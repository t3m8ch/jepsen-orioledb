(ns jepsen.orioledb.evictions
  "Parse the opt-in OrioleDB trace and prepare time-aligned event counts."
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [jepsen.store :as store])
  (:import (java.time Duration Instant)
           (java.time.format DateTimeParseException)))

(def undo-types ["row" "page" "system"])
(def undo-styles
  ;; Fixed order gives each reason a stable stacking order and color.
  [["bgwriter" "#2a78d6"]
   ["checkpoint_pressure" "#eb6834"]
   ["backend_opportunistic" "#1baf7a"]
   ["backend_reserve" "#96295c"]
   ["inplace_rewrite" "#ad50b6"]])

(def trace-prefix
  #"^(\d{4}-\d\d-\d\d \d\d:\d\d:\d\d\.\d+ UTC) \[(\d+)\] DEBUG:  orioledb_trace (.*)$")
(def btree-pattern
  #"event=(btree_evict|btree_writeback) pool=(main|free_tree|catalog) candidate_blkno=(\d+)")
(def undo-pattern
  #"event=undo_ring_evict undo_type=(row|page|system) reason=(bgwriter|checkpoint_pressure|backend_opportunistic|backend_reserve|inplace_rewrite) from=(\d+) to=(\d+) bytes=(\d+)")

(defn parse-payload [payload]
  (if-let [[_ event pool blkno] (re-matches btree-pattern payload)]
    {:event event :pool pool :candidate_blkno (bigint blkno)}
    (when-let [[_ type reason from to bytes] (re-matches undo-pattern payload)]
      {:event "undo_ring_evict" :undo_type type :reason reason
       :from (bigint from) :to (bigint to) :bytes (bigint bytes)})))

(defn elapsed-at [anchor instant]
  (when (and (:timestamp anchor) (number? (:elapsed anchor)))
    (try
      (+ (:elapsed anchor)
         (/ (.toNanos (Duration/between (Instant/parse (:timestamp anchor)) instant)) 1e9))
      (catch DateTimeParseException _ nil))))

(defn parse-event [line node anchor]
  (when-let [[_ timestamp pid payload] (re-matches trace-prefix line)]
    (when-let [event (parse-payload payload)]
      (try
        (let [instant (Instant/parse (str (str/replace (subs timestamp 0 (- (count timestamp) 4)) " " "T") "Z"))]
          (assoc event :timestamp (str instant) :elapsed (elapsed-at anchor instant)
                 :node node :pid (parse-long pid)))
        (catch DateTimeParseException _ nil)))))

(defn sample-range [samples]
  (when-let [times (seq (keep :elapsed samples))]
    [(reduce min times) (reduce max times)]))

(defn record-event [buckets bounds {:keys [event pool undo_type reason elapsed]}]
  (if (and elapsed bounds (<= (first bounds) elapsed (second bounds)))
    (let [series (if pool [event pool] [undo_type reason])
          second (long (Math/floor elapsed))]
      (update-in buckets [:counts series second] (fnil inc 0)))
    buckets))

(defn- export-node! [test writer buckets [node samples]]
  (let [log (store/path test node "postgresql.log")
        anchor (first samples)
        bounds (sample-range samples)]
    (if (.isFile log)
      (with-open [reader (io/reader log)]
        (reduce (fn [buckets line]
                  (if-let [event (parse-event line node anchor)]
                    (do
                      (.write ^java.io.Writer writer
                              (str (json/generate-string event) "\n"))
                      (record-event buckets bounds event))
                    buckets))
                (update buckets :coverage conj (when (and (number? (:elapsed anchor))
                                                          (try (Instant/parse (or (:timestamp anchor) ""))
                                                               (catch DateTimeParseException _ nil))) bounds))
                (line-seq reader)))
      (update buckets :coverage conj nil))))

(defn export! [test samples-by-node]
  ;; Stream every record to disk. Only the count per series and second
  ;; needs to stay in memory, even when the raw logs contain millions of events.
  (with-open [writer (io/writer (store/path! test "evictions.jsonl"))]
    (reduce (fn [buckets node-samples]
              (export-node! test writer buckets node-samples))
            {:counts {} :coverage []}
            samples-by-node)))

(defn complete-seconds
  "Whole seconds covered by every node's event log and sample window."
  [{:keys [coverage]}]
  (when (and (seq coverage) (every? some? coverage))
    (range (long (Math/ceil (reduce max (map first coverage))))
           (long (Math/floor (reduce min (map second coverage)))))))

(defn pool-counts [{:keys [counts]} pool]
  (mapv (fn [[event label]]
          {:title label :counts (get counts [event pool])})
        [["btree_evict" "Evictions"]
         ["btree_writeback" "Writeback without eviction"]]))

(defn undo-counts [{:keys [counts]} type]
  (mapv (fn [[reason color]]
          {:title reason :linecolor color :counts (get counts [type reason])})
        undo-styles))
