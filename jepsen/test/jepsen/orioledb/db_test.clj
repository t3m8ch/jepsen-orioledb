(ns jepsen.orioledb.db-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [jepsen.control :as c]
            [jepsen.control.util :as cu]
            [jepsen.core :as core]
            [jepsen.db :as jdb]
            [jepsen.lazyfs :as lazyfs]
            [jepsen.nemesis :as nem]
            [jepsen.nemesis.combined :as nc]
            [jepsen.orioledb.db :as db]
            [jepsen.orioledb.nemesis :as orioledb-nemesis]
            [jepsen.orioledb.page-stats :as page-stats]
            [jepsen.store :as store]))

(deftest lazyfs-mount-precedes-initdb-when-enabled
  (doseq [enabled? [false true]]
    (let [steps (atom [])]
      (with-redefs [c/exec (fn [& [cmd]]
                             (when (= (db/binary "initdb") cmd)
                               (swap! steps conj :initdb)))
                    cu/write-file! (fn [& _])
                    db/check-cluster! (fn [_])
                    db/mount-lazyfs! #(swap! steps conj :mount)]
        (db/setup-cluster! {:lazyfs enabled?}))
      (is (= (if enabled? [:mount :initdb] [:initdb]) @steps)))))

(deftest unmount-pgdata-rejects-an-unexpected-filesystem
  (let [commands (atom [])]
    (with-redefs [c/exec (fn [& args]
                           (swap! commands conj (first args))
                           "ext4")]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"unexpected mount"
                            (db/unmount-pgdata!)))
      (is (= [:findmnt] @commands)))))

(deftest cluster-config-selects-engine
  (doseq [[engine preload access-method] [[:postgres "" "heap"]
                                          [:orioledb "orioledb" "orioledb"]]]
    (let [config (db/cluster-config {:engine engine})]
      (is (str/includes? config (str "shared_preload_libraries = '" preload "'")))
      (is (str/includes? config (str "default_table_access_method = '" access-method "'")))
      (is (str/includes? config "unix_socket_directories = '/var/lib/jepsen'"))))
  (is (= (db/cluster-config {}) (db/cluster-config {:engine :orioledb}))))

(def engine-probe-query
  (str "SELECT am.amname FROM pg_class c JOIN pg_am am ON am.oid=c.relam "
       "WHERE c.oid='public.jepsen_engine_probe'::regclass"))

(deftest buffer-settings-are-optional-and-orioledb-only
  (doseq [engine [:postgres :orioledb]]
    (let [config (db/cluster-config {:engine engine})]
      (is (not (str/includes? config "orioledb.main_buffers")))
      (is (not (str/includes? config "orioledb.undo_buffers")))))
  (doseq [[option guc] [[:main-buffers "main_buffers"] [:undo-buffers "undo_buffers"]]]
    (is (str/includes? (db/cluster-config {option "32MB"})
                       (str "orioledb." guc " = '32MB'\n"))))
  (let [config (db/cluster-config {:engine :postgres
                                   :main-buffers "8MB" :undo-buffers "32MB"})]
    (is (not (str/includes? config "orioledb.main_buffers")))
    (is (not (str/includes? config "orioledb.undo_buffers")))))

(def create-probe "CREATE TABLE public.jepsen_engine_probe (id int PRIMARY KEY)")
(def drop-probe "DROP TABLE public.jepsen_engine_probe")
(def create-extension "CREATE EXTENSION orioledb")

(defn with-cluster-stub [actual run-test]
  (let [statements (atom [])]
    (with-redefs [db/start! (fn [_])
                  db/sql! (fn [_ statement]
                            (swap! statements conj statement)
                            (cond
                              (= engine-probe-query statement) {:amname actual}
                              (#{create-probe drop-probe create-extension} statement) nil
                              :else (throw (ex-info "Unexpected SQL in cluster probe"
                                                    {:statement statement}))))]
      (run-test statements))))

(deftest cluster-accepts-matching-access-method
  (doseq [[engine actual] [[:postgres "heap"] [:orioledb "orioledb"]]]
    (testing (str "engine=" engine ", actual=" actual)
      (with-cluster-stub actual
        (fn [statements]
          ;; Any exception fails the test; the return value is not a contract.
          (db/check-cluster! {:engine engine})
          (is (some #{create-probe} @statements))
          (is (some #{engine-probe-query} @statements))
          (is (= drop-probe (last @statements))))))))

(deftest cluster-rejects-mismatched-access-method
  (doseq [[engine expected] [[:postgres "heap"] [:orioledb "orioledb"]]]
    (testing (str "engine=" engine ", expected=" expected ", actual=wrong-engine")
      (with-cluster-stub "wrong-engine"
        (fn [statements]
          (let [error (try
                        (db/check-cluster! {:engine engine})
                        nil
                        (catch clojure.lang.ExceptionInfo e e))]
            (is (= {:expected expected :actual "wrong-engine"} (ex-data error))))
          (is (some #{create-probe} @statements))
          (is (= drop-probe (last @statements))))))))

(deftest cluster-creates-extension-only-for-orioledb
  (doseq [[engine actual extension?] [[:postgres "heap" false]
                                      [:orioledb "orioledb" true]]]
    (testing (str "engine=" engine ", extension?=" extension?)
      (with-cluster-stub actual
        (fn [statements]
          (db/check-cluster! {:engine engine})
          (is (= extension? (boolean (some #{create-extension} @statements)))))))))

(deftest postgres-exit-script-is-installed-only-for-cache-loss
  (doseq [enabled? [false true]]
    (let [steps (atom [])
          script (str db/prefix "/await-postgres-exit.sh")]
      (with-redefs [page-stats/stop! (fn [])
                    db/install! (fn [_] (swap! steps conj :install))
                    cu/write-file! (fn [body path]
                                     (is (str/starts-with? body "#!/bin/bash"))
                                     (swap! steps conj [:write path]))
                    page-stats/prepare! (fn [])
                    db/setup-cluster! (fn [_] (swap! steps conj :cluster))
                    c/exec (fn [& args] (swap! steps conj (vec args)))]
        (jdb/setup! (db/database) {:lose-unfsynced-writes enabled?} "n1")
        (when enabled?
          (db/await-postgres-exit!)))
      (is (= (if enabled?
               [:install [:write script] :cluster [:timeout "10s" :bash script]]
               [:install :cluster])
             @steps)))))

(deftest cache-loss-kill-clears-cache-after-exit
  (let [steps (atom [])
        database (db/database)]
    (with-redefs [db/signal! #(swap! steps conj %)
                  db/await-postgres-exit! #(swap! steps conj :exit)
                  db/lose-unfsynced-writes! #(do (swap! steps conj :clear) :cache-cleared)]
      (is (= :cache-cleared (db/kill-with-cache-loss! database {} "n1")))
      (is (= ["KILL" :exit :clear] @steps)))))

(deftest cache-loss-nemesis-intercepts-kill-and-delegates-start
  (let [database (db/database)
        test {:nodes ["n1"]}
        wrapper (nem/setup! (orioledb-nemesis/wrap-cache-loss-kill
                             (nc/db-nemesis database) database) test)
        steps (atom [])]
    (with-redefs [c/on-nodes (fn [test nodes f]
                               (into {} (map (fn [node] [node (f test node)])) nodes))
                  db/signal! #(swap! steps conj %)
                  db/await-postgres-exit! #(swap! steps conj :exit)
                  db/lose-unfsynced-writes! (constantly :cache-cleared)
                  db/start! (constantly :started)]
      (is (= {:f :kill :value {"n1" :cache-cleared}}
             (nem/invoke! wrapper test {:f :kill :value :all})))
      (is (= ["KILL" :exit] @steps))
      (is (= {:f :start :value {"n1" :started}}
             (nem/invoke! wrapper test {:f :start :value :all}))))))

(deftest log-collection-kill-does-not-drop-cache
  (let [database (db/database)
        steps (atom [])]
    (with-redefs [db/signal! #(swap! steps conj %)
                  db/await-postgres-exit! #(swap! steps conj :exit)
                  db/lose-unfsynced-writes! #(swap! steps conj :clear)]
      (jdb/kill! database {:lose-unfsynced-writes true} "n1")
      (is (= ["KILL"] @steps)))))

(deftest unconfirmed-cache-loss-blocks-restart-and-retry-but-not-log-collection
  (let [database (db/database)
        test {:db database :nodes ["n1"] :lazyfs true :lose-unfsynced-writes true}
        steps (atom [])
        downloads (atom #{})]
    (with-redefs [db/signal! #(swap! steps conj %)
                  db/await-postgres-exit! #(swap! steps conj :exit)
                  db/lose-unfsynced-writes! #(do (swap! steps conj :clear)
                                                 (throw (ex-info "timeout" {})))
                  db/start! (constantly :started)
                  c/on-nodes (fn [test f] (f test "n1"))
                  c/exec (fn [& _])
                  cu/exists? (constantly true)
                  page-stats/stop! (fn [])
                  store/path! (fn [_ _ filename] (java.io.File. filename))
                  store/update-symlinks! (fn [_])
                  c/download (fn [remote _] (swap! downloads conj remote))]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"timeout"
                            (db/kill-with-cache-loss! database test "n1")))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Cannot restart"
                            (jdb/start! database test "n1")))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Previous LazyFS cache loss"
                            (db/kill-with-cache-loss! database test "n1")))
      (core/snarf-logs! test)
      (is (= ["KILL" :exit :clear "KILL"] @steps))
      (is (contains? @downloads db/log-file))
      (is (contains? @downloads (:log-file db/pg-lazyfs)))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Cannot restart"
                            (jdb/start! database test "n1"))))))

(deftest cache-drop-waits-for-log-appended-after-request
  (let [steps (atom [])]
    (with-redefs [c/exec (fn [& args]
                           (swap! steps conj args)
                           (when (= :stat (first args)) "42"))
                  lazyfs/lose-unfsynced-writes! #(swap! steps conj [:request %])]
      (is (= :cache-cleared (db/lose-unfsynced-writes!)))
      (let [[stat request wait] @steps]
        (is (= :stat (first stat)))
        (is (= [:request db/pg-lazyfs] request))
        (is (= :timeout (first wait)))
        (is (= [43 (:log-file db/pg-lazyfs)] (vec (take-last 2 wait))))))))

(deftest cache-loss-mode-syncs-config-before-first-start
  (doseq [enabled? [false true]]
    (let [steps (atom [])]
      (with-redefs [c/exec (fn [& [cmd & args]]
                             (when (= :sync cmd)
                               (swap! steps conj (vec args))))
                    cu/write-file! (fn [_ path] (swap! steps conj path))
                    db/check-cluster! (fn [_] (swap! steps conj :start))]
        (db/setup-cluster! {:lose-unfsynced-writes enabled?}))
      (let [conf (str db/data-dir "/postgresql.conf")
            hba (str db/data-dir "/pg_hba.conf")]
        (is (= (if enabled? [conf hba [conf hba] :start] [conf hba :start]) @steps))))))
