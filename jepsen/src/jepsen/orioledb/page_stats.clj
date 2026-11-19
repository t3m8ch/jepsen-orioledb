(ns jepsen.orioledb.page-stats
  "Optional VM-side occupancy sampling, independent of workload history."
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.tools.logging :refer [warn]]
            [gnuplot.core :as g]
            [jepsen.checker :as checker]
            [jepsen.checker.perf :as perf]
            [jepsen.control :as c]
            [jepsen.control.util :as cu]
            [jepsen.nemesis :as nemesis]
            [jepsen.orioledb.evictions :as evictions]
            [jepsen.store :as store]
            [jepsen.util :as util]))

(def log-file "/var/log/jepsen/page-stats.jsonl")
(def script-file "/var/log/jepsen/page-stats.sh")
(def pid-file "/var/log/jepsen/page-stats.pid")

(defn enabled? [test]
  (and (= :orioledb (:engine test :orioledb)) (:page-stats-interval test)))

(defn- trace-enabled? [test]
  (and (= :orioledb (:engine test :orioledb)) (:trace-evictions test)))

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
     (apply cu/start-daemon! {:pidfile pid-file :logfile "/var/log/jepsen/page-stats-collector.log"
                        :chdir "/" :match-executable? false}
                       "/usr/bin/setsid" "/bin/bash" script-file
                       (:page-stats-interval test)
                       (double (util/nanos->secs (util/relative-time-nanos)))
                       "/opt/jepsen/pgsql/bin/psql" (:postgres-port test 5432) log-file
                       (when (trace-enabled? test) ["undo"])))))

(defn wrap-nemesis [delegate]
  (reify nemesis/Nemesis
    (setup! [_ test]
      (let [ready (nemesis/setup! delegate test)]
        (try
          (c/on-nodes test (fn [test _] (start! test)))
          (wrap-nemesis ready)
          (catch Throwable t
            (try (c/on-nodes test (fn [_ _] (stop!)))
                 (finally (nemesis/teardown! ready test)))
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

(defn- position [x]
  (cond
    (integer? x) (bigint x)
    (and (string? x) (re-matches #"[0-9]+" x)) (bigint x)))

(defn undo-percentages [samples undo-type]
  (mapv (fn [{:keys [available elapsed undo]}]
          [elapsed
           (when available
             (when-let [{:keys [capacity_bytes last_used_location written_location]}
                        (some #(when (= undo-type (:undo_type %)) %) undo)]
               (let [last-used (position last_used_location)
                     written (position written_location)]
                 (when (and last-used written (integer? capacity_bytes) (pos? capacity_bytes))
                   (double (/ (* 100 (- last-used written)) capacity_bytes))))))])
        samples))

(defn read-samples [file]
  (if (.exists (io/file file))
    (with-open [reader (io/reader file)]
      (into [] (keep (fn [line]
                       (when-not (str/blank? line)
                         (try (json/parse-string line true)
                              (catch Exception e (warn e "Skipping malformed page statistics"))))))
            (line-seq reader)))
    []))

(defn output-commands [test filename size]
  [[:set :terminal :pngcairo :noenhanced :size size]
   [:set :output (str (store/path! test filename))]])

(defn plot-panel [series]
  ;; A multiplot interleaves commands and inline datasets, unlike perf/plot!.
  (let [command [:plot (apply g/list (map perf/legend-part series))]]
    (concat [(g/format command)]
            (mapcat (fn [{:keys [data]}]
                      (concat (map #(str/join " " %) (if (seq data) data [["NaN" 0]])) ["e"]))
                    series))))

(defn render! [lines]
  (let [{:keys [exit err] :as result} (shell/sh "gnuplot" :in (str (str/join "\n" lines) "\n"))]
    (when-not (zero? exit)
      (throw (ex-info (str "Gnuplot error: " err) result)))))

(defn shared-x-commands [[lo hi]]
  [[:set :lmargin :at :screen 0.11] [:set :rmargin :at :screen 0.95]
   [:set :xrange (g/range lo hi)]])

(defn plot-pool-with-strips! [test filename title data strips xrange]
  (let [panels (mapcat (fn [last? {:keys [title] :as strip}]
                         (concat
                          (map g/format
                               (concat (if last?
                                         [[:set :bmargin :at :screen 0.13] [:set :tmargin :at :screen 0.25]
                                          [:set :format :x "%g"] [:set :xtics :out]
                                          [:set :xlabel "Elapsed time (s)"]]
                                         [[:set :bmargin :at :screen 0.26] [:set :tmargin :at :screen 0.38]])
                                       [[:set :label 1 title :at :graph (g/list 0.015 0.78) :front]]))
                          (plot-panel [(-> strip (dissoc :title)
                                           (assoc :with 'points :pointtype 7 :linecolor "#2a78d6"))])))
                       [false true] strips)]
    (render!
     (concat (map g/format
                  (concat (output-commands test filename (g/list 1000 650))
                          (shared-x-commands xrange)
                          [[:set :multiplot]
                           [:set :bmargin :at :screen 0.43] [:set :tmargin :at :screen 0.84]
                           [:set :format :x ""] [:set :key :outside :top :center :horizontal]
                           [:set :ylabel "Pages"] [:set :title title]]))
             (plot-panel data)
             (map g/format [[:unset :title] [:unset :ylabel] [:unset :key] [:unset :ytics]
                            [:unset :xtics] [:set :yrange (g/range -1 1)]])
             panels
             ["unset multiplot" "unset output"]))))

(defn- plot-legacy-undo! [test buckets xrange]
  (render! (concat (map g/format
                        (concat (output-commands test "undo-evictions.png" (g/list 1000 330))
                                (shared-x-commands xrange)
                                [[:set :key :outside :top :center :horizontal]
                                 [:set :title "OrioleDB undo-ring evictions"]
                                 [:set :xlabel "Elapsed time (s)"]
                                 [:set :grid :ytics :back]
                                 [:set :ytics (g/lit "('row' 0, 'page' 1, 'system' 2)")]
                                 [:set :yrange (g/range -0.55 2.55)]]))
                   (plot-panel (evictions/undo-series buckets)))))

(defn- undo-occupancy-panel [undo-type percentages]
  (let [outside (keep (fn [[_ pct]] (when (and pct (not (<= 0 pct 100))) pct)) percentages)
        title (str undo-type " undo"
                   (cond
                     (not-any? second percentages) " (no samples)"
                     (seq outside) (format " — %d out of range (%.3g%% to %.3g%%; triangles)"
                                           (count outside) (reduce min outside) (reduce max outside))))]
    {:title title
     :series [{:with 'linespoints :pointtype 7 :linecolor "#315a78" :linewidth 1.5
               :data (mapv (fn [[t pct]] [t (if (and pct (<= 0 pct 100)) pct Double/NaN)]) percentages)}
              ;; Boundary triangles mark outliers, not occupancy at 0% or 100%.
              {:with 'points :pointtype 11 :linecolor "#9a3721"
               :data (into [] (keep (fn [[t pct]] (when (and pct (neg? pct)) [t 0]))) percentages)}
              {:with 'points :pointtype 9 :linecolor "#9a3721"
               :data (into [] (keep (fn [[t pct]] (when (and pct (> pct 100)) [t 100]))) percentages)}]}))

(defn- plot-undo-occupancy! [test panels buckets xrange]
  (render!
   (concat
    (map g/format
         (concat (output-commands test "undo-evictions.png" (g/list 1000 930))
                 (shared-x-commands xrange)
                 [[:set :multiplot :title "OrioleDB undo ring occupancy and evictions"]]))
    (mapcat
     (fn [i undo-type {:keys [title series]}]
       (let [top (- 0.86 (* i 0.26))
             last? (= i (dec (count panels)))]
         (concat
          (map g/format [[:unset :key] [:unset :xlabel] [:unset :xtics]
                         [:set :bmargin :at :screen (- top 0.15)] [:set :tmargin :at :screen top]
                         [:set :title title] [:set :ylabel "Ring used (%)"]
                         [:set :yrange (g/range 0 100)] [:set :ytics 25] [:set :grid :ytics :back]])
          (plot-panel series)
          (map g/format (concat [[:unset :title] [:unset :grid] [:unset :ytics]
                                 [:set :ylabel "Evictions"] [:set :yrange (g/range -0.5 0.5)]
                                 [:set :bmargin :at :screen (- top 0.205)]
                                 [:set :tmargin :at :screen (- top 0.155)]]
                                (when (zero? i)
                                  [[:set :key (g/lit "at screen 0.53,0.93 center horizontal")]])
                                (when last?
                                  [[:set :xtics :out] [:set :xlabel "Elapsed time (s)"]])))
          (plot-panel (evictions/undo-series buckets undo-type)))))
     (range) evictions/undo-types panels)
    ["unset multiplot" "unset output"])))

(defn plot-undo! [test samples buckets xrange]
  (let [percentages (mapv #(undo-percentages samples %) evictions/undo-types)]
    (if (some (fn [data] (some second data)) percentages)
      (plot-undo-occupancy! test (mapv undo-occupancy-panel evictions/undo-types percentages) buckets xrange)
      (plot-legacy-undo! test buckets xrange))))

(defn plot! [test]
  (let [samples-by-node (mapv (fn [node]
                                [(name node) (read-samples (store/path test (name node) "page-stats.jsonl"))])
                              (:nodes test))
        samples (mapcat second samples-by-node)
        trace? (trace-enabled? test)
        buckets (when trace? (evictions/export! test samples-by-node))
        xrange (when-let [[lo hi] (evictions/sample-range samples)]
                 (if (= lo hi) [(- lo 0.5) (+ hi 0.5)] [lo hi]))]
    (when xrange
      (doseq [[pool suffix] pools
              :let [data (series samples pool)
                    title (str "OrioleDB " pool " occupancy")
                    filename (str "page-stats-" suffix ".png")]
              :when (some #(Double/isFinite (double (second %))) (mapcat :data data))]
        (if trace?
          (plot-pool-with-strips! test filename title data (evictions/pool-strips buckets pool) xrange)
          (perf/plot! {:preamble (into (output-commands test filename (g/list 1000 500))
                                       [[:set :key :outside :right]
                                        [:set :xlabel "Elapsed time (s)"]
                                        [:set :ylabel "Pages"]
                                        [:set :title title]])
                       :series data})))
      (when trace? (plot-undo! test samples buckets xrange)))))

(defn plot-checker []
  (reify checker/Checker
    (check [_ test _history _opts]
      (if (enabled? test)
        (try (plot! test) {:valid? true}
             (catch Exception e
               (warn e "Could not plot page statistics")
               {:valid? true :error (.getMessage e)}))
        {:valid? true :disabled true}))))
