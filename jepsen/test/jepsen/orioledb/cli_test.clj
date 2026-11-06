(ns jepsen.orioledb.cli-test
  (:require [clojure.test :refer :all]
            [jepsen.db :as db]
            [jepsen.orioledb.cli :as cli]
            [jepsen.postgres.client :as pg]))

(def environment
  {"LAB_ARTIFACT" "/unused/cache"
   "LAB_SSH_HOST" "192.168.121.10"
   "LAB_SSH_USER" "vagrant"
   "LAB_SSH_KEY" "/actual/vagrant/key"
   "LAB_SSH_PORT" "22"
   "LAB_DATA_HOST" "192.168.77.11"})

(deftest construct-test-with-one-node-and-supported-faults
  (doseq [faults [[] [:kill] [:pause] [:kill :pause]]]
    (with-redefs [cli/required-env environment
                  cli/artifact-checksum (constantly "checksum")]
      (let [test (cli/orioledb-test
                  {:nodes ["ignored-node" "another-ignored-node"]
                   :workload :append
                   :isolation :read-committed
                   :expected-consistency-model :read-committed
                   :nemesis faults
                   :nemesis-interval 5
                   :rate 10
                   :time-limit 30
                   :concurrency 4
                   :key-count 10
                   :key-dist :exponential
                   :key-types [:primary]
                   :encodings [:text]
                   :upsert-types [:on-conflict]
                   :max-txn-length 4
                   :max-writes-per-key 256
                   :mop-delay 0
                   :savepoints true
                   :wfr-keys? true})]
        (is (= ["192.168.121.10"] (:nodes test)))
        (is (= "192.168.77.11" (:data-host test)))
        (is (= "/actual/vagrant/key" (get-in test [:ssh :private-key-path])))
        (is (satisfies? db/LogFiles (:db test)))
        (is (satisfies? db/Kill (:db test)))
        (is (satisfies? db/Pause (:db test)))
        (is (:generator test))))))

(deftest jdbc-uses-data-network-while-jepsen-uses-management-network
  (with-redefs [pg/open (fn [test node] [(:postgres-user test) node])]
    (is (= ["postgres" "192.168.77.11"]
           (cli/open {:postgres-user "postgres" :data-host "192.168.77.11"}
                     "192.168.121.10")))))
