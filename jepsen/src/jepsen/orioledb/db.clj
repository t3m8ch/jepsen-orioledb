(ns jepsen.orioledb.db
  "Install the cached build and manage a disposable cluster on n1."
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.logging :refer [info]]
            [jepsen.control :as c]
            [jepsen.control.util :as cu]
            [jepsen.db :as db]
            [jepsen.orioledb.page-stats :as page-stats]
            [jepsen.postgres.client :as pg-client]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]))

(def prefix "/opt/jepsen/pgsql")
(def data-dir "/var/lib/jepsen/pgdata")
(def log-dir "/var/log/jepsen")
(def log-file (str log-dir "/postgresql.log"))
(def build-info-file (str log-dir "/build-info.json"))
(def archive "/var/lib/jepsen/pgsql.tar.gz")
(defn binary [name] (str prefix "/bin/" name))

(defn sql! [test statement]
  (with-open [conn (pg-client/open test (:data-host test))]
    (jdbc/execute-one! conn [statement]
                       {:builder-fn rs/as-unqualified-lower-maps})))

(defn running? []
  (and (cu/exists? (binary "pg_ctl"))
       (= "running"
          (c/sudo "postgres"
                  (c/exec :bash :-c
                          (str "if " (binary "pg_ctl") " -D " data-dir
                               " status >/dev/null 2>&1; then echo running; else echo stopped; fi"))))))

(defn signal! [signal]
  ;; Every PostgreSQL process in this dedicated VM belongs to this installation.
  ;; Restrict the match by both user and executable command name.
  (c/su
   (c/exec :bash :-c
           (str "pkill -" signal " -u postgres -x postgres || test \"$?\" = 1"))))

(defn start! [test]
  (signal! "CONT")
  (when-not (running?)
    (c/sudo "postgres"
            (c/exec (binary "pg_ctl") :-D data-dir :-l log-file :-w :-t 60 :start)))
  (sql! test "SELECT 1"))

(defn check-runtime! [facts]
  (let [release (c/exec :bash :-c ". /etc/os-release; printf '%s' \"$VERSION_ID\"")
        arch (c/exec :dpkg :--print-architecture)]
    (when-not (and (= release (:debian_version facts)) (= arch (:architecture facts)))
      (throw (ex-info "Build/VM Debian or architecture mismatch" {:release release :arch arch})))
    (doseq [[package expected] (:runtime_packages facts)]
      (let [actual (c/exec :dpkg-query :-W "-f=${Version}" (name package))]
        (when-not (= expected actual)
          (throw (ex-info "Build/VM runtime package mismatch"
                          {:package package :expected expected :actual actual})))))))

(defn install! [test]
  (let [artifact (:artifact test)
        facts (json/parse-string (slurp (io/file artifact "build-info.json")) true)]
    (check-runtime! facts)
    ;; Upload into an unprivileged staging directory, then verify as root.
    (let [remote (str (c/exec :mktemp :-d "/tmp/jepsen-artifact.XXXXXXXX") "/pgsql.tar.gz")]
      (try
        (c/upload (io/file artifact "pgsql.tar.gz") remote)
        (let [actual (first (str/split (c/exec :sha256sum remote) #"\s+"))]
          (when-not (= actual (:artifact-sha256 test))
            (throw (ex-info "Uploaded artifact checksum mismatch" {}))))
        (c/su
         (c/exec :mkdir :-p prefix log-dir)
         (c/exec :mv remote archive)
         (c/exec :tar :-xzf archive :-C prefix)
         (c/exec :cp (str prefix "/build-info.json") build-info-file)
         (c/exec :chown :-R "postgres:postgres" "/var/lib/jepsen" log-dir))
        (finally
          (c/exec :rm :-f remote)
          (c/exec :rmdir (.getParent (io/file remote))))))))

(defn engine-settings [test]
  (case (or (:engine test) :orioledb)
    :postgres {:preload "" :access-method "heap"}
    :orioledb {:preload "orioledb" :access-method "orioledb"}))

(defn cluster-config [test]
  (let [{:keys [preload access-method]} (engine-settings test)]
    (str (slurp (io/resource "orioledb.conf"))
         "\nshared_preload_libraries = '" preload "'\n"
         "default_table_access_method = '" access-method "'\n"
         "unix_socket_directories = '/var/lib/jepsen'\n"
         (when (= "orioledb" access-method)
           (str (when-let [size (:main-buffers test)]
                  (str "orioledb.main_buffers = '" size "'\n"))
                (when-let [size (:undo-buffers test)]
                  (str "orioledb.undo_buffers = '" size "'\n")))))))

(defn check-cluster! [test]
  (let [expected (:access-method (engine-settings test))]
    (start! test)
    (when (= "orioledb" expected)
      (sql! test "CREATE EXTENSION orioledb"))
    ;; Upstream workloads omit USING; verify the default for their sessions.
    (sql! test "CREATE TABLE public.jepsen_engine_probe (id int PRIMARY KEY)")
    (try
      (let [actual (:amname
                    (sql! test (str "SELECT am.amname FROM pg_class c JOIN pg_am am ON am.oid=c.relam "
                                    "WHERE c.oid='public.jepsen_engine_probe'::regclass")))]
        (when-not (= expected actual)
          (throw (ex-info "Unexpected default table access method"
                          {:expected expected :actual actual})))
        (info "Verified default table access method:" actual))
      (finally
        (sql! test "DROP TABLE public.jepsen_engine_probe")))))

(defn setup-cluster! [test]
  (c/su
   (c/exec :install :-d :-o "postgres" :-g "postgres" :-m "0755" data-dir)
   (c/exec :truncate :-s 0 log-file)
   (c/exec :chown "postgres:postgres" log-file))
  (c/sudo "postgres"
          (c/exec (binary "initdb") :-D data-dir :--locale=C :--encoding=UTF8)
          (cu/write-file! (cluster-config test)
                          (str data-dir "/postgresql.conf"))
          (cu/write-file! (slurp (io/resource "orioledb-pg_hba.conf"))
                          (str data-dir "/pg_hba.conf")))
  (check-cluster! test))

(defn database []
  (reify
    db/DB
    (setup! [_ test _node]
      (page-stats/stop!)
      (install! test)
      (page-stats/prepare!)
      (setup-cluster! test))
    (teardown! [_ _test _node]
      (page-stats/stop!)
      (signal! "CONT")
      (when (running?)
        (c/sudo "postgres"
                (c/exec (binary "pg_ctl") :-D data-dir :-m :immediate :-w :-t 60 :stop)))
      (signal! "KILL")
      ;; Jepsen collects LogFiles before this teardown. Keep logs for diagnostics.
      (c/su (c/exec :rm :-rf data-dir prefix archive)))

    db/Kill
    (start! [_ test _node] (start! test))
    (kill! [_ _test _node] (signal! "KILL"))

    db/Pause
    (pause! [_ _test _node] (signal! "STOP"))
    (resume! [_ _test _node] (signal! "CONT"))

    db/LogFiles
    (log-files [_ test _node]
      (page-stats/stop!)
      ;; PGDATA is 0700; stage copies readable by the SSH user for Jepsen download.
      (c/su
       (doseq [filename ["postgresql.conf" "pg_hba.conf"]]
         (when (cu/exists? (str data-dir "/" filename))
           (c/exec :cp (str data-dir "/" filename) (str log-dir "/" filename))
           (c/exec :chmod "0644" (str log-dir "/" filename)))))
      (cond-> {log-file "postgresql.log"
       build-info-file "build-info.json"
       (str log-dir "/postgresql.conf") "postgresql.conf"
       (str log-dir "/pg_hba.conf") "pg_hba.conf"}
        (page-stats/enabled? test) (assoc page-stats/log-file "page-stats.jsonl")))))
