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

(deftest cluster-probes-actual-access-method
  (doseq [[engine expected] [[:postgres "heap"] [:orioledb "orioledb"]]
          actual [expected "wrong-engine"]]
    (let [statements (atom [])
          started (atom nil)
          test {:engine engine}]
      (with-redefs [db/start! #(reset! started %)
                    db/sql! (fn [_ statement]
                              (swap! statements conj statement)
                              {:amname actual})]
        (if (= expected actual)
          (is (nil? (db/check-cluster! test)))
          (let [error (try (db/check-cluster! test) nil
                           (catch clojure.lang.ExceptionInfo e e))]
            (is (= {:expected expected :actual actual} (ex-data error))))))
      (is (= test @started))
      (is (= (= engine :orioledb)
             (boolean (some #{"CREATE EXTENSION orioledb"} @statements))))
      (is (some #{"CREATE TABLE public.jepsen_engine_probe (id int PRIMARY KEY)"} @statements))
      (is (= "DROP TABLE public.jepsen_engine_probe" (last @statements))))))
