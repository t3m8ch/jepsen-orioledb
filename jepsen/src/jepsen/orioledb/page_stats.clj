(ns jepsen.orioledb.page-stats
  "Optional VM-side occupancy sampling, independent of workload history."
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.logging :refer [warn]]
            [jepsen.checker :as checker]
            [jepsen.checker.perf :as perf]
            [jepsen.control :as c]
            [jepsen.control.util :as cu]
            [jepsen.nemesis :as nemesis]
            [jepsen.store :as store]
            [jepsen.util :as util]))

(def log-file "/var/log/jepsen/page-stats.jsonl")
(def script-file "/var/log/jepsen/page-stats.sh")
(def pid-file "/var/log/jepsen/page-stats.pid")

(defn enabled? [test]
  (and (= :orioledb (:engine test :orioledb)) (:page-stats-interval test)))

(defn stop! []
  ;; Stop the entire session, including a blocked psql or a long interval sleep.
  ;; Wait before exporting; KILL is a bounded fallback. The pidfile is root-owned.
  (c/su
   (c/exec :bash :-c
           (str "if test -f " pid-file "; then pid=$(cat " pid-file "); "
                "kill -TERM -- -\"$pid\" 2>/dev/null || true; "
                "for i in {1..40}; do kill -0 -- -\"$pid\" 2>/dev/null || break; sleep .1; done; "
                "kill -KILL -- -\"$pid\" 2>/dev/null || true; rm -f " pid-file "; fi"))))

(defn prepare! []
  (stop!)
  (c/su
   (c/exec :rm :-f log-file)
   (cu/write-file! (slurp (io/resource "page-stats.sh")) script-file)))

(defn start! [test]
  (when (enabled? test)
    (c/su
     (c/exec :touch log-file)
     (c/exec :chmod "0644" log-file)
     (cu/start-daemon! {:pidfile pid-file :logfile "/var/log/jepsen/page-stats-collector.log"
                        :chdir "/" :match-executable? false}
                       "/usr/bin/setsid" "/bin/bash" script-file
                       (:page-stats-interval test)
                       (double (util/nanos->secs (util/relative-time-nanos)))
                       "/opt/jepsen/pgsql/bin/psql" (:postgres-port test 5432) log-file))))

(defn wrap-nemesis [delegate]
  (reify nemesis/Nemesis
    (setup! [_ test]
      (let [ready (nemesis/setup! delegate test)]
        (try
          (c/on-nodes test (fn [test _] (start! test)))
          (wrap-nemesis ready)
          (catch Throwable t
            (c/on-nodes test (fn [_ _] (stop!)))
            (throw t)))))
    (invoke! [_ test op] (nemesis/invoke! delegate test op))
    (teardown! [_ test]
      (try (nemesis/teardown! delegate test)
           (finally (c/on-nodes test (fn [_ _] (stop!))))))))

(def pools [["main" "main"] ["free_tree" "free-tree"] ["catalog" "catalog"]])
(def metrics [:busy_pages :dirty_pages :all_pages])

(defn series [samples pool]
  (mapv (fn [metric]
          {:title (name metric) :with 'linespoints
           :data (mapv (fn [sample]
                         [(:elapsed sample)
                          (or (when (:available sample)
                                (some #(when (= pool (:pool_name %)) (get % metric))
                                      (:pools sample)))
                              Double/NaN)]) samples)}) metrics))

(defn read-samples [file]
  (if (.exists (io/file file))
    (with-open [reader (io/reader file)]
      (into [] (keep (fn [line]
                       (when-not (str/blank? line)
                         (try (json/parse-string line true)
                              (catch Exception e (warn e "Skipping malformed page statistics"))))))
            (line-seq reader)))
    []))

(defn plot! [test]
  (let [samples (mapcat #(read-samples (store/path test (name %) "page-stats.jsonl"))
                        (:nodes test))]
    (when (seq samples)
      (doseq [[pool filename] pools
              :let [data (series samples pool)]
              :when (some #(Double/isFinite (double (second %))) (mapcat :data data))]
        (perf/plot! {:preamble [[:set :terminal :pngcairo :size (str "1000,500")]
                               [:set :output (str (store/path! test (str "page-stats-" filename ".png"))) ]
                               [:set :xlabel "Elapsed time (s)"]
                               [:set :ylabel "Pages"]
                               [:set :title (str "OrioleDB " pool " occupancy")]]
                     :series data})))))

(defn plot-checker []
  (reify checker/Checker
    (check [_ test _history _opts]
      (if (enabled? test)
        (try (plot! test) {:valid? true}
             (catch Exception e
               (warn e "Could not plot page statistics")
               {:valid? true :error (.getMessage e)}))
        {:valid? true :disabled true}))))
