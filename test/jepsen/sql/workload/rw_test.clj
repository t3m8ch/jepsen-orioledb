(ns jepsen.sql.workload.rw-test
  "A test for the rw workload on Postgres."
  (:require [clj-commons.slingshot :refer [try+ throw+]]
            [clojure [pprint :refer [pprint]]
                     [set :as set]
                     [string :as str]
                     [test :refer :all]]
            [clojure.tools.logging :refer [info warn]]
            [jepsen.sql.base-test :refer :all]))

(deftest ^:slow rw-test-read-uncommitted
  (let [test' (run-workload! {:workload  :rw
                              :isolation :read-uncommitted
                              :expected-consistency-model :serializable})
        res (:rw (:results test'))]
    (is (false? (:valid? res)))
    (is (set/superset? (set (:anomaly-types res))
                       #{:internal}))))

(deftest ^:slow rw-test-serializable
  (let [test' (run-workload! {:workload  :rw
                              :isolation :serializable
                              ;:encodings [:integer]
                              :expected-consistency-model :serializable})
        res (:rw (:results test'))]
    (pprint res)
    (is (true? (:valid? res)))))
