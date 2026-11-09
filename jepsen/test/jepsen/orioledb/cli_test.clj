(ns jepsen.orioledb.cli-test
  (:require [clojure.test :refer :all]
            [clojure.string :as str]
            [clojure.tools.cli :as tools-cli]
            [jepsen.db :as db]
            [jepsen.orioledb.cli :as cli]
            [jepsen.orioledb.db :as orioledb-db]
            [jepsen.postgres.client :as pg]))

(def environment
  {"LAB_ARTIFACT" "/unused/cache"
   "LAB_SSH_HOST" "192.168.121.10"
   "LAB_SSH_USER" "vagrant"
   "LAB_SSH_KEY" "/actual/vagrant/key"
   "LAB_SSH_PORT" "22"
   "LAB_DATA_HOST" "192.168.77.11"})

(deftest construct-test-with-one-node-and-supported-faults
  (doseq [engine [nil :postgres :orioledb]
          faults [[] [:kill] [:pause] [:kill :pause]]]
    (with-redefs [cli/required-env environment
                  cli/artifact-checksum (constantly "checksum")]
      (let [test (cli/orioledb-test
                  {:nodes ["ignored-node" "another-ignored-node"]
                   :engine engine
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
        (is (= (or engine :orioledb) (:engine test)))
        (is (= (str (name (or engine :orioledb)) "-append-read-committed")
               (:name test)))
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

(deftest engine-cli-option
  (is (= :orioledb (get-in (tools-cli/parse-opts [] cli/cli-opts) [:options :engine])))
  (doseq [engine ["postgres" "orioledb"]]
    (let [{:keys [options errors]} (tools-cli/parse-opts ["--engine" engine] cli/cli-opts)]
      (is (empty? errors))
      (is (= (keyword engine) (:engine options)))))
  (doseq [args [["--engine" "heap"] ["--engine" "unknown"] ["--engine"]]]
    (is (seq (:errors (tools-cli/parse-opts args cli/cli-opts))))))

(deftest buffer-cli-options-reach-cluster-config
  (let [{:keys [options errors]}
        (tools-cli/parse-opts ["--main-buffers" "8MB" "--undo-buffers" "32MB"] cli/cli-opts)
        config (orioledb-db/cluster-config options)]
    (is (empty? errors))
    (is (= "8MB" (:main-buffers options)))
    (is (= "32MB" (:undo-buffers options)))
    (is (str/includes? config "orioledb.main_buffers = '8MB'\n"))
    (is (str/includes? config "orioledb.undo_buffers = '32MB'\n"))))

(deftest buffer-cli-size-validation
  (doseq [flag ["--main-buffers" "--undo-buffers"]]
    (doseq [size ["1kB" "8MB" "1GB" "1TB"]]
      (is (empty? (:errors (tools-cli/parse-opts [flag size] cli/cli-opts)))))
    (doseq [size ["0MB" "-1MB" "8" "8MiB" "1.5MB" "8MB\nfsync=off" "8MB'"]]
      (is (seq (:errors (tools-cli/parse-opts [flag size] cli/cli-opts)))))
    (is (seq (:errors (tools-cli/parse-opts [flag] cli/cli-opts))))))
