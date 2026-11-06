(ns jepsen.sql.workload.internal-sim-test
  "A test for the internal-sim workload on Postgres."
  (:require [clj-commons.slingshot :refer [try+ throw+]]
            [clojure [pprint :refer [pprint]]
                     [set :as set]
                     [string :as str]
                     [test :refer :all]]
            [clojure.test.check :refer [quick-check]]
            [clojure.test.check [properties :as prop]
                                [results :as tc.results]]
            [clojure.tools.logging :refer [info warn]]
            [multiset.core :as multiset :refer [multiset multiset?]]
            [jepsen [core :as jepsen]
                    [generator :as gen]
                    [random :as rand]
                    [tests :refer [noop-test]]]
            [jepsen.sql [ast :as ast]
                        [client :as client]
                        [gen :as sql.gen]
                        [base-test :refer :all]]
            [jepsen.sql.workload.internal-sim :refer :all])
  (:import (jepsen.sql.workload.internal_sim State)))

(defn valid-row
  "Does a row look all right?"
  [row]
  (doseq [[col val] row]
    (is (keyword? col))))

(defn valid-missing-rows
  [{:keys [missing expected actual]}]
  (is (vector? missing))
  ; At least one thing should be missing
  (is (seq missing))
  (is (vector? expected))
  (is (multiset/subset? (into (multiset) missing)
                        (into (multiset) expected)))
  ; All rows should be well-formed
  (mapv valid-row missing)
  (mapv valid-row expected))

(defn valid-not-enough-updated-rows
  [{:keys [expected actual rows]}]
  (is (vector? expected))
  (is (= :at-least (first expected)))
  (is (pos? (second expected)))
  (is (map? actual))
  (is (= 1 (count actual)))
  (is (= :next.jdbc/update-count (key (first actual))))
  (mapv valid-row rows))

(defn valid-unexpected-rows
  [{:keys [negatory unexpected]}]
  (is (vector? negatory))
  (is (vector? unexpected))
  (mapv valid-row unexpected))

(defn valid-error
  [{:keys [index txn statement] :as err}]
  (is (integer? index))
  (is (integer? (:case err)))
  (is (vector? txn))
  (is (every? (fn [mop]
                (and (= #{:statement :results} (set (keys mop)))
                     (vector? (:statement mop))))
              txn))
  (is (vector? statement))
  (is (string? (first statement)))
  (case (:type err)
    :missing-rows (valid-missing-rows err)
    :not-enough-updated-rows (valid-not-enough-updated-rows err)
    :unexpected-rows (valid-unexpected-rows err)))

(def cats-table
  (ast/table "cats"
             [(ast/column "name"     ast/text-type)
              (ast/column "cuteness" ast/integer-type)]))

(def cats-col-names
  (mapv ast/column-name (:cols cats-table)))

(def cats-schema
  (ast/schema [cats-table]))

(deftest select-select-test
  (let [schema  cats-schema
        state   (initial-state cats-schema)
        ; Read all cats with cuteness 2
        state   (check-statement
                  (ast/select "cats" (ast/equals
                                       (ast/column-name "cuteness")
                                       (ast/literal 2)))
                  state
                  [{:name "professor meowington" :cuteness 2}])
        ; This actually tells us that there are *no other* cats with cuteness
        ; 2! On a repeat select, we should be able to notice that.
        state    (check-statement
                   (ast/select "cats" nil)
                   state
                   [{:name "professor meowington", :cuteness 2}
                    {:name "sunflake", :cuteness 2}])]
    (is (= {:type :unexpected-rows
            :statement ["SELECT * FROM cats"]
            :negatory ["((cuteness = ?) AND (NOT ((name = ?) AND (cuteness = ?))))"
                       2 "professor meowington" 2]
            :unexpected [{:name "sunflake", :cuteness 2}]}
           @state))))

(deftest delete-select-test
  (let [schema  cats-schema
        state   (initial-state cats-schema)
        ; Delete every cat with cuteness 2
        state   (check-statement
                  (ast/delete "cats" (ast/equals
                                       (ast/column-name "cuteness")
                                       (ast/literal 2)))
                  state
                  [{:next.jdbc/update-count 3}])
        ; Now let's select a cat and find that it has cuteness 2!
        state    (check-statement
                   (ast/select "cats" nil)
                   state
                   [{:name "professor meowington", :cuteness 2}])]
    (is (= {:type :unexpected-rows
            :statement ["SELECT * FROM cats"]
            :negatory ["(cuteness = ?)" 2]
            :unexpected [{:name "professor meowington", :cuteness 2}]}
           @state))))

(deftest delete-insert-select-test
  (let [schema  cats-schema
        state   (initial-state cats-schema)
        ; Delete every cat with cuteness 2
        state   (check-statement
                  (ast/delete "cats" (ast/equals
                                       (ast/column-name "cuteness")
                                       (ast/literal 2)))
                  state
                  [{:next.jdbc/update-count 3}])
        ; But add a specific cat, scamper, with cuteness 2
        state (check-statement
                (ast/insert "cats" cats-col-names
                            [(ast/literal "scamper") (ast/literal 2)])
                state
                [{:next.jdbc/update-count 1}])
        ; Now let's select all cats and find two, *one* of which is unexpected.
        state    (check-statement
                   (ast/select "cats" nil)
                   state
                   [{:name "professor meowington", :cuteness 2}
                    {:name "scamper", :cuteness 2}])]
    (is (= {:type :unexpected-rows
            :statement ["SELECT * FROM cats"]
            :negatory ["((NOT ((name = ?) AND (cuteness = ?))) AND (cuteness = ?))"
                       "scamper" 2 2]
            :unexpected [{:name "professor meowington", :cuteness 2}]}
           @state))))

(deftest delete-update-select-test
  (let [schema  cats-schema
        state   (initial-state cats-schema)
        ; Delete every cat with cuteness 2
        state   (check-statement
                  (ast/delete "cats" (ast/equals
                                       (ast/column-name "cuteness")
                                       (ast/literal 2)))
                  state
                  [{:next.jdbc/update-count 3}])
        ; Now let's update scamper to have cuteness 2
        state (check-statement
                (ast/update "cats"
                            [[(ast/column-name "cuteness")
                              (ast/literal 2)]]
                            (ast/equals
                              (ast/column-name "name")
                              (ast/literal "scamper")))
                state
                [{:next.jdbc/update-count 1}])
        ; Now let's select all cats and find two. Professor meowington should
        ; have been deleted, but scamper, on account of having been updated to
        ; have cuteness 2, is OK.
        state    (check-statement
                   (ast/select "cats" nil)
                   state
                   [{:name "professor meowington", :cuteness 2}
                    {:name "scamper", :cuteness 2}])]
    (is (= {:type :unexpected-rows
            :statement ["SELECT * FROM cats"]
            :negatory ["((NOT ((cuteness = ?) AND (name = ?))) AND (cuteness = ?))"
                       2 "scamper" 2]
            :unexpected [{:name "professor meowington", :cuteness 2}]}
           @state))))

(deftest update-where-deep-ast-test
  ; This verifies that our update negative hole poker understands the columns
  ; involved in a deeply nested AST.
  (let [table (ast/table "t"
                         [(ast/column "a" ast/boolean-type)
                          (ast/column "b" ast/text-type)])
        schema (ast/schema [table])
        state  (initial-state schema)
        ; Read one row
        state (check-statement
                (ast/select "t" (ast/literal true))
                state
                [{:a true, :b "mew"}])
        ; Update that row
        state (check-statement
                (ast/update "t"
                            [[(ast/column-name "a") (ast/literal false)]
                             [(ast/column-name "b") (ast/literal "mew")]]
                            (ast/->not (ast/->not (ast/column-name "a"))))
                state
                [{:next.jdbc/update-count 1}])
        ; Then read it again
        state (check-statement
                (ast/select "t" nil)
                state
                [{:a false :b "mew"}])]
    ; This is fine!
    (is (instance? State state))))


; This will need us to be able to compare predicates
#_(deftest delete-update-test
    (let [schema  cats-schema
          state   (initial-state cats-schema)
          ; Delete every cat named peaches
          state   (check-statement
                    (ast/delete "cats" (ast/equals
                                         (ast/column-name "name")
                                         (ast/literal "peaches")))
                    state
                    [{:next.jdbc/update-count 2}])
          ; Now let's update a cat named peaches to have cuteness 2
          state (check-statement
                  (ast/update "cats"
                              [[(ast/column-name "cuteness")
                                (ast/literal 2)]]
                              (ast/->And
                                [(ast/equals
                                   (ast/column-name "cuteness")
                                   (ast/literal 1))
                                 (ast/equals
                                   (ast/column-name "name")
                                   (ast/literal "scamper"))]))
                  state
                  [{:next.jdbc/update-count 1}])]
      ; The fact that the update touched a row implies it intersected with
      ; the negative predicate!
      (is (= {:type :unexpected-update
              :statement ["UPDATE cats SET cuteness = ? WHERE cuteness = ? AND name = ?"
                          2 1 "scamper"]
              :result [{:next.jdbc/update-count 1}]
              :negatory [:TODO]}
             @state))))

(deftest ^:slow internal-sim-test
  (rand/with-seed 13
    (let [test' (run-workload! {:log-sql   true
                                :workload  :internal-sim
                                :isolation :read-uncommitted
                                :limit     8192
                                ;:logging   {}
                                })
          res (:internal (:results test'))
          errs (:errors res)]
      (is (false? (:valid? res)))
      (is (pos? (:error-count res)))
      (is (pos? (:txn-count res)))
      (is (= #{:unexpected-rows
               :missing-rows
               :not-enough-updated-rows}
             (set (map :type errs))))
      ;(pprint errs)
      (mapv valid-error errs))))

(deftest ^:slow internal-sim-test-serializable
  (rand/with-seed 13
    (let [test' (run-workload! {:workload  :internal-sim
                                :isolation :serializable
                                :limit     4096
                                ;:logging   {}
                                })
          res (:internal (:results test'))]
      ;(pprint res)
      (is (true? (:valid? res))))))

(defn run-case!
  "Runs a case against the given DB connection. Returns true if everything
  checks out; otherwise false."
  [test conn case]
  (try
    (let [; Set up schema
          _ (mapv (partial statement! test conn) (ast/teardown case))
          _ (mapv (partial statement! test conn) (ast/setup case))
          state (reduce (fn [state sql]
                          (let [results (statement! test conn sql)]
                            (check-statement sql state results)))
                        (initial-state (:schema case))
                        (:statements case))]
      (if (instance? State state)
        true
        (reify tc.results/Result
          (pass? [_] false)
          (result-data [_]
            {:sql (mapv ast/sql
                        (into (ast/setup case)
                              (:statements case)))
             :error state}))))
    (catch Exception e
      (warn e "run-case! threw")
      (throw e))))

(deftest ^:slow qc-test
  ; We set up a whole Jepsen test to run a single "op" which consists of
  ; quickchecking a bunch of cases against Postgres, our reference DB. They
  ; should all pass if we've done our job right.
  (let [res (promise)
        client
        (client/client
          (reify client/Client
            (open! [this test conn node] this)
            (setup! [this test conn])
            (invoke! [this test conn op]
              (deliver res (quick-check 1024
                                        (prop/for-all [case (sql.gen/gen-case {:max-statement-count 32})]
                                                      (run-case! test conn case))
                                        :seed 5675))
              (assoc op :type :ok :value nil))
            (teardown! [this test conn])
            (close! [this test]))
          {:jepsen.sql/open     open
           :jepsen.sql/error-fn error-fn})
        opts (merge base-opts
                    {:concurrency 1
                     ;:logging     nil
                     })
        test (merge noop-test
                    opts
                    {:db (db)
                     :name "internal-sim qc"
                     :generator   (gen/clients [{:f :qc}])
                     :client      client})
        test (jepsen/run! test)]
    (when-let [err (:result-data (:shrunk @res))]
      (mapv prn (:sql err))
      (pprint (:error err)))
    (is (:pass? @res))))
