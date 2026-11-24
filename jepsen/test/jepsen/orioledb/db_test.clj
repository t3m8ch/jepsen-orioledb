(ns jepsen.orioledb.db-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [jepsen.control :as c]
            [jepsen.control.util :as cu]
            [jepsen.db :as jdb]
            [jepsen.lazyfs :as lazyfs]
            [jepsen.orioledb.db :as db]))

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

(deftest kill-only-clears-cache-when-enabled-and-after-exit
  (let [steps (atom [])
        database (db/database)]
    (with-redefs [db/signal! #(swap! steps conj %)
                  db/await-postgres-exit! #(swap! steps conj :exit)
                  db/lose-unfsynced-writes! #(do (swap! steps conj :clear) :cache-cleared)]
      (jdb/kill! database {:lazyfs true} "n1")
      (is (= ["KILL"] @steps))
      (reset! steps [])
      (is (= :cache-cleared (jdb/kill! database {:lose-unfsynced-writes true} "n1")))
      (is (= ["KILL" :exit :clear] @steps)))))

(deftest unconfirmed-cache-loss-blocks-restart-and-retry
  (let [database (db/database)
        test {:lose-unfsynced-writes true}]
    (with-redefs [db/signal! (fn [_])
                  db/await-postgres-exit! (fn [])
                  db/lose-unfsynced-writes! #(throw (ex-info "timeout" {}))
                  db/start! (constantly :started)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"timeout"
                            (jdb/kill! database test "n1")))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Cannot restart"
                            (jdb/start! database test "n1")))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Previous LazyFS cache loss"
                            (jdb/kill! database test "n1"))))))

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
