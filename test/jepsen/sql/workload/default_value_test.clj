(ns jepsen.sql.workload.default-value-test
  "A test for the default-value workload on Postgres."
  (:require [clj-commons.slingshot :refer [try+ throw+]]
            [clojure [pprint :refer [pprint]]
                     [set :as set]
                     [string :as str]
                     [test :refer :all]]
            [clojure.tools.logging :refer [info warn]]
            [jepsen [checker :as checker]
                    [history :as h]]
            [jepsen.sql.base-test :refer :all]
            [jepsen.sql.workload.default-value :as dv]))

(deftest checker-test
  (let [h (h/history [{:process 0, :type :invoke, :f :read, :value "t1"}
                      {:process 0, :type :ok, :f :read, :value [{:a 1 :c1 1} {:a 1 :c1 nil}]}
                      {:process 0, :type :invoke, :f :read, :value "t1"}
                      {:process 0, :type :ok, :f :read, :value [{:a 1} {:a 1 :c2 2}]}])]
    (is (= {:valid? false
            :read-count 2
            :bad-read-count 1
            :bad-reads [{:op (h 1)
                         :bad-rows [{:a 1 :c1 nil}]}]}
           (checker/check (dv/->Checker) {} h {})))))

(deftest ^:slow default-value-test-read-uncommitted
  (let [test' (run-workload! {:workload  :default-value
                              :isolation :read-uncommitted
                              :logging {:overrides nil}})
        res (:default-value (:results test'))]
    (is (pos? (:read-count res)))
    (is (true? (:valid? res)))))
