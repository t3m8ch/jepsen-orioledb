(ns jepsen.sql.workload.rw
  "Test for transactional read-write registers"
  (:refer-clojure :exclude [read])
  (:require [clojure.tools.logging :refer [info warn]]
            [clojure [pprint :refer [pprint]]
                     [string :as str]]
            [dom-top.core :refer [loopr with-retry]]
            [elle.core :as elle]
            [jepsen [checker :as checker]
                    [core :as jepsen]
                    [generator :as gen]
                    [random :as rand]
                    [util :as util]]
            [jepsen.checker.timeline :as timeline]
            [jepsen.tests.cycle.wr :as wr]
            [jepsen.sql [client :as c]
                        [checker :as sc :refer [assert-instance-or-nil
                                                assert-at-most-one]]
                        [encoding :as encoding]]
            [next.jdbc :as j]
            [next.jdbc.result-set :as rs]
            [next.jdbc.sql.builder :as sqlb]
            [slingshot.slingshot :refer [try+ throw+]]))

(defn table-name
  "Takes an integer and constructs a table name."
  [i]
  (str "txn" i))

(defn table-for
  "What table should we use for the given key?"
  [client k]
  (let [tables (:tables client)]
    (nth tables (mod k (count tables)))))

(defn write-on-conflict!
  "Sets k to v using INSERT ... ON CONFLICT"
  [test client conn k v]
  (let [{:keys [table key-encoding val-encoding]} (table-for client k)
        k' ((:encode key-encoding) k k)
        v' ((:encode val-encoding) k v)]
    (j/execute! conn
                [(str "INSERT INTO " table " AS t"
                      " (id, sk, val) VALUES (?, ?, ?)"
                      " ON CONFLICT (id) DO UPDATE SET"
                      " val = ? WHERE "
                      (case (rand/nth (:key-types test))
                        :primary   "t.id"
                        :secondary "t.sk")
                      " = ?")
                 k' k' v' v' k'])))

(defn write-update!
  "Sets k to v by using an UPDATE statement. Returns true if written, otherwise
  false, so you can fall back to an INSERT."
  [test client conn k v]
  (let [{:keys [table key-encoding val-encoding]} (table-for client k)]
    (-> conn
        (j/execute-one! [(str "UPDATE " table " SET val = ? WHERE "
                              (case (rand/nth (:key-types test))
                                :primary "id"
                                :secondary "sk")
                              " = ?")
                         ((:encode val-encoding) k v)
                         ((:encode key-encoding) k k)])
        :next.jdbc/update-count
        pos?)))

(defn write-insert!
  "Sets k to v using an INSERT statement. Returns true if written, otherwise
  false, so you can fall back to an UPDATE."
  [test client conn txn? k v]
  ; If the insert conflicts, it'd be nice if we didn't have to throw away the
  ; whole transaction. We'll use a savepoint to do that--but some DBs don't
  ; support savepoints, so we make it optional.
  (let [savepoint? (and txn? (:savepoints test))
        {:keys [table key-encoding val-encoding]} (table-for client k)]
    (c/try+
      (when savepoint?
        (j/execute! conn ["SAVEPOINT upsert"]))
      (j/execute! conn
                  [(str "INSERT INTO " table " (id, sk, val) VALUES (?, ?, ?)")
                   ((:encode key-encoding) k k)
                   ((:encode key-encoding) k k)
                   ((:encode val-encoding) k v)])
      (when savepoint?
        (j/execute! conn ["RELEASE SAVEPOINT upsert"]))
      true
      ; TODO: maybe we want to catch specifically duplicates? Standardize?
      (catch [:definite? true] e
        (cond ; Fine--we failed but there's no txn that'll break
              (not txn?)
              false

              ; We're in a txn, but have a savepoint; roll back.
              savepoint?
              (do (j/execute! conn ["ROLLBACK TO SAVEPOINT upsert"])
                  false)

              ; No savepoint; gotta explode.
              true
              (throw+ e))))))

(defn write!
  "Sets k to v, returning v."
  [test client conn txn? k v]
  (case (rand/nth (remove #{:copy-on-write} (:upsert-types test)))
    ; Try an update, and if that fails, fall back to an insert, and if THAT
    ; fails we probably conflicted, so try an update again.
    :update-insert-update
    (or (write-update! test client conn k v)
        (write-insert! test client conn txn? k v)
        (write-update! test client conn k v)
        (throw+ {:type ::update-insert-update-failed
                 :key k
                 :value v}))

    :on-conflict
    (write-on-conflict! test client conn k v))
  v)

(defn read
  "Reads the value of key k."
  [test client conn k]
  (let [{:keys [table key-encoding val-encoding]} (table-for client k)
        r (-> (j/execute! conn
                          [(str "SELECT (val) FROM " table " WHERE "
                            (case (rand/nth (:key-types test))
                              :primary   "id"
                              :secondary "sk")
                            " = ?")
                       ((:encode key-encoding) k k)]
                      {:builder-fn rs/as-unqualified-lower-maps})
              assert-at-most-one
              first
              :val)]
    ((:decode val-encoding) k r)))

(defn mop!
  "Executes a transactional micro-op on a connection. Returns the completed
  micro-op."
  [test client conn txn? [f k v]]
  (Thread/sleep (rand/zipf (:mop-delay test)))
  [f k (case f
         :r (read test client conn k)
         :w (write! test client conn txn? k v))])

(defn tables
  "Constructs a vector of tables we'll use to store various keys. Each is a map
  of:

  {:table           The table name
   :key-encoding    The key encoding
   :val-encoding    The value encoding}"
  [test]
  (let [encodings (encoding/encodings test)]
    (loopr [i      0
            tables []]
           [ki (rand/shuffle encodings)
            vi (rand/shuffle encodings)]
           (if (= i (:table-count test
                                  (* (count encodings)
                                     (count encodings))))
             tables
             (recur (inc i)
                    (conj tables {:table         (table-name i)
                                  :key-encoding  ki
                                  :val-encoding  vi})))
           tables)))

(defrecord Client [tables node]
  c/Client
  (open! [this test conn node]
    (assoc this :node node))

  (setup! [_ test conn]
    ; Secondaries may not be writable; always do writes on the primary node.
    (when (= node (jepsen/primary test))
      (doseq [{:keys [table key-encoding val-encoding]} tables]
        (j/execute! conn
                    [(str "create table if not exists " table
                          " (id " (:type key-encoding)
                          " not null primary key,
                          sk " (:type key-encoding) " not null,
                          val " (:type val-encoding) ")")]))))

  (invoke! [this test conn op]
    (let [txn       (:value op)
          use-txn?  (rand/nth [true (< 1 (count txn))])
          txn'      (if use-txn?
                      (c/with-txn test [t conn]
                        (mapv (partial mop! test this t true) txn))
                      (mapv (partial mop! test this conn false) txn))]
      (assoc op :type :ok, :value txn')))

  (teardown! [_ test conn]
    (doseq [table (map :table tables)]
      (j/execute! conn [(str "drop table if exists " table)])))

  (close! [this test]))

(defn process->node
  "Converts a process back to a node ID."
  [test process]
  (nth (:nodes test) (mod process (count (:nodes test)))))

(defn read-only
  "Converts writes to reads."
  [op]
  (loopr [txn' []]
         [[f k v :as mop] (:value op)]
         (recur (conj txn' (case f
                             :r mop
                             [:r k nil])))
         (assoc op :f :read, :value txn')))

(defrecord ROGen [gen ro-nodes]
  gen/Generator
  (update [this test ctx event]
    (let [gen' (gen/update gen test ctx event)]
      (if (= [:read-only] (:error event))
        ; Flag this node as read-only
        (let [node (process->node test (:process event))]
          (ROGen. gen' (conj ro-nodes node)))
        (ROGen. gen' ro-nodes))))

  (op [this test ctx]
    (when-let [[op gen'] (gen/op gen test ctx)]
      (if (= :pending op)
        [:pending this]
        (let [node (process->node test (:process op))]
          (if (contains? ro-nodes node)
            (let [op (read-only op)
                  ; Small chance of this node going back to normal
                  ro-nodes' (if (rand/bool 0.001)
                              (disj ro-nodes node)
                              ro-nodes)]
              [op (ROGen. gen' ro-nodes')])
            ; Pass through
            [op (ROGen. gen' ro-nodes)]))))))

(defn ro-gen
  "Generator that detects read-only errors and flips to emitting read-only
  transactions on that node. Nodes fall out of the read-only pool randomly over
  time."
  [gen]
  (ROGen. gen #{}))

(defn workload
  "A read-write register workload. Options:

      :encodings: A collection of encoding keywords. See jepsen.sql.encoding.
      :isolation          The isolation level for our transactions, e.g.
                          :serializable or :read-uncommitted.
      :key-count          For Elle, the number of active keys at any given time
      :key-dist           For Elle, a distribution like :exponential
      :max-txn-length     For Elle, the maximum number of ops in a transaction
      :max-writes-per-key For Elle, the maximum number of writes we attempt
                          per key
      :key-types          A vector of keywords for strategies we use to look up
                          a row. May include :primary, :secondary.
      :upsert-types       A vector of keywords for strategies we use for
                          upserts. Can include :insert-update-update,
                          :on-conflict, or :copy-on-write"
  [opts]
  (-> (wr/test (assoc (select-keys opts [:key-count
                                         :key-dist
                                         :max-txn-length
                                         :max-writes-per-key
                                         :wfr-keys?
                                         :linearizable-keys?
                                         :sequential-keys?])
                      :min-txn-length 1
                      :consistency-models [(:expected-consistency-model opts)]))
      (assoc :client (c/client (Client. (tables opts) nil) opts))
      (update :checker (fn [rw-checker]
                         (checker/compose
                           {:rw rw-checker
                            :critical (sc/critical-checker)
                            :missing-table-column
                            (sc/missing-table-column-checker)})))
      (update :generator ro-gen)))
