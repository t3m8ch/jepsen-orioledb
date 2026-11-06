(ns jepsen.sql.checker
  "There are a few common things that can go wrong in an SQL test. We encode
  these as :type :info operations with a special :error like

    {:type      :jepsen.sql/wrong-type
     :expected 'java.lang.Integer
     :actual   'java.lang.String}

  We offer a checker which looks for these errors. This checker is
  automatically composed with each workload checker."
  (:require [jepsen [checker :as checker]
                    [history :as h]
                    [util :refer [map-vals]]]
            [tesser [core :as t]]
            [clj-commons.slingshot :refer [try+ throw+]]))

(defrecord CriticalChecker []
  checker/Checker
  (check [this test history opts]
    (let [errs (->> (t/filter (fn filter [op]
                                (and (h/info? op)
                                     (:critical? (:error op)))))
                    (t/group-by (comp :type :error))
                    (t/into [])
                    (h/tesser history))]
      {:valid?      (not (seq errs))
       :error-types (sort (keys errs))
       :errors      errs})))

(defn critical-checker
  "A checker that looks for ops that have a `:critical? true` `:error` map."
  []
  (CriticalChecker.))

(defn compose
  "Takes an existing workload checker and composes it with our SQL checker,
  naming the original checker the given key."
  [checker k]
  (checker/compose
    {k     checker
     :sql  (CriticalChecker.)}))

;; Some common assertions that will trigger our checker

(defn assert-at-most-one
  "Asserts that there's at most one thing in a collection. Returns coll. Takes
  a map of additional keys to merge into thrown errors."
  ([coll]
   (assert-at-most-one coll nil))
  ([coll m]
   (when (< 1 (count coll))
     (throw+ (merge {:type      :jepsen.sql/too-many
                     :critical? true
                     :coll      coll}
                    m)))
   coll))

(defn assert-instance-or-nil
  "Asserts that x is of the given type, or nil. Returns x. Takes a map of
  additional keys to merge into thrown errors."
  ([type x]
   (assert-instance-or-nil type x nil))
  ([type x m]
   (when-not (or (nil? x) (instance? type x))
     (throw+ (merge {:type      :jepsen.sql/wrong-type
                     :critical? true
                     :expected  (symbol (.getName type))
                     :actual    (symbol (.getName (class x)))
                     :value     x})))
   x))

(defrecord MissingTableColumnChecker []
  checker/Checker
  (check [this test history opts]
    (let [process->node (fn [process]
                          (mod process (count (:nodes test))))
          ; First pass: look for the first successful transaction on each node.
          ; This should be very quick.
          cutoff (reduce (fn [remaining-nodes op]
                           (if (seq remaining-nodes)
                             ; Waiting for a node to complete a transaction.
                             (if (h/ok? op)
                               (disj remaining-nodes
                                     (process->node (:process op)))
                               remaining-nodes)
                             ; Done
                             (reduced (:index op))))
                         (set (range (count (:nodes test))))
                         history)]
      (if (set? cutoff)
        {:valid? false
         :error  "Some nodes never executed an OK operation"
         :nodes  (map (:nodes test) (sort cutoff))}
        ; Second pass: look for column or table not found errors *after*
        ; those OK ops.
        (let [errors
              (->> (t/filter (fn [op]
                               (and (<= cutoff (:index op))
                                    (#{:column-not-found
                                       :table-not-found}
                                      (:type (:error op))))))
                   (t/group-by (comp :type :error))
                   (t/into [])
                   (h/tesser history))]
          {:valid?      (empty? errors)
           :error-types (keys errors)
           :errors      (map-vals (fn [errors]
                                    {:count (count errors)
                                     :example (first errors)})
                                  errors)})))))

(defn missing-table-column-checker
  "This checker looks for 'table not found' or 'column not found' errors; it
  would be bad if these happened after the initial setup in some workloads."
  []
  (MissingTableColumnChecker.))
