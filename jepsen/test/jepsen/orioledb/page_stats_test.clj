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
        test {:engine :orioledb :page-stats-interval 1 :nodes ["n1"]}]
    (try
      (io/make-parents (path test "n1" "page-stats.jsonl"))
      (spit (path test "n1" "page-stats.jsonl")
            (apply str (map #(str (json/generate-string %) "\n") snapshots)))
      (with-redefs [store/path path store/path! path]
        (is (= {:valid? true} (checker/check (ps/plot-checker) test [] {})))
        (doseq [pool ["main" "free-tree" "catalog"]]
          (let [file (path test (str "page-stats-" pool ".png"))]
            (is (> (.length file) 1000))
            (is (= "PNG" (subs (slurp file :encoding "ISO-8859-1") 1 4)))))
        (spit (path test "n1" "page-stats.jsonl") "")
        (is (= {:valid? true} (checker/check (ps/plot-checker) test [] {}))))
      (finally (doseq [file (reverse (file-seq dir))] (io/delete-file file))))))
