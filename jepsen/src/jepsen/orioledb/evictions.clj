(ns jepsen.orioledb.evictions
  "Parse the opt-in OrioleDB trace and prepare time-aligned event markers."
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [jepsen.store :as store])
  (:import (java.time Duration Instant)
           (java.time.format DateTimeParseException)))

(def undo-types ["row" "page" "system"])
(def undo-styles
  ;; Fixed order gives each reason a stable track offset, color and shape.
  [["bgwriter" "#2a78d6" 7]
   ["checkpoint_pressure" "#eb6834" 5]
   ["backend_opportunistic" "#1baf7a" 9]
   ["backend_reserve" "#96295c" 13]
   ["inplace_rewrite" "#ad50b6" 3]])

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

(defn record-marker [buckets bounds {:keys [event pool undo_type reason elapsed]}]
  (if (and elapsed bounds (<= (first bounds) elapsed (second bounds)))
    (let [series (if pool [event pool] [undo_type reason])
          second (long (Math/floor elapsed))]
      (update-in buckets [series second] #(if % (min % elapsed) elapsed)))
    buckets))

(defn export! [test samples-by-node]
  ;; Stream every record to disk. Only the earliest marker per series and second
  ;; needs to stay in memory, even when the raw logs contain millions of events.
  (with-open [w (io/writer (store/path! test "evictions.jsonl"))]
    (reduce (fn [buckets [node samples]]
              (let [log (io/file (store/path test node "postgresql.log"))
                    anchor (first samples)
                    bounds (sample-range samples)]
                (if (.isFile log)
                  (with-open [r (io/reader log)]
                    (reduce (fn [buckets line]
                              (if-let [event (parse-event line node anchor)]
                                (do (.write w (str (json/generate-string event) "\n"))
                                    (record-marker buckets bounds event))
                                buckets))
                            buckets (line-seq r)))
                  buckets)))
            {} samples-by-node)))

(defn marker-times [buckets series]
  (sort (vals (get buckets series))))

(defn pool-strips [buckets pool]
  (mapv (fn [[event label]]
          {:title label :data (mapv #(vector % 0) (marker-times buckets [event pool]))})
        [["btree_evict" "Evictions"]
         ["btree_writeback" "Writeback without eviction"]]))

(defn undo-series [buckets]
  (mapv (fn [reason-index [reason color shape]]
          {:title reason :with 'points :linecolor color :pointtype shape
           :data (into []
                       (mapcat (fn [type-index type]
                                 (let [y (+ type-index (* 0.15 (- reason-index 2)))]
                                   (map #(vector % y) (marker-times buckets [type reason]))))
                               (range) undo-types))})
        (range) undo-styles))
