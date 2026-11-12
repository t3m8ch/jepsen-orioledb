(ns jepsen.orioledb.page-stats-test
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.test :refer :all]
            [jepsen.checker :as checker]
            [jepsen.control :as c]
            [jepsen.control.util :as cu]
            [jepsen.db :as db]
            [jepsen.orioledb.db :as odb]
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

(deftest renders-real-pngs-without-affecting-verdict
  (let [dir (.toFile (Files/createTempDirectory "page-stats-test" (make-array java.nio.file.attribute.FileAttribute 0)))
        path (fn [_ & parts] (apply io/file dir parts))
        test {:engine :orioledb :page-stats-interval 1 :trace-page-writes true :nodes ["n1"]}]
    (try
      (io/make-parents (path test "n1" "page-stats.jsonl"))
      (spit (path test "n1" "page-stats.jsonl")
            (apply str (map #(str (json/generate-string (assoc % :timestamp "2026-11-12T12:00:00Z")) "\n") snapshots)))
      (spit (path test "n1" "postgresql.log")
            "2026-11-12 12:00:01.250 UTC [42] DEBUG:  Wrote plain disk page: checkpoint 7\n")
      (with-redefs [store/path path store/path! path]
        (is (= {:valid? true} (checker/check (ps/plot-checker) test [] {})))
        (is (= 1 (count (ps/read-samples (path test "page-writes.jsonl")))))
        (doseq [pool ["main" "free-tree" "catalog"]]
          (let [file (path test (str "page-stats-" pool ".png"))]
            (is (> (.length file) 1000))
            (is (= "PNG" (subs (slurp file :encoding "ISO-8859-1") 1 4)))))
        (spit (path test "n1" "page-stats.jsonl") "")
        (is (= {:valid? true} (checker/check (ps/plot-checker) test [] {}))))
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

(deftest page-write-observations-and-alignment
  (let [anchor {:timestamp "2026-11-12T12:00:00Z" :elapsed 10}
        parse #(ps/parse-write % "n1" anchor)
        plain (parse "2026-11-12 12:00:01.250 UTC [42] DEBUG:  Wrote plain disk page: checkpoint 7")
        compressed (parse "2026-11-12 12:00:02.000 UTC [99] DEBUG:  Wrote disk page: checkpoint 8 size 4096")]
    (is (= 11.25 (:elapsed plain)))
    (is (= 42 (:pid plain)))
    (is (= 7 (:checkpoint plain)))
    (is (nil? (:size plain)))
    (is (= 4096 (:size compressed)))
    (is (= 99 (:pid compressed)))
    (doseq [line ["" "Wrote plain disk page: checkpoint 7"
                  "2026-11-12 12:00:02.000 UTC [99] DEBUG:  Wrote disk page: checkpoint 8"
                  "2026-11-12 12:00:02.000 UTC [99] DEBUG:  unrelated"]]
      (is (nil? (parse line))))
    (is (= [[11.25 1]]
           (:data (ps/marker-series [plain plain compressed]
                                    [{:elapsed 11} {:elapsed 11.9}]))))
    (is (empty? (:data (ps/marker-series [] [{:elapsed 0}]))))))

(deftest trace-config-is-global-and-engine-specific
  (is (re-find #"log_min_messages = debug1"
               (odb/cluster-config {:engine :orioledb :trace-page-writes true})))
  (is (re-find #"log_timezone = 'UTC'"
               (odb/cluster-config {:trace-page-writes true})))
  (doseq [test [{} {:engine :postgres :trace-page-writes true}]]
    (is (not (re-find #"debug1" (odb/cluster-config test))))))

(deftest annotations-preserve-occupancy-series
  (let [plots (atom [])
        dir (.toFile (Files/createTempDirectory "write-markers" (make-array java.nio.file.attribute.FileAttribute 0)))]
    (try
      (with-redefs [store/path (fn [& _] "unused")
                    store/path! (fn [_ file] (io/file dir file))
                    ps/read-samples (constantly snapshots)
                    ps/write-events (constantly [{:elapsed 1.25} {:elapsed 1.75}])
                    jepsen.checker.perf/plot! #(swap! plots conj %)]
        (ps/plot! {:trace-page-writes true :nodes ["n1"]})
        (is (= 3 (count @plots)))
        (doseq [plot @plots]
          (is (= 4 (count (:series plot))))
          (is (= [[1.25 108.0]] (:data (last (:series plot)))))
          (is (re-find #"Observed page-write attempts" (:title (last (:series plot)))))))
      (finally (doseq [file (reverse (file-seq dir))] (io/delete-file file))))))
