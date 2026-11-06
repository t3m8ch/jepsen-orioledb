(ns jepsen.sql.encoding-test
  (:require [clojure [test :refer :all]]
            [clojure.test.check [generators :as g]]
            [com.gfredericks.test.chuck.clojure-test :refer [checking]]
            [jepsen.sql.encoding :refer :all]))

(defn roundtrip
  [encoding k x]
  (let [x' (->> x
                ((:encode encoding) k)
                ((:simulate-db encoding identity))
                ((:decode encoding) k))]
    (is (= (type x) (type x')))
    (is (= x x'))))

(def integer
  ; We have never gotten anywhere near to this close as many keys, and this means we can safely pack into floats etc.
  (g/large-integer* {:min -16777216 :max 16777216}))

(deftest roundtrip-test
  (checking "roundtrip" 1600
            [encoding   (g/elements all-encodings)
             k          integer
             x          integer]
            (roundtrip encoding k x)))
