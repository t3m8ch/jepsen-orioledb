(ns jepsen.orioledb.cli
  (:gen-class)
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
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
            [jepsen.orioledb.nemesis :as snapshot]
            [jepsen.orioledb.page-stats :as page-stats]
            [jepsen.orioledb.db :as db])
  (:import (java.security MessageDigest)
           (java.util HexFormat)))

(defn required-env [key]
  (or (not-empty (System/getenv key))
      (throw (ex-info (str "Missing " key "; run through mise run test:run") {:key key}))))

(defn artifact-checksum [artifact]
  (or (some (fn [line]
              (let [[checksum filename] (str/split line #"  " 2)]
                (when (= filename "pgsql.tar.gz") checksum)))
            (str/split-lines (slurp (io/file artifact "SHA256SUMS"))))
      (throw (ex-info "Artifact archive is missing from SHA256SUMS" {}))))

(defn trace-patches-sha256 []
  (let [digest (MessageDigest/getInstance "SHA-256")]
    (doseq [filename ["orioledb-eviction-trace.patch" "orioledb-undo-buffer-stats.patch"]]
      (with-open [patch (io/input-stream (io/resource filename))]
        (.update digest (.readAllBytes patch))))
    (.formatHex (HexFormat/of) (.digest digest))))

(defn read-archive-build-info [artifact]
  (let [{:keys [exit out err]}
        (shell/sh "bsdtar"
                  "-xOf"
                  (str (io/file artifact "pgsql.tar.gz")) "./build-info.json")]
    (when-not (zero? exit)
      (throw (ex-info "Cannot read build-info.json inside artifact" {:artifact artifact :error err})))
    (json/parse-string out true)))

(defn verify-run-artifact! [{:keys [trace-evictions engine]}]
  (when (and trace-evictions (not= :postgres engine))
    (let [artifact (required-env "LAB_ARTIFACT")
          expected (trace-patches-sha256)
          facts (io/file artifact "build-info.json")
          actual (when (.isFile facts)
                   (:eviction_trace_patch_sha256 (json/parse-string (slurp facts) true)))
          embedded (when (= expected actual)
                     (:eviction_trace_patch_sha256 (read-archive-build-info artifact)))]
      (when-not (= expected actual embedded)
        (throw (ex-info "OrioleDB eviction tracing requires an artifact with the current trace patches; rebuild with --trace-evictions"
                        {:expected expected :actual actual :embedded embedded :artifact artifact}))))))

(defn open [test _node]
  (pg-client/open test (:data-host test)))

(def workloads-builders (sql/workloads {:open open :error-fn pg-client/error-fn}))

(defn orioledb-test [opts]
  (let [engine (or (:engine opts) :orioledb)
        opts (cond-> (assoc opts :engine engine)
               (= :postgres engine) (dissoc :trace-evictions)
               (and (= :orioledb engine) (:trace-evictions opts) (nil? (:page-stats-interval opts)))
               (assoc :page-stats-interval 1.0))
        artifact (required-env "LAB_ARTIFACT")
        host (required-env "LAB_SSH_HOST")
        database (db/database)
        workload (let [make-workload (workloads-builders (:workload opts))]
                   (make-workload opts))
        faults (set (:nemesis opts))
        db-faults (disj faults :hold-snapshot)
        db-pkg (when (seq db-faults)
                 (nc/db-package
                  {:db database
                   :faults db-faults
                   :pause {:targets [:all]}
                   :kill {:targets [:all]}
                   :interval (:nemesis-interval opts)}))
        snapshot-pkg (when (faults :hold-snapshot)
                       (snapshot/snapshot-package))
        combined (nc/compose-packages (remove nil? [db-pkg snapshot-pkg]))
        nemesis (if snapshot-pkg
                  (assoc combined :generator
                         (gen/phases
                          (:generator snapshot-pkg)
                          (if db-pkg
                            (:generator db-pkg)
                            (gen/sleep (:time-limit opts)))))
                  combined)]
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
            :nemesis (if (page-stats/enabled? (assoc opts :engine engine))
                       (page-stats/wrap-nemesis (:nemesis nemesis))
                       (:nemesis nemesis))
            :checker (checker/compose
                      {:page-stats (page-stats/plot-checker)
                       :stats (checker/stats)
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
     :validate [workloads-builders (cli/one-of workloads-builders)]]
    [nil "--nemesis FAULTS" "Comma-separated faults: kill, pause, hold-snapshot; or none"
     :default []
     :parse-fn pg-cli/parse-nemesis-spec
     :validate [#(every? #{:kill :pause :hold-snapshot} %)
                "Supported faults: kill, pause, hold-snapshot"]]
    [nil "--postgres-user USER" "Database user" :default "postgres"]
    [nil "--postgres-port PORT" "Database port" :default 5432 :parse-fn parse-long]
    [nil "--postgres-sslmode MODE" "JDBC SSL mode" :default "disable"]
    [nil "--trace-evictions" "Trace OrioleDB evictions and non-evicting writeback (DEBUG1); defaults to 1s occupancy sampling; ignored with --engine postgres"]
    [nil "--page-stats-interval SECONDS" "Collect and plot OrioleDB pool occupancy (disabled if omitted; ignored with --engine postgres)"
     :parse-fn #(Double/parseDouble %)
     :validate [#(and (Double/isFinite %) (pos? %)) "Must be a finite positive duration"]]
    [nil "--main-buffers SIZE" "OrioleDB main buffer size, e.g. 8MB (server default if omitted; ignored with --engine postgres)"
     :validate [#(boolean (re-matches #"[1-9][0-9]*(?:kB|MB|GB|TB)" %))
                "Must be a positive integer followed by kB, MB, GB, or TB"]]
    [nil "--undo-buffers SIZE" "OrioleDB undo buffer size, e.g. 32MB (server default if omitted; ignored with --engine postgres)"
     :validate [#(boolean (re-matches #"[1-9][0-9]*(?:kB|MB|GB|TB)" %))
                "Must be a positive integer followed by kB, MB, GB, or TB"]]
    ["-r" "--rate HZ" "Approximate requests per second"
     :default 10
     :parse-fn parse-double
     :validate [pos? "Must be positive"]]]))

(defn commands []
  (let [commands (cli/single-test-cmd {:test-fn orioledb-test
                                       :opt-spec cli-opts
                                       :opt-fn sql/opt-fn})]
    ;; Offline analyze reconstructs the test without installing an artifact.
    (update-in commands ["test" :run]
               (fn [run]
                 (fn [{:keys [options] :as parsed}]
                   (verify-run-artifact! options)
                   (run parsed))))))

(defn -main [& args]
  (cli/run! (merge (commands) (cli/serve-cmd)) args))
