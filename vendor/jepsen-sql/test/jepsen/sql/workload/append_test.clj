(ns jepsen.sql.workload.append-test
  "A test for the list-append workload on Postgres."
  (:require [clj-commons.slingshot :refer [try+ throw+]]
            [clojure [pprint :refer [pprint]]
                     [set :as set]
                     [string :as str]
                     [test :refer :all]]
            [clojure.tools.logging :refer [info warn]]
            [jepsen.sql.base-test :refer :all]
            [jepsen.sql.client :as c]
            [jepsen.sql.workload.append :as append]
            [next.jdbc :as j]))

(deftest ^:slow append-test
  (let [test' (run-workload! {:workload                   :append
                              :isolation                  :read-uncommitted
                              :expected-consistency-model :serializable})
        res (:append (:results test'))]
    (is (false? (:valid? res)))
    ; (pprint (:results test'))
    (is (set/superset? (set (:anomaly-types res))
                       #{:internal :lost-update}))))

(deftest ^:slow indirect-append-test-read-uncommitted
  (let [test' (run-workload! {:workload                   :append
                              :isolation                  :read-uncommitted
                              :expected-consistency-model :serializable
                              :log-sql                    true
                              :indirection?               true})
        res (:append (:results test'))]
    (is (false? (:valid? res)))
    ; (pprint (:results test'))
    (is (set/superset? (set (:anomaly-types res))
                       #{:internal :lost-update :G2-item}))))

(deftest ^:slow indirect-append-test-serializable
  (let [test' (run-workload! {:workload                   :append
                              :isolation                  :serializable
                              :expected-consistency-model :serializable
                              :log-sql                    true
                              :indirection?               true})
        res (:append (:results test'))]
    (is (true? (:valid? res)))
    ; (pprint (:results test'))
    ))

(deftest update-insert-update-retries-update-after-insert-conflict
  (let [calls (atom [])
        updates (atom 0)]
    (binding [c/*error-fn*
              (constantly {:type :duplicate-key-value
                           :definite? true})]
      (with-redefs
       [append/append-update!
        (fn [& _]
          (swap! calls conj :update)
          ; nb: only the second update succeeds
          (= 2 (swap! updates inc)))

        append/insert!
        (fn [& _]
          (swap! calls conj :insert)
          (throw (Exception. "duplicate key")))

        j/execute!
        (fn [_ [sql & _]]
          (swap! calls conj
                 (case sql
                   "SAVEPOINT UPSERT"             :savepoint
                   "ROLLBACK TO SAVEPOINT upsert" :rollback
                   "RELEASE SAVEPOINT upsert"     :release
                   :other))
          ; return a truthy value a la next.jdbc
          [{}])]

        (is (true?
             (append/append-update-insert-update!
              {:savepoints true} ::conn "txn0" true 1 2)))
        (is (= [:update :savepoint :insert :rollback :update] @calls))
        (is (= 2 @updates))))))
