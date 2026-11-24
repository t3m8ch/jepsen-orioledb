(ns jepsen.orioledb.cli-test
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.test :refer :all]
            [clojure.string :as str]
            [clojure.tools.cli :as tools-cli]
            [jepsen.cli :as jc]
            [jepsen.db :as db]
            [jepsen.orioledb.cli :as cli]
            [jepsen.orioledb.db :as orioledb-db]
            [jepsen.postgres.client :as pg])
  (:import (java.nio.file Files)))

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
                   :page-stats-interval 0.5
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
        (is (= 0.5 (:page-stats-interval test)))
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

(deftest lazyfs-cli-option
  (is (not (get-in (tools-cli/parse-opts [] cli/cli-opts) [:options :lazyfs])))
  (let [{:keys [options errors]} (tools-cli/parse-opts ["--lazyfs"] cli/cli-opts)]
    (is (empty? errors))
    (is (true? (:lazyfs options)))))

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

(deftest page-stats-interval-validation
  (is (nil? (get-in (tools-cli/parse-opts [] cli/cli-opts) [:options :page-stats-interval])))
  (doseq [value ["1" "0.25" "1e2"]]
    (let [{:keys [options errors]} (tools-cli/parse-opts ["--page-stats-interval" value] cli/cli-opts)]
      (is (empty? errors))
      (is (= (Double/parseDouble value) (:page-stats-interval options)))))
  (doseq [value ["0" "-1" "NaN" "Infinity" "-Infinity" "abc" "1e999"]]
    (is (seq (:errors (tools-cli/parse-opts ["--page-stats-interval" value] cli/cli-opts))))))

(def trace-opts
  {:trace-evictions true :workload :append :isolation :read-committed
   :nemesis [] :nemesis-interval 5 :rate 10 :time-limit 1
   :concurrency 1 :key-count 10 :key-dist :uniform
   :max-txn-length 4 :max-writes-per-key 256})

(deftest tracing-defaults-sampling-and-preserves-explicit-interval
  (with-redefs [cli/required-env environment
                cli/artifact-checksum (constantly "checksum")]
    (is (:trace-evictions (:options (tools-cli/parse-opts ["--trace-evictions"] cli/cli-opts))))
    (doseq [interval [nil 0.25]]
      (let [test (cli/orioledb-test (cond-> trace-opts interval (assoc :page-stats-interval interval)))]
        (is (= (or interval 1.0) (:page-stats-interval test)))
        (is (:trace-evictions test))))
    (let [test (cli/orioledb-test (assoc trace-opts :engine :postgres))]
      (is (not (:trace-evictions test)))
      (is (nil? (:page-stats-interval test))))))

(deftest trace-artifact-must-match-before-test-setup
  (let [dir (.toFile (Files/createTempDirectory "trace-artifact" (make-array java.nio.file.attribute.FileAttribute 0)))
        facts (io/file dir "build-info.json")
        run-count (atom 0)
        sha (cli/trace-patches-sha256)]
    (try
      (with-redefs [cli/required-env #(if (= "LAB_ARTIFACT" %) (str dir) (environment %))
                    jc/single-test-cmd (fn [_] {"test" {:run (fn [_] (swap! run-count inc))}
                                                "analyze" {:run (constantly :analyzed)}})]
        (let [commands (cli/commands)
              run (get-in commands ["test" :run])]
          (doseq [metadata [{} {:eviction_trace_patch_sha256 (apply str (repeat 64 "0"))}]]
            (spit facts (json/generate-string metadata))
            (is (thrown-with-msg? clojure.lang.ExceptionInfo #"requires an artifact"
                                  (run {:options trace-opts}))))
          (is (= :analyzed ((get-in commands ["analyze" :run]) {:options trace-opts})))
          (is (zero? @run-count))
          (run {:options (assoc trace-opts :engine :postgres)})
          (is (= 1 @run-count))
          (is (re-matches #"[0-9a-f]{64}" sha))
          (spit facts (json/generate-string {:eviction_trace_patch_sha256 sha}))
          (with-redefs [cli/read-archive-build-info (constantly {})]
            (is (thrown-with-msg? clojure.lang.ExceptionInfo #"requires an artifact"
                                  (run {:options trace-opts}))))
          (with-redefs [cli/read-archive-build-info (constantly {:eviction_trace_patch_sha256 sha})]
            (run {:options trace-opts})
            (is (= 2 @run-count)))))
      (finally (doseq [file (reverse (file-seq dir))] (io/delete-file file))))))

(deftest lose-unfsynced-writes-requires-lazyfs-and-kill
  (doseq [[args valid?] [[[] true]
                         [["--lose-unfsynced-writes"] false]
                         [["--lose-unfsynced-writes" "--lazyfs"] false]
                         [["--lose-unfsynced-writes" "--nemesis" "kill"] false]
                         [["--lose-unfsynced-writes" "--lazyfs" "--nemesis" "kill,pause"] true]]]
    (let [parsed (cli/opt-fn (tools-cli/parse-opts args cli/cli-opts))]
      (is (= valid? (empty? (:errors parsed))) (pr-str args)))))
