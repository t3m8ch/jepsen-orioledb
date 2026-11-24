(ns jepsen.orioledb.page-stats-test
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [jepsen.checker :as checker]
            [jepsen.checker.perf :as perf]
            [jepsen.control :as c]
            [jepsen.control.util :as cu]
            [jepsen.db :as db]
            [jepsen.nemesis :as nemesis]
            [jepsen.orioledb.db :as odb]
            [jepsen.orioledb.evictions :as trace]
            [jepsen.orioledb.page-stats :as ps]
            [jepsen.store :as store]
            [jepsen.util :as util])
  (:import (java.nio.file Files)))

(def snapshots
  [{:elapsed 0.0 :available true
    :pools (mapv #(hash-map :pool_name % :busy_pages 20 :dirty_pages 5 :all_pages 100)
                 ["main" "free_tree" "catalog" "future_pool"])}
   {:elapsed 1.0 :available false :pools []}
   {:elapsed 2.0 :available true
    :pools (mapv #(hash-map :pool_name % :busy_pages 30 :dirty_pages 10 :all_pages 100)
                 ["main" "free_tree" "catalog"])}])

(deftest outages-are-gaps
  (let [data (:data (first (ps/series snapshots "main")))]
    (is (= [0.0 20] (first data)))
    (is (Double/isNaN (second (second data))))
    (is (= [2.0 30] (last data))))
  (is (every? #(Double/isNaN (second %))
              (:data (first (ps/series snapshots "absent"))))))

(deftest export-stops-collector-and-selects-log-only-when-enabled
  (doseq [test [{} {:engine :postgres :page-stats-interval 1}
                {:engine :orioledb :page-stats-interval 1}]]
    (let [stopped (atom false)]
      (with-redefs [ps/stop! #(reset! stopped true)
                    c/exec (fn [& _]) cu/exists? (constantly false)]
        (let [logs (db/log-files (odb/database) test "n1")]
          (is @stopped)
          (is (= (boolean (ps/enabled? test)) (contains? logs ps/log-file))))))))

(defn log-line [second payload]
  (str "2026-11-12 12:00:" second " UTC [42] DEBUG:  " payload))

(def undo-line
  "orioledb_trace event=undo_ring_evict undo_type=row reason=inplace_rewrite from=10 to=30 bytes=20")

(deftest renders-real-pngs-and-exports-ungrouped-events
  (let [dir (.toFile (Files/createTempDirectory "page-stats-test" (make-array java.nio.file.attribute.FileAttribute 0)))
        path (fn [_ & parts] (apply io/file dir parts))
        test {:engine :orioledb :page-stats-interval 1 :trace-evictions true :nodes ["n1" "n2"]}
        log (str (log-line "01.250" "orioledb_trace event=btree_evict pool=main candidate_blkno=4") "\n"
                 (log-line "01.250" "orioledb_trace event=btree_evict pool=main candidate_blkno=4") "\n"
                 (log-line "01.450" "orioledb_trace event=btree_writeback pool=free_tree candidate_blkno=5") "\n"
                 (log-line "01.650" undo-line) "\n"
                 (log-line "02.850" undo-line) "\n"
                 (log-line "01.250" "Wrote plain disk page: checkpoint 7") "\n")]
    (try
      (doseq [node ["n1" "n2"]]
        (io/make-parents (path test node "postgresql.log"))
        (spit (path test node "postgresql.log") log))
      (spit (path test "n1" "page-stats.jsonl")
            (apply str (map #(str (json/generate-string (assoc % :timestamp "2026-11-12T12:00:00Z")) "\n") snapshots)))
      (with-redefs [store/path path store/path! path]
        (is (= {:valid? true} (checker/check (ps/plot-checker) test [] {})))
        (let [events (ps/read-samples (path test "evictions.jsonl"))]
          (is (= 10 (count events)))
          (is (= [1.25 1.25 1.45 1.65 2.85 nil nil nil nil nil] (map :elapsed events)))
          (is (= (first events) (second events)))
          (is (= "2026-11-12T12:00:02.850Z" (:timestamp (nth events 4)))))
        (is (= log (slurp (path test "n1" "postgresql.log"))))
        (doseq [filename ["page-stats-main.png" "page-stats-free-tree.png" "page-stats-catalog.png" "undo-evictions.png"]]
          (let [file (path test filename)]
            (is (> (.length file) 1000))
            (when (.exists file)
              (is (= "PNG" (subs (slurp file :encoding "ISO-8859-1") 1 4))))))
        (let [chart #(javax.imageio.ImageIO/read (path test "undo-evictions.png"))
              before (slurp (path test "evictions.jsonl"))
              file (path test "n1" "page-stats.jsonl")
              records (ps/read-samples file)
              undo (fn [type capacity last-used written]
                     {:undo_type type :capacity_bytes capacity
                      :last_used_location last-used :written_location written})]
          (is (= 630 (.getHeight (chart))))
          (spit file (str (json/generate-string
                           (assoc (first records) :undo [(undo "row" 8 "5" "1")
                                                         (undo "page" 8 "1" "2")
                                                         (undo "system" 8 "10" "1")])) "\n"
                          (slurp file)))
          (is (= {:valid? true} (checker/check (ps/plot-checker) test [] {})))
          (is (= 1100 (.getHeight (chart))))
          (is (= before (slurp (path test "evictions.jsonl"))))))
      (finally (doseq [file (reverse (file-seq dir))] (io/delete-file file))))))

(deftest disabled-empty-and-unavailable-do-not-fabricate-plots
  (let [renders (atom [])]
    (with-redefs [store/path (fn [& _] "unused")
                  perf/plot! #(swap! renders conj %)
                  ps/read-samples (constantly [])]
      (is (= {:valid? true :disabled true}
             (checker/check (ps/plot-checker) {:engine :postgres :page-stats-interval 1} [] {})))
      (is (= {:valid? true}
             (checker/check (ps/plot-checker) {:engine :orioledb :page-stats-interval 1 :nodes ["n1"]} [] {})))
      (is (empty? @renders)))
    (with-redefs [store/path (fn [& _] "unused")
                  perf/plot! #(swap! renders conj %)
                  ps/read-samples (constantly [{:elapsed 1 :available false :pools []}])]
      (ps/plot! {:nodes ["n1"]})
      (is (empty? @renders)))))

(deftest nemesis-wrapper-cleans-up-on-success-and-start-failure
  (doseq [fail-start? [false true]]
    (let [events (atom [])
          delegate (reify nemesis/Nemesis
                     (setup! [this _] (swap! events conj :setup) this)
                     (invoke! [_ _ op] op)
                     (teardown! [_ _] (swap! events conj :teardown)))
          test {:nodes ["n1"]}]
      (with-redefs [c/on-nodes (fn [test f] (f test "n1"))
                    ps/start! (fn [_] (swap! events conj :start)
                                (when fail-start? (throw (ex-info "start failed" {}))))
                    ps/stop! #(swap! events conj :stop)]
        (if fail-start?
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"start failed"
                                (nemesis/setup! (ps/wrap-nemesis delegate) test)))
          (let [ready (nemesis/setup! (ps/wrap-nemesis delegate) test)]
            (is (= {:f :pause} (nemesis/invoke! ready test {:f :pause})))
            (nemesis/teardown! ready test)))
        (is (= (if fail-start? [:setup :start :stop :teardown]
                   [:setup :start :teardown :stop]) @events))))))

(deftest trace-parsing-alignment-and-routing
  (let [anchor {:timestamp "2026-11-12T12:00:00Z" :elapsed 10}
        parse #(trace/parse-event (log-line %1 %2) %3 anchor)
        evict #(parse % "orioledb_trace event=btree_evict pool=main candidate_blkno=17" "n1")
        writeback (parse "01.290" "orioledb_trace event=btree_writeback pool=free_tree candidate_blkno=19" "n2")
        undo (parse "01.250" undo-line "n1")
        bounds {"n1" [10 12] "n2" [11 11.5]}
        events [(evict "01.750") (evict "01.250") (evict "02.050") writeback undo]
        buckets (reduce #(trace/record-event %1 (bounds (:node %2)) %2) {} events)]
    (is (= 11.25 (:elapsed undo)))
    (is (= [42 "n1" "row" "inplace_rewrite" 10 30 20]
           ((juxt :pid :node :undo_type :reason :from :to :bytes) undo)))
    (is (= 17 (:candidate_blkno (evict "01.250"))))
    (doseq [payload ["Wrote plain disk page: checkpoint 7" "orioledb_trace event=did_write"
                     "prefix orioledb_trace event=btree_evict pool=main candidate_blkno=17"]]
      (is (nil? (parse "02.000" payload "n1"))))
    (is (= [{11 2} nil] (mapv :counts (trace/pool-counts buckets "main"))))
    (is (= [nil {11 1}] (mapv :counts (trace/pool-counts buckets "free_tree"))))
    (is (= [nil nil] (mapv :counts (trace/pool-counts buckets "catalog"))))))

(deftest undo-counts-stack-by-type-and-reason
  (let [reasons (map first trace/undo-styles)
        events (for [type trace/undo-types reason reasons elapsed [3.25 3.75]]
                 {:undo_type type :reason reason :elapsed elapsed})
        buckets (reduce #(trace/record-event %1 [0 5] %2) {} (reverse events))]
    (doseq [type trace/undo-types]
      (let [series (trace/undo-counts buckets type)]
        (is (= (vec reasons) (mapv :title series)))
        (is (= (repeat 5 {3 2}) (map :counts series)))
        (is (= (mapv (fn [lo] [[3.5 (double (inc lo)) 3 4 lo (+ lo 2)]]) (range 0 10 2))
               (mapv :data (ps/stacked-counts series))))))))

(deftest only-whole-seconds-with-log-coverage-have-a-zero-baseline
  (is (= [2 3] (trace/complete-seconds {:coverage [[0.1 4.8] [1.1 4.5]]})))
  (is (empty? (trace/complete-seconds {:coverage [[0 5] nil]})))
  (is (empty? (trace/complete-seconds {:coverage [[0.1 0.9]]})))
  (let [series [{:counts {1 7, 2 8, 3 9, 4 10}}]]
    (is (= [{:counts {2 8, 3 9}}] (ps/covered-counts series (range 2 4))))
    (is (= [{:counts {}}] (ps/covered-counts series nil)))))

(deftest trace-config-is-global-and-engine-specific
  (is (re-find #"log_min_messages = debug1"
               (odb/cluster-config {:engine :orioledb :trace-evictions true})))
  (is (re-find #"log_timezone = 'UTC'"
               (odb/cluster-config {:trace-evictions true})))
  (doseq [test [{} {:engine :postgres :trace-evictions true}]]
    (is (not (re-find #"debug1" (odb/cluster-config test))))))

(defn undo-sample [elapsed capacity last-used written]
  {:elapsed elapsed :available true
   :undo [{:undo_type "row" :capacity_bytes capacity
           :last_used_location last-used :written_location written}]})

(deftest occupancy-subtracts-exactly-before-converting-to-percent
  (let [samples [(undo-sample 0 8 "18446744073709551615" "18446744073709551612")
                 {:elapsed 1 :available true :pools []}
                 (undo-sample 2 8 "18446744073709551612" "18446744073709551613")
                 (undo-sample 3 8 "18446744073709551615" "18446744073709551606")
                 (undo-sample 4 0 "2" "1")
                 (assoc (undo-sample 5 8 "2" "1") :available false)
                 (undo-sample 6 8 "1" "1")
                 (undo-sample 7 8 "9" "1")]]
    (is (= [[0 37.5] [1 nil] [2 -12.5] [3 112.5] [4 nil] [5 nil] [6 0.0] [7 100.0]]
           (ps/undo-percentages samples "row")))
    (is (every? (comp nil? second) (ps/undo-percentages samples "page")))))

(deftest sampler-start-only-requests-undo-for-traced-orioledb
  (doseq [[opts args] [[{:trace-evictions true} ["undo"]]
                       [{} []]
                       [{:engine :postgres :trace-evictions true} nil]]]
    (let [started (atom nil)]
      (with-redefs [c/exec (fn [& _])
                    util/relative-time-nanos (constantly 0)
                    cu/start-daemon! (fn [& argv] (reset! started argv))]
        (ps/start! (assoc opts :page-stats-interval 1)))
      (is (= args (when @started (vec (drop-while #(not= "undo" %) @started)))))
      (is (= (some? args) (some? @started))))))
