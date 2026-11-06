(ns jepsen.sql.base-test
  "Support functions for other tests."
  (:require [clj-commons.slingshot :refer [try+ throw+]]
            [clojure [pprint :refer [pprint]]
                     [set :as set]
                     [string :as str]
                     [test :refer :all]]
            [clojure.java.io :as io]
            [clojure.tools.logging :refer [info warn]]
            [jepsen [control :as c :refer [|]]
                    [core :as jepsen]
                    [db :as db]
                    [generator :as gen]
                    [util :as util :refer [meh random-nonempty-subset]]
                    [sql :as sql]
                    [tests :refer [noop-test]]]
            [jepsen.control.util :as cu]
            [jepsen.control.net :as cn]
            [jepsen.os.debian :as debian]
            [next.jdbc :as j]))

;; DB automation, shamelessly stolen from the postgres test.

(def user
  "The OS user which will run postgres."
  "postgres")

(def just-postgres-log-file
  "/var/log/postgresql/postgresql-18-main.log")

(defn install-pg!
  "Installs postgresql"
  [test node]
  (c/su
    (when-not (debian/installed? :postgresql-18)
      ; Add repo
      (debian/install ["postgresql-common"])
      (info "Adding Postgres apt repos")
      (c/exec :echo "" | "/usr/share/postgresql-common/pgdg/apt.postgresql.org.sh")
      ; Install
      (debian/install [:postgresql-18 :postgresql-client-18])
      ; Deactivate default install
      (c/exec :service :postgresql :stop)
      (c/exec "update-rc.d" :postgresql :disable))))

(defn db
  "A database which just runs a regular old single-node Postgres instance"
  []
  (reify db/DB
    (setup! [_ test node]
      (install-pg! test node)
      (c/su (cu/write-file! (slurp (io/file "test-resources" "pg_hba.conf"))
                            "/etc/postgresql/18/main/pg_hba.conf")
            (cu/write-file! (slurp (io/file "test-resources" "jepsen.conf"))
                            "/etc/postgresql/18/main/conf.d/99-jepsen.conf"))

        ; Create fresh data dir
        (c/sudo user
                ; Can't create if it exists--installing will make this dir
                (c/exec :rm :-rf (c/lit "/var/lib/postgresql/18/main/*"))
                (c/exec "/usr/lib/postgresql/18/bin/initdb"
                        :-D "/var/lib/postgresql/18/main"))

        (c/su (c/exec :service :postgresql :start))
        (cu/await-tcp-port 5432))

      (teardown! [_ test node]
        (c/su (try+ (c/exec :service :postgresql :stop)
                    ; Not installed?
                    (catch [:exit 5] _))
              ; This might not actually work, so we have to kill the processes
              ; too
              (cu/grepkill! "postgres")
              (c/exec :rm :-rf (c/lit "/var/lib/postgresql/18/main/*")))
        (try+ (c/sudo user
                      (c/exec :truncate :-s 0 just-postgres-log-file))
              (catch [:exit 1] _)) ; No user (not installed)
        )

      db/LogFiles
      (log-files [_ test node]
        [just-postgres-log-file])

      db/Process
      (start! [db test node]
        (c/su (c/exec :service :postgresql :restart)))

      (kill! [db test node]
        (doseq [pattern (shuffle
                          ["postgres -D" ; Main process
                           "main: checkpointer"
                           "main: background writer"
                           "main: walwriter"
                           "main: autovacuum launcher"])]
          (Thread/sleep (rand-int 100))
          (info "Killing" pattern "-" (cu/grepkill! pattern))))))

;; PG client

(defn open
  "Opens a connection to the given node."
  [test node]
  (let [spec {:dbtype   "postgresql"
              :host     node
              :port     5432
              :user     "postgres"
              :password "pw"
              :sslmode  "disable"}
        ds    (j/get-datasource spec)
        conn  (j/get-connection ds)]
    conn))

(defn error-fn
  [^Exception e]
  (let [msg (.getMessage e)]
    (condp identical? (class e)
      org.postgresql.util.PSQLException
      (condp re-find msg
        #"could not serialize access due to .+ dependencies"
        {:type :dependency-cycle :definite? true}

        #"could not serialize access due to concurrent update"
        {:type :concurrent-update, :definite? true}

        #"could not serialize access due to concurrent delete"
        {:type :concurrent-delete, :definite? true}

        #"current transaction is aborted"
        {:type :txn-aborted, :definite? true}

        #"deadlock detected"
        {:type :deadlock, :definite? true}

        #"duplicate key value"
        {:type :duplicate-key-value, :definite? true}

        #"multiple primary keys for table .+ are not allowed"
        {:type :multiple-primary-keys, :definite? true}

        #"relation .+ does not exist"
        {:type :relation-does-not-exist, :definite? true}

        #"table .+? does not exist"
        {:type :table-does-not-exist, :definite? true}

        #"violates not-null constraint"
        {:type :not-null-constraint, :definite? true}

        nil)
      nil)))

(def workloads
  (sql/workloads {:open     open
                  :error-fn error-fn}))

(def base-opts
  "Basic options we use over and over."
  {:nodes              ["n1"]
   :concurrency        3
   :isolation          :serializable
   :max-txn-length     4
   :mop-delay          5
   :key-types          (vec sql/key-types)
   :upsert-types       (vec sql/upsert-types)
   :linearizable-keys? true
   :log-sql            true
   :logging
   ; We're going to run a bunch of our own tests here and the log noise is
   ; incredible. Comment these out to get more details.
   {:overrides
    #_nil
    {"jepsen.core" :warn
     "jepsen.db" :warn
     "jepsen.print" :warn
     "jepsen.store" :warn
     "jepsen.sql.base-test" :warn
     "jepsen.sql.client" :warn
     "elle.viz" :warn
     "net.schmizz.sshj.SSHClient" :warn}}})

(defn run-workload!
  "Runs a test for the given options, which are merged into base-opts."
  [opts]
  (let [opts          (merge base-opts opts)
        workload-name (:workload opts)
        workload      ((get workloads workload-name) opts)
        test (merge noop-test
                    opts
                    workload
                    {:db        (db)
                     :name      (name workload-name)
                     :generator (->> (:generator workload)
                                     (gen/clients)
                                     (gen/limit (:limit opts 1024)))})]
    (jepsen/run! test)))
