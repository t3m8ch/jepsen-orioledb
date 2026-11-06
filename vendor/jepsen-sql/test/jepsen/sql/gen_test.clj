(ns jepsen.sql.gen-test
  (:require [clojure [pprint :refer [pprint]]
                     [test :refer :all]]
            [clojure.test.check.generators :as g]
            [com.gfredericks.test.chuck.clojure-test :refer [checking]]
            [jepsen [random :as rand]]
            [jepsen.sql [ast :as ast :refer
                         [schema
                          table
                          column
                          column-name
                          text-type
                          boolean-type
                          integer-type
                          sql
                          setup
                          teardown]]
                        [gen :refer :all]]))

(deftest gen-schema-test
  (is (= (schema
           [(table "t"
                   [(column "c" text-type {:primary-key? true})
                    (column "b" boolean-type)
                    (column "a" integer-type)])
            (table "u"
                   [(column "b" integer-type {:primary-key? true})])]
           {:longs [1 -1]
            :doubles [-3.0 -2.0]
            :strings ["Yy"]})
         (g/generate (gen-schema {:max-table-count  3
                                  :max-column-count 3})
                     2 123))))

(deftest gen-case-test
  (rand/with-seed 0
    (let [case (generate {:max-statement-count 1024})]
      (is (= [["CREATE TABLE t (b TEXT collate \"en_US.utf8\" PRIMARY KEY, a TEXT collate \"en_US.utf8\", c TEXT collate \"en_US.utf8\", d BOOLEAN)"]
              ["CREATE TABLE v (a BOOLEAN, c INTEGER PRIMARY KEY, b INTEGER, d BOOLEAN)"]
              ["CREATE TABLE u (d INTEGER)"]]
             (mapv sql (setup case))))

      (is (= [["DROP TABLE t"] ["DROP TABLE v"] ["DROP TABLE u"]]
             (mapv sql (teardown case))))
      ; You're meant to replace this when the generators change; the idea is to
      ; look through the history by hand and make sure it seems reasonable;
      ; you're getting a nice blend of statements and predicates, etc.
      (is (= [["SELECT * FROM v"]
           ["SELECT * FROM v"]
           ["DELETE FROM v WHERE ?" true]
           ["SELECT * FROM v WHERE ((d < ?) AND (d OR (? = d)) AND ((d OR ? OR ? OR a) OR (b <= c)) AND a)"
            true
            false
            true
            true]
           ["INSERT INTO v (a, c, b, d) VALUES (?, ?, ?, ?)"
            nil
            125
            -3545
            false]
           ["DELETE FROM t"]
           ["SELECT * FROM v"]
           ["SELECT * FROM u WHERE (? < ?)" 1231 850848]
           ["INSERT INTO u (d) VALUES (?)" -163662]
           ["INSERT INTO u (d) VALUES (?)" -24]
           ["UPDATE v SET a = ?, b = ?, d = ?, c = ? WHERE (c <> ?)"
            true
            3334
            nil
            287693
            -27283]
           ["INSERT INTO v (a, c, b, d) VALUES (?, ?, ?, ?)"
            nil
            -50026
            -1831
            true]
           ["INSERT INTO v (d, c, a, b) VALUES (?, ?, ?, ?)"
            true
            -922880
            nil
            -25]
           ["SELECT * FROM v WHERE ?" nil]
           ["UPDATE v SET a = ?" nil]
           ["INSERT INTO v (a, b, c, d) VALUES (?, ?, ?, ?)"
            true
            31832
            -852274519
            false]
           ["INSERT INTO v (d, c, a, b) VALUES (?, ?, ?, ?)"
            true
            6171078
            true
            -6467835]
           ["UPDATE u SET d = ?" 1231]
           ["DELETE FROM u WHERE ((? <> d) AND (NOT ?) AND (NOT (d <> ?)))"
            -27283
            nil
            125]
           ["SELECT * FROM t"]
           ["INSERT INTO t (c, d, b, a) VALUES (?, ?, ?, ?)"
            "PRF"
            false
            "zir"
            "y1IkQ8c7Zn8HUHMVFAz"]
           ["SELECT * FROM v WHERE a"]
           ["INSERT INTO u (d) VALUES (?)" 1231]
           ["SELECT * FROM u"]
           ["UPDATE v SET a = ?, b = ? WHERE (b <= ?)" false -27283 0]
           ["INSERT INTO t (c, a, b, d) VALUES (?, ?, ?, ?)"
            "2mkfdN0F9Me"
            "mx8bqpvo16mUWz12511u5C"
            "5"
            nil]
           ["UPDATE t SET b = ?, d = ? WHERE (NOT ?)" "PRF" true false]
           ["SELECT * FROM v"]
           ["UPDATE v SET a = ?, b = ?, d = ? WHERE ?"
            false
            -2
            true
            true]
           ["INSERT INTO v (a, c, b, d) VALUES (?, ?, ?, ?)"
            true
            -27283
            15396517
            true]
           ["SELECT * FROM t WHERE (b = b)"]
           ["SELECT * FROM t"]
           ["SELECT * FROM u WHERE ?" nil]
           ["INSERT INTO u (d) VALUES (?)" -108]
           ["UPDATE t SET b = ?, a = ?, c = ?, d = ? WHERE (d >= (NOT d))"
            "2"
            "PRF"
            "h1tg"
            nil]
           ["SELECT * FROM u"]
           ["INSERT INTO u (d) VALUES (?)" 125]
           ["SELECT * FROM v WHERE ((? = d) < ?)" nil true]
           ["INSERT INTO t (b, d, c, a) VALUES (?, ?, ?, ?)"
            "2"
            nil
            "LyEtZ2GoK6iSf7xna57lKdI9rtOv2A9"
            "x2w8wHbPh4E"]
           ["INSERT INTO u (d) VALUES (?)" -45]
           ["SELECT * FROM t"]
           ["INSERT INTO t (b, a, d, c) VALUES (?, ?, ?, ?)"
            "HjtJ9wOVC8UXWBllWu"
            "bVXje3KO"
            true
            "kc20hMy7oRCEXYBvKFem"]
           ["SELECT * FROM v WHERE a"]
           ["UPDATE v SET c = ?" 0]
           ["SELECT * FROM u WHERE (NOT ((? <= ?) AND ? AND (? = d) AND (? >= d)))"
            -27283
            -3
            true
            34587296
            -510362]
           ["INSERT INTO t (d, b, a, c) VALUES (?, ?, ?, ?)"
            nil
            "zir"
            "zir"
            "7OlbW"]
           ["UPDATE v SET b = ? WHERE a" 125]
           ["INSERT INTO t (c, d, b, a) VALUES (?, ?, ?, ?)"
            "PRF"
            nil
            "2"
            "xDGIyU1OgNz9kk9dKQXzwd59UspZn"]
           ["SELECT * FROM v WHERE ?" false]
           ["INSERT INTO v (b, d, a, c) VALUES (?, ?, ?, ?)"
            125
            true
            false
            132260549]
           ["UPDATE t SET c = ?, d = ? WHERE ?" "QMw8" false false]
           ["INSERT INTO t (a, b, c, d) VALUES (?, ?, ?, ?)"
            "zir"
            "gcy5qN93ps87qopkhTZPav2Pfvcv"
            "5mADgczGNQ4WCtVIt7lBbEhoi3Y"
            false]
           ["SELECT * FROM t"]
           ["SELECT * FROM u WHERE (? = d)" 1231]
           ["INSERT INTO u (d) VALUES (?)" 1231]
           ["UPDATE u SET d = ? WHERE ((? <> d) OR (? >= d) OR (d <= ?) OR ?)"
            -27283
            -29083
            94344102
            1231
            true]
           ["UPDATE u SET d = ? WHERE ?" 125 nil]
           ["SELECT * FROM t WHERE d"]
           ["UPDATE u SET d = ? WHERE ?" 1231 false]
           ["UPDATE u SET d = ? WHERE ?" 817216526 nil]
           ["SELECT * FROM t WHERE (? = ? COLLATE \"en_US.utf8\")"
            "DhSVhp6rTumI7I0PyES5yEX6mvu"
            "2"]
           ["SELECT * FROM u WHERE ((? <= ?) OR ((? AND ?) OR ?))"
            -27283
            -27283
            true
            nil
            false]
           ["SELECT * FROM t"]
           ["UPDATE u SET d = ?" 369873]
           ["SELECT * FROM t WHERE ?" nil]
           ["UPDATE v SET a = ?, b = ?, c = ?, d = ? WHERE (((d <> ?) AND ? AND (b < c) AND (c <> ?)) OR ? OR ? OR ?)"
            nil
            -27283
            -27283
            nil
            nil
            nil
            -27283
            nil
            nil
            true]
           ["DELETE FROM t WHERE ((d >= ?) = (b >= ?))" true "2"]
           ["INSERT INTO u (d) VALUES (?)" -1]
           ["UPDATE t SET c = ?, b = ?, d = ?, a = ? WHERE (NOT d)"
            "noYEsigZxVLkuAMqnq"
            "2"
            true
            "zoXJuElg860ytTBEr"]
           ["SELECT * FROM t WHERE d"]
           ["SELECT * FROM t WHERE (c > b)"]
           ["SELECT * FROM u"]
           ["SELECT * FROM t WHERE ((d OR d OR d) <> (d OR ? OR d))"
            true]
           ["SELECT * FROM v WHERE (? OR ((c < ?) OR (? = a) OR (? OR ?) OR (c = ?)) OR ? OR (? > ?))"
            false
            405
            true
            true
            false
            -4
            true
            false
            nil]
           ["INSERT INTO v (a, c, b, d) VALUES (?, ?, ?, ?)"
            false
            -27283
            -1617527243
            false]
           ["INSERT INTO u (d) VALUES (?)" -27283]
           ["SELECT * FROM v WHERE (((a OR d OR d) AND (NOT a) AND (d < a) AND (NOT ?)) OR ((? < a) AND ?))"
            nil
            false
            false]
           ["SELECT * FROM u WHERE (d = d)"]
           ["INSERT INTO t (a, d, c, b) VALUES (?, ?, ?, ?)"
            "PRF"
            nil
            "PRF"
            "zir"]
           ["INSERT INTO u (d) VALUES (?)" 1022]
           ["UPDATE t SET a = ?, c = ? WHERE d" "0tz0SkaBJwC" "2"]
           ["SELECT * FROM u WHERE ((NOT (d < ?)) AND ?)" 1231 nil]
           ["INSERT INTO v (c, a, b, d) VALUES (?, ?, ?, ?)"
            125
            false
            -27283
            true]
           ["SELECT * FROM v WHERE ?" false]
           ["SELECT * FROM u WHERE ?" true]
           ["INSERT INTO t (b, d, c, a) VALUES (?, ?, ?, ?)"
            "zir"
            true
            "zir"
            "2"]
           ["INSERT INTO t (d, c, b, a) VALUES (?, ?, ?, ?)"
            true
            "G7hKoPyMNkoTnQeVFWunlXRsMklnT"
            "uLk1sbcK9b77i9EVGTJQjhEy3L2KeKLeG"
            "zir"]
           ["UPDATE t SET a = ?, d = ?, b = ?, c = ? WHERE (NOT d)"
            "7aDUiUmG"
            nil
            "zir"
            "T8MexcirY1FsfuUV6YFCNogBwyTrSb2QG"]
           ["UPDATE u SET d = ? WHERE (d <= ?)" -23 -24884]
           ["SELECT * FROM v"]
           ["SELECT * FROM u WHERE (? <> d)" 7]
           ["INSERT INTO v (b, d, c, a) VALUES (?, ?, ?, ?)"
            -132082363
            nil
            -7595
            true]
           ["SELECT * FROM v WHERE (d = (a <> a))"]
           ["SELECT * FROM t WHERE d"]
           ["UPDATE u SET d = ?" 1231]
           ["INSERT INTO v (c, a, d, b) VALUES (?, ?, ?, ?)"
            -27283
            false
            false
            1231]
           ["SELECT * FROM v WHERE (c = ?)" 1231]
           ["INSERT INTO u (d) VALUES (?)" 1231]
           ["SELECT * FROM u WHERE ?" nil]
           ["SELECT * FROM u WHERE (? OR (d <> d) OR (d < ?))"
            true
            125]
           ["INSERT INTO u (d) VALUES (?)" 449461560]
           ["SELECT * FROM u"]
           ["SELECT * FROM u"]
           ["UPDATE u SET d = ? WHERE (d <> d)" -6395]
           ["SELECT * FROM t WHERE ?" nil]
           ["UPDATE t SET c = ?, a = ? WHERE ((? >= c) = d)"
            "2"
            "rqfakvZ4"
            "zir"]
           ["SELECT * FROM u WHERE (? <> ?)" 5554897 203]
           ["INSERT INTO t (c, a, b, d) VALUES (?, ?, ?, ?)"
            "PRF"
            "2"
            "4tyx5kvPOMCdchploFytQPg0ismgsf"
            nil]
           ["INSERT INTO v (c, a, d, b) VALUES (?, ?, ?, ?)"
            -39254068
            false
            false
            1231]
           ["SELECT * FROM t WHERE ((? = ?) <> (NOT ?))"
            false
            nil
            true]
           ["INSERT INTO v (b, a, c, d) VALUES (?, ?, ?, ?)"
            -27283
            false
            -29
            nil]
           ["UPDATE v SET b = ?, d = ?, a = ? WHERE (NOT d)"
            125
            true
            false]
           ["SELECT * FROM t WHERE ?" nil]
           ["SELECT * FROM u WHERE ?" nil]
           ["INSERT INTO v (d, c, a, b) VALUES (?, ?, ?, ?)"
            false
            -27283
            nil
            125]
           ["UPDATE u SET d = ? WHERE (? <> d)" 125 -9078]
           ["SELECT * FROM t WHERE ((a <> a) AND d AND (NOT d) AND (d AND (d OR d)))"]
           ["INSERT INTO v (b, c, a, d) VALUES (?, ?, ?, ?)"
            1231
            125
            true
            nil]
           ["INSERT INTO t (c, d, b, a) VALUES (?, ?, ?, ?)"
            "zir"
            false
            "r6GRswgg831sxZtm1T9GvtW"
            "zir"]
           ["INSERT INTO u (d) VALUES (?)" 1231]
           ["SELECT * FROM v WHERE (? < b)" 952637999]
           ["UPDATE t SET b = ?" "W9rpesHJjZp"]
           ["INSERT INTO v (b, a, c, d) VALUES (?, ?, ?, ?)"
            -7
            nil
            1231
            false]
           ["SELECT * FROM u WHERE (? < d)" 49152243]
           ["SELECT * FROM u WHERE ((d <= ?) OR ((NOT ?) AND (NOT ?) AND (NOT ?) AND (NOT ?)) OR ((? <= ?) AND (? OR ? OR ? OR ?) AND (? < d) AND ?) OR (? <= d))"
            -17746756
            false
            true
            true
            true
            -27283
            389608
            nil
            false
            false
            false
            125
            false
            -30]
           ["SELECT * FROM u WHERE (? > ?)" 125 -409521]
           ["INSERT INTO t (a, d, c, b) VALUES (?, ?, ?, ?)"
            "MIGjTUynBmYA4"
            false
            "EPFcuWJlMO7wXMoRJJV58HP"
            "PRF"]
           ["SELECT * FROM v WHERE (? AND (a = a))" false]
           ["UPDATE t SET c = ?, b = ?, a = ?, d = ?"
            "jFkZvmE75RkUrz2N7vQ"
            "2"
            "zir"
            false]
           ["UPDATE t SET c = ?, d = ? WHERE ?" "zir" false false]
           ["INSERT INTO v (b, d, c, a) VALUES (?, ?, ?, ?)"
            -936068120
            true
            1231
            false]
           ["UPDATE t SET b = ?, a = ?, d = ?, c = ? WHERE (d > (d < d))"
            "PRF"
            "PRF"
            nil
            "xuxW5uN7FTALxALBWo"]
           ["UPDATE t SET b = ?, a = ? WHERE ((? <= d) > (? OR ? OR ?))"
            "PRF"
            "MEmrFIdUKkMOlaFL1lxcwU"
            false
            true
            true
            true]
           ["UPDATE v SET a = ?, d = ? WHERE (? >= (c <> ?))"
            nil
            false
            true
            -27283]
           ["INSERT INTO v (d, a, b, c) VALUES (?, ?, ?, ?)"
            true
            true
            -27283
            -162072688]
           ["INSERT INTO u (d) VALUES (?)" 1231]
           ["INSERT INTO t (c, a, b, d) VALUES (?, ?, ?, ?)"
            "nRBGJ"
            "zir"
            "gyYDTtFcIWPBCUyOx3CK"
            true]
           ["INSERT INTO v (a, b, c, d) VALUES (?, ?, ?, ?)"
            false
            1231
            125
            nil]
           ["UPDATE u SET d = ? WHERE (? > ?)" -27283 -20780901 -27283]
           ["SELECT * FROM v WHERE ?" nil]
           ["SELECT * FROM v WHERE ((b >= b) <= (NOT ?))" false]
           ["INSERT INTO v (d, c, a, b) VALUES (?, ?, ?, ?)"
            nil
            -27283
            true
            4802]
           ["SELECT * FROM v"]
           ["INSERT INTO t (b, d, c, a) VALUES (?, ?, ?, ?)"
            "2"
            false
            "qHst6izefOnoI41p"
            "VZqD"]
           ["INSERT INTO u (d) VALUES (?)" 0]
           ["INSERT INTO v (a, b, c, d) VALUES (?, ?, ?, ?)"
            false
            3618
            6
            true]
           ["SELECT * FROM t"]
           ["UPDATE t SET c = ?, b = ? WHERE ((? OR (NOT d) OR (? AND ?) OR (d >= d)) OR (? >= d) OR d)"
            "U1JVhzNZywG9X7p"
            "PJh8a4vkd55K2uYJ2iO8199SWfEUEL3KH"
            true
            false
            nil
            true]
           ["SELECT * FROM u WHERE (NOT ?)" false]
           ["SELECT * FROM u WHERE (NOT (? >= d))" 1231]
           ["SELECT * FROM v"]
           ["SELECT * FROM v WHERE (? OR (? = b))" true 3613]
           ["SELECT * FROM v WHERE (b < c)"]
           ["INSERT INTO t (b, a, d, c) VALUES (?, ?, ?, ?)"
            "YI9u2XLAAZwXpDv5N4T4WQh4H"
            "zir"
            nil
            "zir"]
           ["SELECT * FROM t WHERE ((? OR ? OR ? OR d) < (c <= b))"
            true
            nil
            true]
           ["UPDATE u SET d = ? WHERE ?" 6480 true]
           ["UPDATE u SET d = ? WHERE ?" 125 false]
           ["UPDATE u SET d = ? WHERE ?" 125 false]
           ["SELECT * FROM u"]
           ["INSERT INTO v (b, c, d, a) VALUES (?, ?, ?, ?)"
            125
            1231
            false
            nil]
           ["INSERT INTO v (d, a, c, b) VALUES (?, ?, ?, ?)"
            nil
            true
            1231
            -891]
           ["DELETE FROM u"]
           ["SELECT * FROM t WHERE (? AND (? = ? COLLATE \"en_US.utf8\") AND d AND (d > d))"
            false
            "PRF"
            "eBK4kkz"]
           ["INSERT INTO t (b, d, c, a) VALUES (?, ?, ?, ?)"
            "2"
            nil
            "O"
            "hai4pZzR22Cfd1yPtaeXkMTHdFh"]
           ["SELECT * FROM v WHERE (b = c)"]
           ["SELECT * FROM t"]
           ["INSERT INTO t (b, c, a, d) VALUES (?, ?, ?, ?)"
            "5PfZ7TkBHuVQCBsiJ3d"
            "PRF"
            "zir"
            true]
           ["INSERT INTO t (b, a, c, d) VALUES (?, ?, ?, ?)"
            "XJu"
            "OlRJ5kMOR"
            "PRF"
            true]
           ["INSERT INTO t (d, a, b, c) VALUES (?, ?, ?, ?)"
            nil
            "M1ftOCwf3GLyS2GlvMDz2Cw"
            "NRzRqkg"
            "2"]
           ["SELECT * FROM t WHERE ?" true]
           ["SELECT * FROM t"]
           ["INSERT INTO u (d) VALUES (?)" 1231]
           ["UPDATE t SET b = ?, a = ?, d = ?, c = ? WHERE (d <= (? > ? COLLATE \"en_US.utf8\"))"
            "G85HxOlj7CjQnOZJ7w5dFywe"
            "Yc20BZ9nVs"
            true
            "zir"
            "PRF"
            "BqhTPWECdYITGSSf27AXXKcNh62ayRbt"]
           ["SELECT * FROM u WHERE ((NOT ?) AND (d > d))" false]
           ["DELETE FROM u"]
           ["SELECT * FROM t WHERE d"]
           ["INSERT INTO t (b, d, c, a) VALUES (?, ?, ?, ?)"
            "Y"
            nil
            "5Mkw6UNQnCW8xnwn"
            "YvXRQlvwb50"]
           ["DELETE FROM v WHERE ?" true]
           ["INSERT INTO u (d) VALUES (?)" 16533]
           ["INSERT INTO v (a, b, c, d) VALUES (?, ?, ?, ?)"
            nil
            1231
            1231
            false]
           ["UPDATE u SET d = ?" -27283]
           ["SELECT * FROM t WHERE d"]
           ["UPDATE v SET d = ?, a = ? WHERE (d <> (a OR a OR ?))"
            nil
            false
            nil]
           ["UPDATE u SET d = ?" 1]
           ["UPDATE v SET d = ?, c = ?, a = ?" true 296920407 false]
           ["SELECT * FROM v WHERE ((b <= ?) OR (d > ?))"
            12632018
            false]
           ["INSERT INTO v (c, b, a, d) VALUES (?, ?, ?, ?)"
            1231
            125
            false
            nil]
           ["INSERT INTO t (a, b, c, d) VALUES (?, ?, ?, ?)"
            "2"
            "7r6Q1703ak4Rrfjy9"
            "X0IgWXV"
            true]
           ["INSERT INTO t (b, a, c, d) VALUES (?, ?, ?, ?)"
            "JQ50xGvf2OA5sPfsXw"
            "45AbcJV7ytaWPZ0C40Q"
            "zir"
            nil]
           ["SELECT * FROM u WHERE ?" true]
           ["SELECT * FROM t"]
           ["INSERT INTO u (d) VALUES (?)" 1231]
           ["INSERT INTO t (a, c, b, d) VALUES (?, ?, ?, ?)"
            "UZwseu8Wbzw"
            "zir"
            "PRF"
            false]
           ["INSERT INTO t (d, a, b, c) VALUES (?, ?, ?, ?)"
            true
            "2"
            "A"
            "PRF"]
           ["SELECT * FROM u"]
           ["INSERT INTO v (c, d, a, b) VALUES (?, ?, ?, ?)"
            -103738865
            nil
            nil
            -36596]
           ["UPDATE u SET d = ? WHERE (? >= d)" 125 -2679384]
           ["INSERT INTO v (b, a, d, c) VALUES (?, ?, ?, ?)"
            125
            nil
            nil
            145230]
           ["SELECT * FROM u WHERE (? <> ?)" 1639 1231]
           ["SELECT * FROM t WHERE ?" false]
           ["SELECT * FROM t WHERE (? AND (? AND d AND d AND ?) AND ((d <> d) OR ? OR (d >= d) OR (NOT d)))"
            false
            nil
            nil
            false]
           ["UPDATE t SET d = ?, b = ? WHERE ?"
            nil
            "cNNXiQbCxvLPV05UdTb89JUFCiShxYA"
            nil]
           ["SELECT * FROM t WHERE (d AND (? = c))" "yH31gB2RJ5G"]
           ["SELECT * FROM v WHERE d"]
           ["UPDATE t SET d = ?, a = ? WHERE (((? < d) AND (d OR ? OR d) AND (d < d) AND (c > b)) OR (NOT (? < d)) OR (b <= ?))"
            false
            "PRF"
            nil
            true
            false
            "1QMBIe6ryIC0BoA1KlnPbeLaUP"]
           ["UPDATE u SET d = ? WHERE (d <= d)" 1231]]
             (mapv sql (:statements case))))

      ;(mapv (comp prn sql) (:statements case))
      )))

(defn samples
  "Takes 10 deterministic samples from a generator of SQL expressions. Returns
  a vector of SQL vectors."
  [g]
  (->> (range) ; Seeds
       (map (partial g/generate g 30))
       (take 10)
       (mapv sql)))

(deftest gen-expr-leaf-test
  (let [t1 (table "t1"
                  [(column "int1" integer-type)
                   (column "int2" integer-type)
                   (column "text1" text-type)])
        schema (schema [t1] (g/generate (gen-vpool {}) 10 0))]
    (testing "ints"
      (is (= [["?" -1]
              "int2"
              "int2"
              "int2"
              "int2"
              ["?" -20]
              ["?" -25105412]
              "int2"
              "int1"
              ["?" 0]]
             (samples (gen-expr-leaf {} schema t1 integer-type)))))
    (testing "text"
      (is (= [
              ["?" "W65aal6QWcoYLbQM43Zd7b"]
              "text1"
              "text1"
              "text1"
              "text1"
              ["?" "lNChIlN8Ujy"]
              ["?" "v9fftRComeo1PjyBdlEj"]
              "text1"
              "text1"
              ["?" "u41NbAq2px"]
              ]
             (samples (gen-expr-leaf {} schema t1 text-type)))))
    (testing "booleans"
      (is (= [["?" nil]
              ["?" false]
              ["?" false]
              ["?" true]
              ["?" false]
              ["?" false]
              ["?" nil]
              ["?" false]
              ["?" false]
              ["?" nil]]
             (samples (gen-expr-leaf {} schema t1 boolean-type)))))))

(deftest gen-expr-boolean-test
  (let [t1 (table "t1"
                  [(column "int1" integer-type)
                   (column "int2" integer-type)
                   (column "text1" text-type)])
        schema (schema [t1] (g/generate (gen-vpool {}) 10 0))
        g (gen-expr-boolean {} schema t1)]
    (is (= [
            ["?" true]
           ["(((? < ? COLLATE \"en_US.utf8\") OR (int1 <= int2)) OR ?)"
            "FDeeOkE502SBIsVcAk8W"
            "Hg"
            false]
           ["(? <> int1)" 0]
           ["(? >= ?)" -20 -86]
           ["((text1 < text1) OR (? AND ?) OR ?)" true true nil]
           ["?" nil]
           ["?" true]
           ["((? OR ? OR (NOT ?)) OR ?)" true false false false]
           ["(NOT ?)" false]
           ["?" false]
            ]
           (samples g)))))
