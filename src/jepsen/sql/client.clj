(ns jepsen.sql.client
  "Functions for working with next.jdbc clients, performing transactions,
  logging, error handling, etc."
  (:require [clojure [pprint :refer [pprint]]]
            [clojure.tools.logging :refer [info warn]]
            [clj-commons.slingshot :as s :refer [throw+]]
            [dom-top.core :refer [loopr]]
            [jepsen [client :as client]]
            [next.jdbc :as j]
            [next.jdbc.result-set :as rs]
            [next.jdbc.sql.builder :as sqlb])
  (:import (java.sql Connection
                     SQLException)))

;; Working with connections

(defn close-conn!
  "Closes a connection or a next.jdbc wrapper containing a connection."
  [conn]
  (j/on-connection [^Connection conn conn]
    (.close conn)))

(defn with-logging
  "Wraps a connection-esque thing with an SQL logger, if (:log-sql test) is
  true."
  [test conn]
  (if (:log-sql test)
    (j/with-logging conn
      (fn req-logger [op sql] sql)
      (fn res-logger [op sql res]
        (info op (pr-str sql) '->
              (if (instance? Throwable res)
                (.getMessage ^Throwable res)
                (pr-str res)))))
    conn))

(defn set-transaction-isolation!
  "Sets the transaction isolation level on a connection. Returns conn."
  [conn level]
  (j/on-connection [^Connection conn conn]
                   (.setTransactionIsolation
                     conn
                     (case level
                       :serializable     Connection/TRANSACTION_SERIALIZABLE
                       :repeatable-read  Connection/TRANSACTION_REPEATABLE_READ
                       :read-committed   Connection/TRANSACTION_READ_COMMITTED
                       :read-uncommitted Connection/TRANSACTION_READ_UNCOMMITTED))
  conn))

(defmacro with-txn
  "Like next.jdbc/with-transaction, but takes a test map first. Re-wraps the
  connection in logging, if applicable. Opts are passed through to
  jdbc/with-transaction. By default, sets the isolation level from the test."
  [test [lhs rhs & opts] & body]
  (assert (<= (count opts) 1))
  `(let [opts# (merge {:isolation (:isolation ~test)}
                      ~(first opts))]
     (j/with-transaction [~lhs ~rhs opts#]
       (let [~lhs (with-logging ~test ~lhs)]
         ~@body))))

(defn isolation-level-string
  "A map of isolation levels (as keywords) to their SQL names."
  [level]
  (case level
    :serializable     "SERIALIZABLE"
    :repeatable-read  "REPEATABLE READ"
    :read-committed   "READ COMMITTED"
    :read-uncommitted "READ UNCOMMITTED"))

(defmacro with-manual-txn
  "Same as with-txn, but uses explicit BEGIN/COMMIT statements and SET
  TRANSACTION ISOLATION LEVEL. I've been using this to see if there's something
  broken with next.jdbc's transaction handling. Opts are ignored."
  [test [lhs rhs & opts] & body]
  `(let [~lhs ~rhs]
     (try
       (j/execute! ~lhs ["BEGIN"])
       (j/execute! ~lhs [(str "SET TRANSACTION ISOLATION LEVEL "
                              (isolation-level-string (:isolation ~test)))])
       (let [res# ~@body]
         (j/execute! ~lhs ["COMMIT"])
         res#)
       (catch Exception e#
         (j/execute! ~lhs ["ROLLBACK"])
         (throw e#)))))


;; Error handling.

(defn ^:dynamic *error-fn*
  "Turns some types of Exception into a throwable map. Callers are supposed to
  rebind this to handle the types of errors the current client and database
  throw; we make it a dynamic var so that you don't have to pass this function
  down through every single layer of your program. The jepsen.sql workloads
  will bind this variable for you when you pass them `:error-fn` options. See
  the README for details."
  [e]
  (let [m (.getMessage e)]
    (condp identical? (class e)
      SQLException
      (condp re-find m
        #"duplicate key value" {:type :duplicate
                                :definite? true}))))

(defmacro with-errors
  "Evaluates body, converting some errors into thrown Slingshot maps using
  `*error-fn*`."
  [& body]
  `(try ~@body
        (catch Exception e#
          (if-let [known# (*error-fn* e#)]
            (throw+ known#)
            (throw e#)))))

(defmacro try+
  "Like Slingshot try+, but rewrites exceptions using *error-fn*. This
  way, you can write:

      (try+ (j/execute [...])
        (catch [:definite? true] ...))"
  [& forms]
  (let [; Split up into body expressions and catch/finally clauses
        parts (group-by (fn [form]
                          (boolean (and (seq? form)
                                        (#{'catch 'finally} (first form)))))
                        forms)
        body (parts false)
        clauses (parts true)]
    (assert (seq body) "No body for try+")
    (assert (seq clauses) "No catch/finally clauses for try+")
    `(s/try+ (with-errors ~@body)
             ~@clauses)))

;; Clients

(defprotocol Client
  "Like jepsen's Client, but handles connection management. Connections are
  passed as an argument to setup!, invoke!, etc."
  (open! [_ test conn node] "Prepares a client for use on a specific node.")
  (setup! [_ test conn] "Sets up the workload using connection.")
  (invoke! [_ test conn op] "Applies an operation using connection.")
  (teardown! [_ test conn] "Tears down the workload using connection.")
  (close! [_ test]))

(defrecord ClientWrapper [client open error-fn conn]
  client/Client
  (open! [this test node]
    (binding [*error-fn* error-fn]
      (let [conn (->> (open test node)
                      (with-logging test))]
        (j/execute-one! conn
                        [(str "SET SESSION CHARACTERISTICS "
                              "AS TRANSACTION ISOLATION LEVEL "
                              (isolation-level-string (:isolation test)))])
        (assoc this
               :conn   conn
               :client (open! client test conn node)))))

  (setup! [_ test]
    (binding [*error-fn* error-fn]
      (setup! client test conn)))

  (invoke! [_ test op]
    (try
      (binding [*error-fn* error-fn]
        (with-errors
          (invoke! client test conn op)))
      (catch clojure.lang.ExceptionInfo e
        (let [data (ex-data e)]
          (if (:type data)
            (assoc op
                   :type (if (:definite? (ex-data e))
                           :fail
                           :info)
                   :error (dissoc data :definite?))
            (throw e))))))

  (teardown! [_ test]
    (binding [*error-fn* error-fn]
      (teardown! client test conn)))

  (close! [_ test]
    (binding [*error-fn* error-fn]
      (close-conn! conn)
      (close! client test))))

(defn client
  "A Jepsen Client which automatically handles connection management and errors
  thrown by `invoke!`. You must provide a client which satisfies the Client
  protocol in this namespace.

    :jepsen.sql/open        A function `(open test node)` which opens a conn
    :jepsen.sql/error-fn    An error-remapping function"
  [client opts]
  (map->ClientWrapper {:client client
                       :open     (:jepsen.sql/open opts)
                       :error-fn (:jepsen.sql/error-fn opts)}))
