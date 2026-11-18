(ns jepsen.orioledb.page-stats-test
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.test :refer :all]
            [jepsen.checker :as checker]
            [jepsen.control :as c]
            [jepsen.control.util :as cu]
            [jepsen.db :as db]
            [jepsen.orioledb.db :as odb]
            [jepsen.orioledb.evictions :as trace]
            [jepsen.orioledb.page-stats :as ps]
            [jepsen.store :as store])
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
              (is (= "PNG" (subs (slurp file :encoding "ISO-8859-1") 1 4)))))))
      (finally (doseq [file (reverse (file-seq dir))] (io/delete-file file))))))

(deftest disabled-empty-and-unavailable-do-not-fabricate-plots
  (let [renders (atom [])]
    (with-redefs [store/path (fn [& _] "unused")
                  jepsen.checker.perf/plot! #(swap! renders conj %)
                  ps/read-samples (constantly [])]
      (is (= {:valid? true :disabled true}
             (checker/check (ps/plot-checker) {:engine :postgres :page-stats-interval 1} [] {})))
      (is (= {:valid? true}
             (checker/check (ps/plot-checker) {:engine :orioledb :page-stats-interval 1 :nodes ["n1"]} [] {})))
      (is (empty? @renders)))
    (with-redefs [store/path (fn [& _] "unused")
                  jepsen.checker.perf/plot! #(swap! renders conj %)
                  ps/read-samples (constantly [{:elapsed 1 :available false :pools []}])]
      (ps/plot! {:nodes ["n1"]})
      (is (empty? @renders)))))

(deftest nemesis-wrapper-cleans-up-on-success-and-start-failure
  (doseq [fail-start? [false true]]
    (let [events (atom [])
          delegate (reify jepsen.nemesis/Nemesis
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
                               (jepsen.nemesis/setup! (ps/wrap-nemesis delegate) test)))
          (let [ready (jepsen.nemesis/setup! (ps/wrap-nemesis delegate) test)]
            (is (= {:f :pause} (jepsen.nemesis/invoke! ready test {:f :pause})))
            (jepsen.nemesis/teardown! ready test)))
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
        buckets (reduce #(trace/record-marker %1 (bounds (:node %2)) %2) {} events)]
    (is (= 11.25 (:elapsed undo)))
    (is (= [42 "n1" "row" "inplace_rewrite" 10 30 20]
           ((juxt :pid :node :undo_type :reason :from :to :bytes) undo)))
    (is (= 17 (:candidate_blkno (evict "01.250"))))
    (doseq [payload ["Wrote plain disk page: checkpoint 7" "orioledb_trace event=did_write"
                     "prefix orioledb_trace event=btree_evict pool=main candidate_blkno=17"]]
      (is (nil? (parse "02.000" payload "n1"))))
    (is (= [[[11.25 0]] []] (mapv :data (trace/pool-strips buckets "main"))))
    (is (= [[] [[11.29 0]]] (mapv :data (trace/pool-strips buckets "free_tree"))))
    (is (= [[] []] (mapv :data (trace/pool-strips buckets "catalog"))))))

(deftest undo-tracks-have-stable-offsets-and-group-by-type-and-reason
  (let [reasons (map first trace/undo-styles)
        events (for [type trace/undo-types reason reasons second [3.25 3.75]]
                 {:undo_type type :reason reason :elapsed second})
        buckets (reduce #(trace/record-marker %1 [0 5] %2) {} (reverse events))
        series (trace/undo-series buckets)]
    (is (= (vec reasons) (mapv :title series)))
    (is (= 5 (count (set (map :pointtype series)))))
    (doseq [{:keys [data]} series]
      (is (= [3.25 3.25 3.25] (mapv first data))))
    (is (= 15 (count (set (mapcat #(map second (:data %)) series)))))
    (is (= [[3.25 0.3] [3.25 1.3] [3.25 2.3]] (:data (last series))))))

(deftest trace-config-is-global-and-engine-specific
  (is (re-find #"log_min_messages = debug1"
               (odb/cluster-config {:engine :orioledb :trace-evictions true})))
  (is (re-find #"log_timezone = 'UTC'"
               (odb/cluster-config {:trace-evictions true})))
  (doseq [test [{} {:engine :postgres :trace-evictions true}]]
    (is (not (re-find #"debug1" (odb/cluster-config test))))))
