(ns jepsen.orioledb.nemesis
  (:require [jepsen.nemesis :as nem]
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

