(ns jepsen.sql.workload.default-value
  "This test is designed to stress a database's DDL. We simulate a migration in
  which a user adds a column with a default value of `0` to an existing table,
  and execute concurrent inserts and reads of that table. We look for reads
  where a column has a `null` value, rather than the default.

  To create a table, we write:

      {:f :create-table, :value \"t1\"}

  To add a column to a table:

      {:f :add-column, :value {:table \"t1\", :column \"c1\", :default 3}}

  To insert a row into a table (without providing values for default columns):

      {:f :insert, :value {:table \"t1\", :id 1}}

  And to read all rows from a table:

      {:type :invoke, :f :read, :value \"t1\"}
      {:type :ok, :f :read, :value [{:a 2, :c3 3, :c4 nil} ...]}

  We flag any reads which have a nil value--since all columns should have a
  default, or be explicitly written, this should never happen."
  (:require [jepsen [checker :as checker]
                    [generator :as gen]
                    [history :as h]
                    [random :as rand]]
            [jepsen.checker.plot :as plot]
            [jepsen.sql [base :as base]
                        [client :as c]
                        [checker :as sc]]
            [next.jdbc :as j]
            [next.jdbc.result-set :as rs]))

(defn table-gen
  "Generator of operations on a single table."
  [table]
  (let [inserts (map (fn [i]
                       {:f :insert, :value {:table table, :id i}})
                     (range))
        reads   (gen/repeat {:f :read, :value table})]
    (gen/phases
      (gen/until-ok
        {:f :create-table, :value table})
      ; This gets quadratic fast, so we don't want to do too much
      (gen/limit (rand/zipf 10000)
                 (gen/mix
                   (into [(map (fn [i]
                                 (let [column (str "c" i)]
                                   {:f :add-column
                                    :value {:table table
                                            :column column,
                                            :default i}}))
                               (range))]
                         (take (* 2 (inc (rand/zipf 15)))
                               (cycle [inserts reads]))))))))

(defn generator
  "Generator of operations across many tables."
  []
  (->> (range)
       (map (fn [i]
              (table-gen (str "t" i))))))


(defrecord Client []
  c/Client
  (open! [this test conn node]
    this)

  (setup! [this test conn])

  (invoke! [this test conn {:keys [f value] :as op}]
    (case f
      :create-table
      ; Tables always start off with an `a` column
      (do (j/execute! conn [(str "CREATE TABLE IF NOT EXISTS " value
                                    " (a INT)")])
          (assoc op :type :ok))

      :add-column
      (let [{:keys [table column default]} value]
        (j/execute! conn [(str "ALTER TABLE IF EXISTS " table
                                  " ADD COLUMN " column
                                  " INT DEFAULT " default)])
        (assoc op :type :ok))

      :insert
      (let [{:keys [table id]} value]
        (j/execute! conn [(str "INSERT INTO " table " (a) VALUES (?)") id])
        (assoc op :type :ok))

      :read
      (let [rows (j/execute! conn [(str "SELECT * FROM " value)]
                             {:builder-fn rs/as-unqualified-lower-maps})]

        (assoc op :type :ok, :value rows))))

  (teardown! [this test conn])

  (close! [this test]))

(defn bad-row
  "Is this particular row illegal--e.g. does it contain a `null`? Returns row
  if true."
  [row]
  (when (seq (filter nil? (vals row)))
    row))

(defn bad-read
  "Does this read op have a bad row in it? Returns that row if so, otherwise
  nil."
  [op]
  (->> op :value (filter bad-row) seq))

(defrecord Checker []
  checker/Checker
  (check [_ test history opts]
    (let [reads (->> history
                     h/oks
                     (h/filter (h/has-f? :read)))
          bad-reads (->> reads
                         (h/filter bad-read)
                         (mapv (fn [op]
                                 {:op op
                                  :bad-rows (vec (keep bad-row (:value op)))})))]
      {:valid?          (empty? bad-reads)
       :read-count      (count reads)
       :bad-read-count  (count bad-reads)
       :bad-reads       (take 16 bad-reads)})))

(defn workload
  [opts]
  {:generator (generator)
   :client    (c/client (Client.) opts)
   :checker   (checker/compose
                {:default-value (Checker.)
                 :critical (sc/critical-checker)
                 :missing-table-column (sc/missing-table-column-checker)})})
