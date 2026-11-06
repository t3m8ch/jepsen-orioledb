(ns jepsen.sql.encoding
  "Maps values to and from SQL types in various ways.

  This namespace right now is for encoding single integers. Each encoding is a
  map of the form

  {:name    A unique keyword identifier
   :type    The SQL type used for creating columns
   :encode  A function (encode k x) -> x', which transforms a long before it's
            handed to next.jdbc.
   :decode  A function (decode k x') -> x, which transforms back from the DB
            representation to a long. Nil should be passed through unchanged.
   :simulate-db Simulates what the DB will do to our values; used for testing."
  (:require [clj-commons.slingshot :refer [try+ throw+]]
            [clojure [string :as str]]
            [clojure.tools.logging :refer [info warn]]
            [jepsen [random :as rand]]
            [jepsen.sql [checker :refer [assert-instance-or-nil]]]))

(defn encode-string
  "Encodes a long as a string."
  [k x]
  (str x))

(defn decode-string
  "Decodes a string to a long."
  [k x]
  (assert-instance-or-nil String x)
  (when x
    (when-not (re-find #"^-?\d*$" x)
      (throw+ {:type :jepsen.sql/wrong-format
               :critical? true
               :expected "A series of digits"
               :actual   x}))
    (parse-long x)))

(def all-encodings
  "All encodings."
  [{:name  :integer
    :type  "integer"
    :encode (fn encode-integer [k x] x)
    :decode (fn decode-integer [k x]
              (when-not (or (nil? x)
                            (integer? x))
                (throw+ {:type :jepsen.sql/wrong-type
                         :critical? true
                         :expected "some kind of integer"
                         :actual   (class x)
                         :value    x}))
              (when x (long x)))}

   {:name :double-precision
    :type "double precision"
    :encode (fn encode-double-precision [k x]
              ; Gotta be within 2^24, otherwise floats will lose integer
              ; precision Double should be able to do 2^53, but let's be
              ; conservative
              (assert (< -16777216 x 16777216))
              x)
    :simulate-db double
    :decode (fn decode-double-precision [k x]
              (assert-instance-or-nil Double x)
              (when x (long x)))}

   {:name :decimal
    :type "decimal(32, 16)"
    :encode (fn encode-decimal [k x]
              ; We encode either before or after the decimal
              (if (even? (hash k))
                x
                ; -1230 -> -0.0321
                (let [x' (->> x Math/abs long str reverse str/join
                             (str "0.") bigdec)]
                  (if (neg? x)
                    (- x')
                    x'))))
    :simulate-db bigdec
    :decode (fn decode-decimal [k x]
              (assert-instance-or-nil BigDecimal x)
              (when x
                (if (even? (hash k))
                  (try (.longValueExact ^BigDecimal x)
                       (catch ArithmeticException e
                         (throw+ {:type :jepsen.sql/unexpected-fractional-part
                                  :critical? true
                                  :expected "A BigDecimal like 123.0M"
                                  :actual   x})))
                  (if-let [[m sign digits] (re-find #"^(-?)0.(\d+)$" (str x))]
                    (parse-long (str sign (str/join (reverse digits))))
                    (throw+ {:type :jepsen.sql/unexpected-integral-part
                             :critical? true
                             :expected "A BigDecimal like 0.321M"
                             :actual x})))))}

   ; char(n) fixed-width padded strings
   {:name :character
    :type "character(32)"
    :simulate-db (partial format "%-32s")
    :encode encode-string
    :decode (fn decode-character [k x]
              (when x
                (decode-string k (str/trim x))))}

   ; character varying(n) variable-width strings
   {:name :character-varying
    :type "character varying(32)"
    :encode encode-string
    :decode decode-string}

   ; text columns
   {:name   :text
    :type   "text"
    :encode encode-string
    :decode decode-string}])

(defn encoding
  "Looks up an encoding by name."
  [name]
  (first (filter (comp #{name} :name) all-encodings)))

(defn encodings
  "All allowed encodings for a test. Shuffled each time."
  [test]
  (let [names (set (:encodings test (mapv :name all-encodings)))]
    (assert (seq names) "No encodings provided")
    (let [encodings (filterv (comp names :name) all-encodings)]
      (when (not= (count names)
                  (count encodings))
        (throw (IllegalArgumentException. (str "Unrecognized encoding names"
                                               (pr-str names)))))
      (rand/shuffle encodings))))
