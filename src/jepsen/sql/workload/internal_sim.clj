(ns jepsen.sql.workload.internal-sim
  "A more complex test for internal consistency. Generates random schemas and
  transactions over them. For each transaction, verifies that the operations
  within that transaction appear to execute in isolation. This is not
  particularly smart; we build up a partial set of known-extant rows over time
  and flag places where a row should have appeared but did not. This will not
  catch things like 'I deleted everything then read a row'."
  (:refer-clojure :exclude [read])
  (:require [clojure.tools.logging :refer [info warn]]
            [clojure [pprint :refer [pprint]]
                     [set :as set]
                     [string :as str]]
            [dom-top.core :refer [loopr with-retry]]
            [elle.core :as elle]
            [jepsen [checker]
                    [client :as client]
                    [core :as jepsen]
                    [generator :as gen]
                    [history :as h]
                    [random :as rand]
                    [util :as util]]
            [jepsen.sql [client :as c]
                        [checker :as checker :refer [assert-at-most-one
                                                     assert-instance-or-nil]]
                        [encoding :as encoding]
                        [ast :as ast]
                        [gen :as sql.gen]]
            [multiset.core :as multiset]
            [next.jdbc :as j]
            [next.jdbc.result-set :as rs]
            [next.jdbc.sql.builder :as sqlb]
            [clj-commons.slingshot :refer [try+ throw+]]
            [tesser.core :as t])
  (:import (jepsen.sql.ast ColumnName
                           Delete
                           Equals
                           Insert
                           Literal
                           Select
                           Statement
                           Update)))

(defn update-row
  "Takes a row and a series of [column expr] pairs. Returns the row with each
  column set to expr."
  [row pairs]
  (persistent!
    (reduce (fn [row [col expr]]
              (assoc! row (keyword (:name col)) (ast/eval expr row)))
            (transient row)
            pairs)))

(defrecord Table [; Function that takes a row and returns the primary key
                  pkey-fn
                  ; Function that sorts rows
                  sort-fn
                  ; A multiset of rows that we know definitely exist in the
                  ; table.
                  rows
                  ; An Eval-able predicate of rows that we know *definitely
                  ; do not exist* in the table. For example, if we DELETE ...
                  ; WHERE wit = 'sharp', this predicate would be (wit = 'sharp'
                  ; OR ...). If we can ever show that this predicate
                  ; definitively intersects with at least one row
                  ; affected/returned by a statement, then we've found a fault!
                  neg])

(defrecord State [tables])

(defn initial-state
  "Computes an initial State for a schema."
  [schema]
  (->> (:tables schema)
       (map (fn [table]
              (let [pkeys (->> (:cols table)
                               (filter :primary-key?)
                               (map :name)
                               (map keyword))
                    pkey-fn (case (count pkeys)
                              0 nil
                              1 (first pkeys)
                              (apply juxt pkeys))
                    cols (sort (map (comp keyword :name) (:cols table)))
                    sort-fn (fn [a b]
                              (loop [cols cols]
                                (if (seq cols)
                                  (let [col (first cols)
                                        c (compare
                                            (get a col)
                                            (get b col))]
                                    (if (= 0 c)
                                      (recur (next cols))
                                      c))
                                  ; No columns left; must be equal
                                  0)))]
                [(:name table)
                 (Table. pkey-fn sort-fn
                         (multiset/multiset)
                         (ast/literal false)
                         )])))
       (into (sorted-map))
       (State.)))

(defn sorted-rows
  "When returning errors, it's much nicer to sort. Takes a State, the string
  table name, and collection of rows, and makes a sorted vector of those rows,
  each row a sorted map."
  [state table rows]
  (let [sort-fn (-> state :tables (get table) :sort-fn)]
    (->> rows
         (map (partial into (sorted-map)))
         (sort sort-fn)
         vec)))

(defprotocol CheckStatement
  (check-statement [statement state results]
                   "Checks a single statement. Takes a statement, a State, and
                   the results of evaluating the statement. Returns either a
                   new State or an error map, if we can show the statement was
                   inconsistent with this State."))

(extend-protocol CheckStatement
  ; When we have an insert, we know its rows become a part of the state map.
  Insert
  (check-statement [statement state results]
    (if (map? results)
      ; Error
      state
      ; Success
      (let [table-name (:name (:table statement))
            table      (get (:tables state) table-name)
            ; Add this row to the table's rows
            row        (->> (:values statement)
                            ; Evaluate each value
                            (map (fn [expr]
                                   (ast/eval expr nil)))
                            ; And zip together with columns
                            (zipmap (map (comp keyword :name)
                                         (:cols statement))))
            rows'     (conj (:rows table) row)
            ; An insert also "pokes a hole" in our negative predicate.
            neg' (ast/->and
                   [(ast/->not (ast/row->pred row))
                    (:neg table)])
            neg' (ast/simplify neg')]
        (assoc-in state [:tables table-name]
                  (assoc table :rows rows' :neg neg')))))

    ; When we perform an update, we apply it to the rows we know about.
    Update
    (check-statement [statement state results]
      (if (map? results)
        ; Error
        state
        ; Success
        (let [table-name (:name (:table statement))
              table (get (:tables state) table-name)
              where (:where statement)
              ; Work through each row, transforming it if it matches the
              ; predicate
              rows'
              (loopr [rows'        (:rows table)
                      update-count 0]
                     [row (:rows table)]
                     (if (or (nil? where) (ast/eval where row))
                       ; An interesting question: what happens if you come back
                       ; around to modify the same row again? I *think* this is
                       ; fine functionally; either row would be equivalent.
                       (recur (-> rows'
                                  (disj row)
                                  (conj (update-row row (:set statement))))
                              (inc update-count))
                       (recur rows' update-count))
                     ; We know a subset of the DB, so would be bad if we updated
                     ; more rows than the database did!
                     (cond (< (:next.jdbc/update-count (first results))
                              update-count)
                           (reduced
                             {:type      :not-enough-updated-rows
                              :statement (ast/sql statement)
                              :expected  [:at-least update-count]
                              :actual    (first results)
                              :rows      (sorted-rows state table-name
                                                      (:rows table))})

                           ; OK, all set
                           true
                           rows'))
              ; Our update also pokes holes in the negative predicate by
              ; creating rows which a.) match the predicate and are b.)
              ; transformed by the SET. The problem is that the SET clause can
              ; do *arbitrary* transformations, which makes it hard to figure
              ; out what the matching predicate would be. We can, however, do
              ; some simple tricks. One of them is that if any of the columns
              ; are set to a static value, we can use those to make a smaller
              ; hole in neg.
              set-preds
              (->> (:set statement)
                   (keep (fn [[col expr]]
                           (when (ast/eval-without-row? expr)
                             (Equals. col (ast/literal (ast/eval expr nil))))))
                   vec)

              ; If we can show that the WHERE does not depend on the
              ; columns that we'll be changing, then the predicate would match
              ; the same rows after the UPDATE as before; we can further
              ; restrict ourselves to the WHERE clause.
              set-preds (cond ; Everything's affected
                              (nil? where)
                              set-preds

                              ; Shoot, the WHERE involves a modified column
                              (seq (set/intersection
                                     (ast/column-names-in where)
                                     (set (map first (:set statement)))))
                              set-preds

                              ; Ha! We can further restrict it.
                              true
                              (conj set-preds where))
              neg' (ast/->and [(ast/->not (ast/->and set-preds))
                               (:neg table)])
              neg' (ast/simplify neg')]
          (cond ; Error!
                (reduced? rows')
                rows'

                true
                (assoc-in state [:tables table-name]
                          (assoc table :rows rows', :neg neg'))))))

    ; When we delete something, we destroy every matching row in our set.
    Delete
    (check-statement [statement state results]
      (if (map? results)
        ; Error
        state
        ; Success
        (let [table-name  (:name (:table statement))
              where       (:where statement)
              table       (-> state :tables (get table-name))
              ; Delete each row matching the predicate
              rows'
              (loopr [rows'         (:rows table)
                      update-count  0]
                     [row (:rows table)]
                     (if (or (nil? where) (ast/eval where row))
                       (recur (disj rows' row)
                              (inc update-count))
                       (recur rows' update-count))
                     ; We know a subset of the DB, so it would be bad if we
                     ; deleted more rows than the DB did.
                     (cond (< (:next.jdbc/update-count (first results))
                              update-count)
                           {:type :not-enough-updated-rows
                            :statement (ast/sql statement)
                            :expected  [:at-least update-count]
                            :actual    (first results)
                            :rows      (sorted-rows state table-name
                                                    (:rows table))}
                           ; All set
                           true
                           rows'))
              ; And expand the negative predicate to cover anything we deleted.
              neg' (if where
                     (ast/simplify (ast/->or [where (:neg table)]))
                     ; Deleted everything
                     (ast/literal true))
              table' (assoc table :rows rows' :neg neg')]
          ; Return errors if they happened
          (cond
            (map? rows')
            (reduced rows')

            true
            (assoc-in state [:tables table-name] table')))))

    ; When we have a select, we evaluate the predicate against our state and
    ; ensure that all the rows we think should be present are present.
    Select
    (check-statement [statement state results]
      (if (map? results)
        ; Error
        state
        ; Success
        (let [table-name  (:name (:table statement))
              table       (-> state :tables (get table-name))
              results     (into (multiset/multiset) results)
              ; What rows should be present?
              required (->> (:rows table)
                            (filter
                              (if-let [w (:where statement)]
                                (partial ast/eval w)
                                ; With no WHERE clause, you match everything
                                (constantly true)))
                            (into (multiset/multiset)))
              missing (multiset/minus required results)
              ; Is there anything that should *not* be present?
              neg        (:neg table)
              unexpected (->> results
                              (filter (partial ast/eval neg))
                              (into (multiset/multiset)))]
          (cond (seq missing)
                (reduced {:type       :missing-rows
                          :statement  (ast/sql statement)
                          :missing    (sorted-rows state table-name missing)
                          :expected   (sorted-rows state table-name required)
                          :actual     (sorted-rows state table-name results)})

                (seq unexpected)
                (reduced {:type       :unexpected-rows
                          :statement  (ast/sql statement)
                          :negatory   (ast/sql neg)
                          :unexpected (sorted-rows state table-name unexpected)})

                ; OK, this is valid.
                true
                (let [; We know these specific rows now exist
                      rows' (multiset/union (:rows table) results)
                      ; And we *also* know that there cannot be any other rows
                      ; which match the select predicate and are *not* one of
                      ; these rows.
                      neg' (ast/->or
                             [(ast/->and
                               [; Matches predicate
                                (or (:where statement)
                                    (ast/literal true))
                                ; And not one of the rows
                                (ast/->not
                                  (ast/->or
                                    (mapv ast/row->pred results)))])
                             (:neg table)])
                      neg' (ast/simplify neg')]
                (assoc-in state [:tables table-name]
                         (assoc table :rows rows' :neg neg'))))))))

(defn check-txn
  "Takes an initial State and a transaction, represented as a series of SQL AST
  nodes, and a vector of corresponding results. Returns nil if no anomalies
  detected, or an anomaly map if we can find something wrong."
  [initial-state sql results]
  ;(prn)
  ;(println "## Check txn")
  (let [r (reduce (fn [state [statement results]]
                    (check-statement statement state results))
                  initial-state
                  (mapv vector sql results))]
    ; More robust than checking for instance of State, with hot code reloading
    (when (:type r)
      r)))

(defn check-op
  "Takes a history, a map of case numbers to initial States, and a completion
  operation. Returns nil if no anomalies detected, or an anomaly map if we can
  find something wrong."
  [history initial-states complete]
  (let [invoke (h/invocation history complete)
        initial-state (get initial-states (:case invoke))]
    (when-let [err (check-txn initial-state (:value invoke) (:value complete))]
      (assoc err
             :case  (:case invoke)
             :index (:index complete)
             :txn (mapv (fn [statement results]
                          {:statement (ast/sql statement)
                           :results   results})
                        (:value invoke) (:value complete))))))

;; Client

(defn statement!
  "Applies a single statement. Returns results. Results may also be a map,
  which denotes an error."
  [test conn statement]
  (Thread/sleep (rand/zipf (:mop-delay test)))
  (let [sql (ast/sql statement)]
    (c/try+
      (j/execute! conn sql {:builder-fn rs/as-unqualified-lower-maps})
      (catch [:definite? true] e
        e))))

(defrecord Client []
  c/Client
  (open! [this test conn node]
    this)

  (setup! [_ test conn])

  (invoke! [_ test conn op]
    (c/with-txn test [t conn]
      (case (:f op)
        :create
        (assoc op
               :type :ok
               :value (->> (:value op)
                           ast/setup
                           (mapv (partial statement! test t))))

        :txn
        (assoc op
             :type :ok
             :value (->> (:value op)
                         (mapv (partial statement! test t)))))))

  (teardown! [_ test conn])

  (close! [_ test]))

(def max-txn-length 16)

(defn partition-statements
  "Partitions a vector of statements into a vector of vectors of statements;
  each one intended for a single transaction."
  [test statements]
  (when (seq statements)
    (lazy-seq
      (let [; No sense in testing just a single statement
            n (+ 2 (rand/long
                     (- (:max-txn-length test max-txn-length)
                        2)))
            [txn more] (split-at n statements)]
        (cons txn (partition-statements test more))))))

(defn new-case-delay
  "Returns a Delay which will generate a new Case for case number i."
  [i]
  (-> (sql.gen/generate {:duplicate-primary-keys? false
                         :max-statement-count 4096
                         :max-table-count 2
                         :max-column-count 4})
      (ast/unique-tables i)
      delay))

(defrecord Generator [; Case number
                      i
                      ; Vector of transactions remaining
                      txns
                      ; This is a Delay which generates the next case. We wrap
                      ; this so we don't call it repeatedly and throw away
                      ; work.
                      case-delay]
  gen/Generator
  (op [this test ctx]
    (if-let [txn (first txns)]
      ; Keep issuing transactions
      [(gen/fill-in-op {:f :txn, :value txn, :case i} ctx)
       (Generator. i (next txns) case-delay)]
      ; Out of txns; generate a fresh case.
      (let [i' (inc i)
            case @case-delay
            txns (partition-statements test (:statements case))]
        ; One txn to create the tables, then more for the actual statements.
        [(gen/fill-in-op {:f :create, :value (:schema case), :case i'} ctx)
         (Generator. i' txns (new-case-delay (inc i')))])))

  (update [this test ctx event]
    this))

(defn gen
  "Generates a schema, then a series of transactions on it."
  []
  (Generator. -1 nil (new-case-delay 0)))

(defn internal-anomaly
  "Does an operation have an internal anomaly? If so, returns a map explaining
  it."
  [op]
  (:internal-anomaly op))

(defrecord Checker []
  jepsen.checker/Checker
  (check [this test history opts]
    (h/ensure-pair-index history)
    (let [; First pass: extract the schema for each case and convert it to an
          ; initial state.
          schemas (->> (t/filter (h/has-f? :create))
                       (t/filter h/invoke?)
                       (t/map (juxt :case :value))
                       (t/into {})
                       (h/tesser history))
          initial-states (util/map-vals initial-state schemas)

          ; Second pass: validate each txn
          {:keys [n errors]}
          (->> (t/filter h/ok?)
               (t/filter (h/has-f? :txn))
               (t/fuse
                 {:n      (t/count)
                  :errors (->> (t/keep (partial check-op
                                                history initial-states))
                               (t/into []))})
               (h/tesser history))]
      {:valid? (not (seq errors))
       :errors errors
       :error-count (count errors)
       :txn-count n})))

(defn workload
  "A workload for an internal consistency test. Takes CLI opts."
  [opts]
  {:generator (gen)
   :client    (c/client (Client.) opts)
   :checker   (checker/compose (Checker.) :internal)})
