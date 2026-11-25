(ns jepsen.orioledb.nemesis
  (:require [jepsen.control :as c]
            [jepsen.nemesis :as nem]
            [jepsen.nemesis.combined :as nc]
            [jepsen.orioledb.db :as db]
            [jepsen.generator :as gen]
            [jepsen.postgres.client :as pg]
            [next.jdbc :as jdbc]))

(defn hold-snapshot! [test]
  (let [conn (pg/open test (:data-host test))]
    (try
      (.setTransactionIsolation
       conn java.sql.Connection/TRANSACTION_REPEATABLE_READ)
      (.setReadOnly conn true)
      (.setAutoCommit conn false)
      (jdbc/execute-one! conn ["SELECT 1 FROM txn0 LIMIT 1"])
      conn
      (catch Exception e
        (.close conn)
        (throw e)))))

(defn release-snapshot! [conn-atom]
  (when-let [conn @conn-atom]
    (try
      (.rollback conn)
      (finally
        (try
          (.close conn)
          (finally
            (reset! conn-atom nil)))))))

(defrecord HoldSnapshotNemesis [conn]
  nem/Reflection
  (fs [_] #{:hold-snapshot :release-snapshot})

  nem/Nemesis
  (setup! [this _]
    (assoc this :conn (atom nil)))

  (invoke! [_ test op]
    (case (:f op)
      :hold-snapshot
      (do
        (when (nil? @conn)
          (reset! conn (hold-snapshot! test)))
        (assoc op :value :held))

      :release-snapshot
      (do
        (release-snapshot! conn)
        (assoc op :value :released))))

  (teardown! [_ _]
    (release-snapshot! conn)))

(defn snapshot-package []
  {:nemesis (->HoldSnapshotNemesis nil)
   :generator (gen/once {:type :info :f :hold-snapshot})
   :final-generator (gen/once {:type :info :f :release-snapshot})})

(defn wrap-cache-loss-kill [original-nemesis database]
  (reify
    nem/Reflection
    (fs [_] (nem/fs original-nemesis))

    nem/Nemesis
    (setup! [_ test]
      (wrap-cache-loss-kill (nem/setup! original-nemesis test) database))

    (invoke! [_ test op]
      (if (= :kill (:f op))
        (let [nodes (nc/db-nodes test database (:value op))
              result (c/on-nodes test nodes
                                 (fn [test node]
                                   (db/kill-with-cache-loss! database test node)))]
          (assoc op :value result))
        (nem/invoke! original-nemesis test op)))

    (teardown! [_ test]
      (nem/teardown! original-nemesis test))))
