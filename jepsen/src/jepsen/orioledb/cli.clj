(ns jepsen.orioledb.cli
  (:gen-class)
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [jepsen.checker :as checker]
            [jepsen.cli :as cli]
            [jepsen.generator :as gen]
            [jepsen.nemesis.combined :as nc]
            [jepsen.os :as os]
            [jepsen.postgres.client :as pg-client]
            [jepsen.postgres.cli :as pg-cli]
            [jepsen.sql :as sql]
            [jepsen.tests :as tests]
            [jepsen.orioledb.db :as db]))

(defn required-env [key]
  (or (not-empty (System/getenv key))
      (throw (ex-info (str "Missing " key "; run through mise run test:run") {:key key}))))

(defn artifact-checksum [artifact]
  (or (some (fn [line]
              (let [[checksum filename] (str/split line #"  " 2)]
                (when (= filename "pgsql.tar.gz") checksum)))
            (str/split-lines (slurp (io/file artifact "SHA256SUMS"))))
      (throw (ex-info "Artifact archive is missing from SHA256SUMS" {}))))

(defn open [test _node]
  (pg-client/open test (:data-host test)))

(def workloads (sql/workloads {:open open :error-fn pg-client/error-fn}))

(defn orioledb-test [opts]
  (let [engine (or (:engine opts) :orioledb)
        artifact (required-env "LAB_ARTIFACT")
        host (required-env "LAB_SSH_HOST")
        database (db/database)
        workload ((workloads (:workload opts)) opts)
        nemesis (nc/nemesis-package
                 {:db database :nodes [host] :faults (:nemesis opts)
                  :pause {:targets [:all]}
                  :kill {:targets [:all]}
                  :interval (:nemesis-interval opts)})]
    (merge tests/noop-test opts
           {:engine engine
            :name (str (name engine) "-" (name (:workload opts)) "-" (name (:isolation opts)))
            :nodes [host]
            :ssh {:username (required-env "LAB_SSH_USER")
                  :private-key-path (required-env "LAB_SSH_KEY")
                  :port (parse-long (required-env "LAB_SSH_PORT"))
                  :strict-host-key-checking false}
            :artifact artifact
            :artifact-sha256 (artifact-checksum artifact)
            :data-host (required-env "LAB_DATA_HOST")
            :os os/noop
            :db database
            :client (:client workload)
            :nemesis (:nemesis nemesis)
            :checker (checker/compose
                      {:stats (checker/stats)
                       :exceptions (checker/unhandled-exceptions)
                       :perf (checker/perf {:nemeses (:perf nemesis)})
                       :workload (:checker workload)})
            :generator
            ;; Keep one stateful workload generator across faults and recovery;
            ;; restarting it would reuse append values and invalidate Elle's history.
            (->> (:generator workload)
                 (gen/stagger (/ (:rate opts)))
                 (gen/nemesis
                  (gen/phases
                   (gen/time-limit (:time-limit opts) (:generator nemesis))
                   (:final-generator nemesis)))
                 (gen/time-limit (+ (:time-limit opts)
                                    (if (seq (:nemesis opts)) 10 0))))})))

(def cli-opts
  (cli/merge-opt-specs
   sql/cli-opts
   [[nil "--engine ENGINE" "Storage engine: postgres or orioledb"
     :default :orioledb :parse-fn keyword
     :validate [#{:postgres :orioledb} "Supported engines: postgres and orioledb"]]
    ["-i" "--isolation LEVEL" "Transaction isolation level"
     :default :read-committed :parse-fn keyword
     :validate [#{:read-committed :repeatable-read}
                "Supported isolation levels: read-committed and repeatable-read"]]
    ["-w" "--workload NAME" "SQL workload"
     :default :append
     :parse-fn keyword
     :validate [workloads (cli/one-of workloads)]]
    [nil "--nemesis FAULTS" "none, kill, pause, or kill,pause"
     :default [] :parse-fn pg-cli/parse-nemesis-spec
     :validate [(partial every? #{:kill :pause}) "Supported faults: kill and pause"]]
    [nil "--postgres-user USER" "Database user" :default "postgres"]
    [nil "--postgres-port PORT" "Database port" :default 5432 :parse-fn parse-long]
    [nil "--postgres-sslmode MODE" "JDBC SSL mode" :default "disable"]
    ["-r" "--rate HZ" "Approximate requests per second"
     :default 10
     :parse-fn parse-double
     :validate [pos? "Must be positive"]]]))

(defn -main [& args]
  (cli/run! (merge (cli/single-test-cmd {:test-fn orioledb-test
                                         :opt-spec cli-opts
                                         :opt-fn sql/opt-fn})
                   (cli/serve-cmd))
            args))
