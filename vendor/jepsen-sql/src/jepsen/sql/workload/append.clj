(ns jepsen.sql.workload.append
  "Test for transactional list append."
  (:refer-clojure :exclude [read])
  (:require [clojure.tools.logging :refer [info warn]]
            [clojure [pprint :refer [pprint]]
                     [string :as str]]
            [dom-top.core :refer [loopr with-retry]]
            [elle.core :as elle]
            [jepsen [checker :as checker]
                    [core :as jepsen]
                    [generator :as gen]
                    [util :as util]
                    [random :as rand]]
            [jepsen.sql [base :as base]
                        [client :as c]
                        [checker :as sc
                         :refer [assert-at-most-one
                                 assert-instance-or-nil]]]
            [jepsen.tests.cycle.append :as append]
            [next.jdbc :as j]
            [next.jdbc.result-set :as rs]
            [next.jdbc.sql.builder :as sqlb]
            [slingshot.slingshot :refer [try+ throw+]]))

(def default-table-count 6)

(defn table-name
  "Takes an integer and constructs a table name."
  [i]
  (str "txn" i))

(defn indirection-table-name
  "Takes an integer and constructs an indirection table name."
  [i]
  (str "txn_indir" i))

(defn tables-for
  "Returns a map of {:table ... :indirection ...} for the given key."
  [client k]
  (let [tables (:tables client)]
    (-> tables (nth (mod (hash k) (count tables))))))

(defn table-for
  "What table should we use for the given key? Takes a Client record."
  [client k]
  (:table (tables-for client k)))

(defn indirection-for
  "What indirection should we use for the given key? Takes a Client
  record."
  [client k]
  (:indirection (tables-for client k)))

(defn id!
  "Generates a unique ID given a Client."
  [client]
  (swap! (:next-id client) inc))

(defn read-direct
  "Reads the value of a single key without indirection. Returns a single
  row."
  [test conn table k]
  (-> conn
      (j/execute!
        [(str "SELECT (val) FROM " table " WHERE "
              (case (rand/nth (:key-types test))
                :primary "id"
                :secondary "sk")
              " = ?")
         k]
        {:builder-fn rs/as-unqualified-lower-maps})
      assert-at-most-one
      first))

(defn read
  "Reads the value of a single key."
  [test client conn k]
  (let [{:keys [table indirection]} (tables-for client k)
        row (if indirection
              (base/read-indirection test conn indirection k)
              (read-direct test conn table k))]
    (when-let [v (:val row)]
      ; This sounds deeply silly, but trust me, we want to check
      (assert-instance-or-nil String v)
      (mapv parse-long (str/split v #",")))))


(defn append-on-conflict!
  "Appends an element to a key using an INSERT ... ON CONFLICT statement."
  [test conn table k e]
  ; Upsert row
  (j/execute!
    conn
    [(str "insert into " table " as t"
          " (id, sk, val) values (?, ?, ?)"
          " on conflict (id) do update set"
          " val = CONCAT(t.val, ',', ?) where "
          (case (rand/nth (:key-types test))
            :primary   "t.id"
            :secondary "t.sk")
          " = ?")
     k k e e k]))

(defn insert!
  "Inserts a row into the given data table with the given comma-separated
  string value."
  [conn table k value-string]
  (j/execute! conn
              [(str "INSERT INTO " table " (id, sk, val)"
                    " VALUES (?, ?, ?)")
               k k value-string]))

(defn append-insert!
  "Performs an initial insert of a key with initial element e. Catches
  duplicate key exceptions, returning true if succeeded. If the insert fails
  due to a duplicate key, it'll break the rest of the transaction, assuming
  we're in a transaction, so we establish a savepoint before inserting and roll
  back to it on failure."
  [test conn table txn? k e]
  (let [savepoint? (and txn? (:savepoints test))]
    (c/try+
      (when savepoint? (j/execute! conn ["SAVEPOINT UPSERT"]))
      (insert! conn table k (str e))
      (when savepoint? (j/execute! conn ["RELEASE SAVEPOINT upsert"]))
      true
      (catch [:definite? true] e
        (cond ; Fine: we failed but there's no txn that'll break
              (not txn?)
              nil

              ; We're in a txn, but have a savepoint; roll back
              savepoint?
              (do
                (j/execute! conn ["ROLLBACK TO SAVEPOINT upsert"])
                ; NB j/execute! will falsely signal append-insert! succeeded
                ; if we propagate its truthy return value
                false)

              ; No savepoint; gotta explode.
              :else
              (throw+ e))))))

(defn append-update!
  "Performs an update of a key k, adding element e. Returns true if the update
  succeeded, false otherwise."
  [test conn table k e]
  (let [res (-> conn
                (j/execute-one!
                  [(str "update " table " set val = CONCAT(val, ',', ?)"
                        " where "
                        (case (rand/nth (:key-types test))
                          :primary   "id"
                          :secondary "sk")
                        " = ?") e k]))]
    (-> res
        :next.jdbc/update-count
        pos?)))

(defn append-update-insert-update!
  "Appends via an update, falling back to an insert, falling back to an update."
  [test conn table txn? k e]
  (or (append-update! test conn table k e)
      (append-insert! test conn table txn? k e)
      (append-update! test conn table k e)
      (throw+ {:type ::update-insert-update-failed
               :key   k
               :element e})))

(defn append-without-indirection!
  "Appends by upserting directly to a data table, without doing anything with
  an indirection table."
  [test conn table txn? k e]
  (case (rand/nth (remove #{:copy-on-write} (:upsert-types test)))
    ; Try an update, and if that fails, fall back to an insert, and if THAT
    ; fails, we probably conflicted, so update again.
    :update-insert-update
    (append-update-insert-update! test conn table txn? k e)

    :on-conflict
    (append-on-conflict! test conn table k e)))

(defn append-cow!
  "Appends by creating a new copy of the row and updating the indirection table
  to match. Takes the test, client, connection, indirection, the logical key in
  the indirection table, the data table, and then the current id in the data
  table, and the element being appended."
  [test client conn indirection k table id e]
  (let [v (if-let [v (:val (read-direct test conn table id))]
            (do (assert-instance-or-nil String v)
                (str v "," e))
            (str e))
        id (id! client)]
    ; Write copy
    (insert! conn table id v)
    ; Update reference
    (base/write-indirection! test conn indirection k id)))

(defn append!
  "Appends element e to key k, returning e."
  [test client conn txn? k e]
  (let [e (str e)
        {:keys [table indirection]} (tables-for client k)]
    (if indirection
      ; If we're doing an indirection, we look up the indirection first
      (let [id (base/read-indirection-target-id test conn indirection k)
            ; Ah, we need to do a fresh indirection. Generate an ID.
            new-indirection? (nil? id)
            id (or id (id! client))]
        (case (rand/nth (:upsert-types test))
          :copy-on-write
          (append-cow! test client conn indirection k table id e)

          ; Otherwise...
          (do ; Append in place
              (append-without-indirection! test conn table txn? id e)
              ; And create an indirection entry linking this key to this id
              (when new-indirection?
                (base/write-indirection! test conn indirection k id)))))

      ; When we're not doing indirection, we can just write directly
      (append-without-indirection! test conn table txn? k e)))
  e)

(defn mop!
  "Executes a transactional micro-op on a connection. Returns the completed
  micro-op."
  [test client conn txn? [f k v]]
  (base/mop-sleep test)
  [f k (case f
         :r      (read    test client conn k)
         :append (append! test client conn txn? k v))])

(defrecord Client
  [node          ; The node we're connected to
  ; A vector of maps like
  ;  {:table       "..."
  ;   :indirection {...}
  ; Each key belongs in one table and indirection table, found in this vector.
  tables
  ; An atom we use to pick unique IDs for rows.
  next-id
  ]
  c/Client
  (open! [this test conn node]
    ; Fine to do this here; it's deterministic.
    (let [n (:table-count test default-table-count)]
      (assoc this
             :node node
             :tables
             (mapv (fn tables [i]
                     {:table (table-name i)
                      :indirection
                      ; Half of our tables get an indirection; we want to blend
                      ; the two tactics.
                      (when (and (:indirection? test)
                                 (< i (/ n 2)))
                        (base/indirection
                          test
                          (indirection-table-name i)
                          (table-name i)
                          "id"))})
                   (range n)))))

  (setup! [_ test conn]
    ; Secondaries may not be writable; always do writes on the primary node.
    (when (= node (jepsen/primary test))
      (doseq [{:keys [table indirection]} tables]
        (j/execute! conn
                    [(str "create table if not exists " table
                          " (id int not null primary key,
                          sk int not null,
                          val text)")])
        ; And corresponding indirection table
        (when indirection
          (base/create-indirection-table! test conn indirection)))))

  (invoke! [this test conn op]
    (let [txn       (:value op)
          use-txn?  (or (< 1 (count txn))
                        ; If we're indirecting through another table, there are
                        ; technically a couple cases where we can get away
                        ; without a txn, but it's complicated.
                        (:indirection? test))
          txn'      (if use-txn?
                      (c/with-txn test [t conn]
                        (mapv (partial mop! test this t true) txn))
                      (mapv (partial mop! test this conn false) txn))]
      (assoc op :type :ok, :value txn')))

  (teardown! [_ test conn]
    (dotimes [i (:table-count test default-table-count)]
      (j/execute! conn [(str "drop table if exists " (table-name i))])))

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
                  ro-nodes' (if (< (rand) 0.001)
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
  "A list append workload. Options:

      :indirection?       If set, lets us look up rows through a separate
                          indirection table
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
                          :on-conflict, or :copy-on-write.
  "
  [opts]
  (-> (append/test (assoc (select-keys opts [:key-count
                                             :key-dist
                                             :max-txn-length
                                             :max-writes-per-key])
                          :min-txn-length 1
                          :consistency-models [(:expected-consistency-model opts)]))
      (assoc :client (c/client
                       (map->Client {:next-id (atom 0)})
                       opts))
      (update :checker (fn [append-checker]
                         (checker/compose
                           {:append    append-checker
                            :critical  (sc/critical-checker)
                            :missing-table-column
                            (sc/missing-table-column-checker)})))
      (update :generator ro-gen)))
