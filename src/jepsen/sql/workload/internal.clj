(ns jepsen.sql.workload.internal
  "A simple test for internal consistency. Transactions perform a mixture of
  inserts, updates, and reads of individual keys, randomly changing but mostly
  focused on recent keys. We then look for cases where a transaction failed to
  observe its most recent reads or writes."
  (:refer-clojure :exclude [read])
  (:require [clojure.tools.logging :refer [info warn]]
            [clojure [pprint :refer [pprint]]
                     [string :as str]]
            [dom-top.core :refer [loopr with-retry]]
            [elle.core :as elle]
            [jepsen [checker :as checker]
                    [client :as client]
                    [core :as jepsen]
                    [generator :as gen]
                    [history :as h]
                    [random :as rand]
                    [util :as util]]
            [jepsen.sql [client :as c]
             [checker :as sc :refer [assert-at-most-one
                                     assert-instance-or-nil]]
                        [encoding :as encoding]]
            [next.jdbc :as j]
            [next.jdbc.result-set :as rs]
            [next.jdbc.sql.builder :as sqlb]
            [clj-commons.slingshot :refer [try+ throw+]]
            [tesser.core :as t]))

(def encoding
  "Just integers for now"
  (encoding/encoding :integer))

(defn mop!
  "Applies a single transaction micro-operation. Returns micro-op, or nil if
  the operation did not take effect."
  [test conn [f k v]]
  (Thread/sleep (rand/zipf (:mop-delay test)))
  (let [{:keys [encode decode]} encoding]
    (case f
      :insert (c/try+
                (j/execute! conn [(str "INSERT INTO internal (id, val) "
                                       "VALUES (?, ?)")
                                  (encode k k)
                                  (encode k v)])
                [f k v]
                (catch [:definite? true] e nil))

      :update (if (-> conn
                      (j/execute-one!
                        [(str "UPDATE internal SET val = ? WHERE id = ?")
                         (encode k v)
                         (encode k k)])
                      :next.jdbc/update-count
                      pos?)
                [f k v])

      :r (let [x (-> conn
                     (j/execute! [(str "SELECT (val) FROM internal "
                                       "WHERE id = ?")
                                  (encode k k)]
                                 {:builder-fn rs/as-unqualified-lower-maps})
                     assert-at-most-one
                     first
                     :val)]
           [f k (decode k x)]))))

(defrecord Client []
  c/Client
  (open! [this test conn node]
    this)

  (setup! [_ test conn]
    (j/execute! conn
                [(str "CREATE TABLE IF NOT EXISTS internal ("
                      "  id " (:type encoding) " not null primary key,"
                      "  val " (:type encoding) ")")]))

  (invoke! [_ test conn op]
    (c/with-txn test [t conn]
      (assoc op
             :type :ok
             :value (->> (:value op)
                         (mapv (partial mop! test t))
                         (remove nil?)
                         vec))))

  (teardown! [_ test conn])

  (close! [_ test]))

(defrecord Generator [next-id]
  gen/Generator
  (op [this test ctx]
    (loopr [next-id next-id
            txn     []]
           [i (range (inc (rand/zipf (/ (:max-txn-length test) 2))))]
           (let [; A recent key. Moooostly these use the next id, but sometimes
                 ; it'll be three or five keys ago.
                 k (- next-id (rand/zipf (min next-id 5)))]
             (rand/weighted-branch
               ; Insert
               1 (recur (inc next-id)
                        (conj txn [:insert k (rand/long 100)]))

               ; Update
               4 (recur next-id (conj txn [:update k (rand/long 100)]))

               ; Read
               2 (recur next-id (conj txn [:r k nil]))))
           ; After we build the initial transaction, go through and read
           ; everything just to be sure.
           (let [txn (->> txn
                          (mapv second)
                          ; What the heck, maybe multiple reads will be fun
                          rand/shuffle
                          (mapv (fn [k] [:r k nil]))
                          (into txn))]
             ; Done building txn
             [(gen/fill-in-op {:f :txn, :value txn} ctx)
              (Generator. next-id)])))

  (update [this test ctx event]
    this))

(defn gen
  "Generates a stream of transactions which try to observe internal consistency
  anomalies."
  []
  (Generator. 0))

(defn internal-anomaly
  "Does an operation have an internal anomaly? If so, returns a map explaining
  it."
  [op]
  (when (h/ok? op)
    (loopr [state (transient {})]
           [[f k v :as mop] (:value op)]
           (case f
             :insert
             (recur (assoc! state k v))

             :update
             (recur (assoc! state k v))

             :r
             (if (contains? state k)
               (if (= v (state k))
                 (recur state)
                 ; Oops!
                 {:op op
                  :mop mop
                  :k k
                  :expected (get state k)
                  :actual v})
               ; New information
               (recur (assoc! state k v))))
           nil)))

(defrecord Checker []
  checker/Checker
  (check [this test history opts]
    (let [{:keys [n errors]}
          (->> (t/filter h/ok?)
               (t/filter (h/has-f? :txn))
               (t/fuse
                 {:n      (t/count)
                  :errors (->> (t/keep internal-anomaly)
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
   :client    (c/client (Client. nil nil) opts)
   :checker   (checker/compose
                {:internal (Checker.)
                 :critical (sc/critical-checker)
                 :missing-table-column
                 (sc/missing-table-column-checker)})})
