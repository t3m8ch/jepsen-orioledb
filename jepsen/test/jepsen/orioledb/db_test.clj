(ns jepsen.orioledb.db-test
  (:require [clojure.string :as str]
            [clojure.test :refer :all]
            [jepsen.orioledb.db :as db]))

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
