(ns jepsen.sql.gen
  "Test.check generators for simple schemas and statements."
  (:require [clojure [core :as c]
                     [math :refer [round]]
                     [pprint :refer [pprint]]
                     [set :as set]
                     [string :as str]
                     [walk :refer [prewalk]]]
            [clojure.test.check :as tc]
            [clojure.test.check [generators :as g]]
            [clojure.tools.logging :refer [info warn]]
            [com.gfredericks.test.chuck.generators :refer [string-from-regex]]
            [jepsen [random :as rand]]
            [jepsen.sql [ast :as ast]]
            [dom-top.core :refer [loopr]])
  (:import (jepsen.sql.ast BooleanType
                           Case
                           ColumnName
                           Equals
                           IntegerType
                           Literal
                           Select
                           Schema
                           TextType
                           TableName
                           Update)))

;; Basic generators

(defn bind
  "It is SO frustrating that tc/gen does (fmap f gen) but (bind gen f)."
  [f gen]
  (g/bind gen f))

(defmacro flet
  "A reduced version of `generators/let` which uses `fmap`, rather than `bind`,
  and does not support serial assignment; all bindings must be independent of
  one another. My hope is this shrinks better."
  [bindings & body]
  (assert (even? (count bindings)))
  (let [pairs  (partition 2 bindings)
        lefts  (mapv first pairs)
        rights (mapv second pairs)]
    `(g/fmap (fn ~'flet [~lefts]
               ~@body)
             (g/tuple ~@rights))))

(defn probability
  "A generator which returns true with probability p, otherwise false. Shrinks
  towards false. Only works up to one part in 1000."
  [p]
  (let [scale 1000
        t     (max 1 (Math/round (float (* scale p))))]
    (g/fmap (fn [x]
              (< x t))
            (g/choose 0 (dec scale)))))

(defn rarely
  "A generator which rarely generates from `rare`, but sometimes from `common`."
  [rare common]
  (g/frequency [[100 common]
                [1 rare]]))

(defn gen-long
  "A generator of Long values between lower and upper, inclusive."
  [lower upper]
  (g/large-integer* {:min lower :max upper}))

;; Basic types, table and column names

(def column-names
  "All column names, as a vector."
  (mapv str "abcdefghijklmnopqrs"))

(def table-names
  "All table names, as a vector."
  ["t" "u" "v"])

(defn gen-concrete-type
  "A generator for a specific type, like INTEGER or CHARACTER(3). We use these
  to build schemas."
  [opts]
  (g/one-of
    [(g/return ast/integer-type)
     (g/return ast/boolean-type)
     (g/return ast/text-type)]))

(defn gen-type
  "A generator for a type valid in the given table."
  [opts schema table]
  (->> (:cols table)
       (map :type)
       distinct
       vec
       g/elements))

;; Literals

(defn gen-string-lit
  "Generates a string literal. We're just doing simple strings for now. With no
  args, generates variable width strings. With an integer size, generates
  strings of up to that length."
  ([]
   ; For now let's restrict ourselves to polite strings that are unlikely to
   ; mess with escaping, and which our collator can reliably sort.
   (string-from-regex #"[A-Za-z0-9]+"))
  ([n]
   (string-from-regex
     (re-pattern (str "[A-Za-z0-9]{0," n "}")))))

(defprotocol GenLitOfType
  (gen-lit-of-type [type opts schema]
                   "Returns a generator for the given Type.")

  (vpool-key [type]
              "What key do we use in the vpool when generating values of this
              type?"))

(extend-protocol GenLitOfType
  BooleanType
  (gen-lit-of-type [_ opts schema]
    (g/elements [true false nil]))

  (vpool-key [_] nil)

  IntegerType
  (gen-lit-of-type [_ opts schema]
    (gen-long Integer/MIN_VALUE Integer/MAX_VALUE))

  (vpool-key [_] :longs)

  TextType
  (gen-lit-of-type [_ opts schema]
    (gen-string-lit))

  (vpool-key [_] :strings))


(defn gen-lit*
  "Generates a Literal of the given type."
  [opts schema type]
  (g/fmap ast/literal
          (gen-lit-of-type type opts schema)))

(defn gen-lit
  "Generates a Literal of the given type, sometimes picking dense values from
  the schema's vpool."
  [opts schema type]
  (let [lit* (gen-lit* opts schema type)]
    (if-let [vpool-key (vpool-key type)]
      (let [vpool (get (:vpool schema) vpool-key)]
        (assert (seq vpool) (str "No elements in vpool for " vpool-key))
        (g/one-of [(g/fmap ast/literal (g/elements vpool))
                   lit*]))
      lit*)))

; Columns, Tables, Schemas

(defn gen-column
  "Generates a Column with the given name."
  [opts name]
  (->> (g/hash-map :name (g/return name)
                   :type (gen-concrete-type opts)
                   :primary-key? g/boolean)
       (g/fmap ast/map->Column)))

(defn remove-duplicate-primary-keys
  "Takes a vector of columns and limits them to just one primary key."
  [cols]
  (loopr [pk?   false
          cols' []]
         [col cols]
         (let [pk?' (or pk? (:primary-key? col))]
           (recur pk?'
                  (conj cols'
                        (if (and pk? pk?')
                          (assoc col :primary-key? false)
                          col))))
         cols'))

(defn gen-table
  "Generates a Table. Options are:

      :duplicate-primary-keys?  If true, we rarely emit tables with more than
                                one primary key.
      :max-column-count         The maximum number of columns"
  [opts name]
  ; We're doing a weird dance here to miminize the recurrent use of
  ; bind--this should hopefully shrink better.
  (let [mcc (:max-column-count opts 4)]
    (->> (g/tuple (g/choose 1 mcc)
                  (g/shuffle (vec (take mcc column-names)))
                  (if (:duplicate-primary-keys opts true)
                    (probability 1/100)
                    (g/return false)))
         ; Generate columns
         (bind (fn [[col-count col-names duplicate-pks?]]
                 (g/tuple
                   (->> col-names
                        (take col-count)
                        (mapv (partial gen-column opts))
                        (apply g/tuple))
                   (g/return duplicate-pks?))))
         ; Filter columns and produce a schema
         (g/fmap (fn [[cols duplicate-pks?]]
                   (let [cols (if duplicate-pks?
                                cols
                                (remove-duplicate-primary-keys cols))]
                     (ast/map->Table {:name name
                                      :cols cols})))))))

(defn gen-vpool
  "Generator for a value pool: a map of keywords (like :longs or :doubles) to a
  vector of values. We use this to generate the same values over and over again
  in different contexts. Options:

      :max-vpool-count  How many elements can the value pool contain for a
                        given type?"
  [opts]
  (let [opts {:min-elements 1
              :max-elements (:max-vpool-count opts 3)}]
    (g/hash-map
      ; We pick small generators here so that they're likely to work across
      ; domains
      :longs   (g/vector-distinct (gen-long Short/MIN_VALUE Short/MAX_VALUE)
                                  opts)
      :doubles (g/vector-distinct (g/double* {:infinite? false
                                              :NaN? false})
                                  opts)
      ; We make these very short so they're more likely to work across various
      ; character(n) sizes
      :strings (g/vector-distinct (gen-string-lit 3) opts))))

(defn gen-schema
  "Generator for a Schema. Options are:

      :max-table-count  The maximum number of tables
      :max-column-count The maximum number of columns
      :max-vpool-count  How many elements can the value pool contain for a
                        given type?"
  [opts]
  (->> (g/tuple
         (g/choose 1 (:max-table-count opts (count table-names)))
         (g/shuffle table-names)
         (gen-vpool opts))
       (bind (fn [[table-count table-names vpool]]
               (g/hash-map
                 :tables (->> table-names
                              (take table-count)
                              (mapv (partial gen-table opts))
                              (apply g/tuple))
                 :vpool (g/return vpool))))
       (g/fmap ast/map->Schema)))

;; Expressions

(defn gen-column-name
  "Generates a ColumnName of the given type in the given table. Returns nil
  rather than a generator if there are no columns of this type."
  [opts schema table type]
  (let [cols (->> (:cols table)
                  ; TODO: type compatibility, so VARCHAR(3) and TEXT end up
                  ; used together.
                  (filter (comp #{type} :type))
                  (mapv ast/column-name))]
    (when (seq cols)
      (g/elements cols))))

(declare gen-expr
         gen-expr-leaf)

(defn gen-equals
  "Generates an Equals expression up to the given depth."
  [opts schema table depth]
  (g/bind (gen-type opts schema table)
          (fn [type]
            (let [g (gen-expr opts schema table type (dec depth))]
              (flet [left  g
                     right g]
                    (ast/equals left right))))))

(defn gen-compare
  "Generates a Compare expression up to the given depth."
  [opts schema table depth]
  (g/bind (gen-type opts schema table)
          (fn [type]
            (let [g (gen-expr opts schema table type (dec depth))]
              (flet [op    (g/elements ast/compare-ops)
                     left  g
                     right g]
                    (ast/compare op left right))))))

(defn gen-expr-boolean-logic
  "Generates an AND, OR, or NOT expression, given a generator of
  sub-expressions."
  [expr-gen]
  (g/one-of [(g/fmap ast/->not expr-gen)
             (g/fmap ast/->or  (g/vector expr-gen 2 4))
             (g/fmap ast/->and (g/vector expr-gen 2 4))]))

(defn gen-expr-boolean
  "Generates a boolean expression up to the given depth."
  ([opts schema table]
   (gen-expr-boolean opts schema table 3))
  ([opts schema table depth]
   (if (< depth 1)
     (gen-expr-leaf opts schema table ast/boolean-type)
     (g/one-of [(gen-expr-leaf opts schema table ast/boolean-type)
                (gen-compare opts schema table (dec depth))
                (gen-expr-boolean-logic
                  (gen-expr-boolean opts schema table (dec depth)))]))))

(defn gen-expr-leaf
  "A generator which returns simple expressions of the given type, like
  Literals or ColumnNames."
  [opts schema table type]
  (if-let [col (gen-column-name opts schema table type)]
    (g/one-of [(gen-lit opts schema type) col])
    ; No valid columns, just do a literal.
    (gen-lit opts schema type)))

(defn gen-expr
  "Takes options, schema, a table, a type, and a depth (which controls nested
  expressions). Returns a generator of expressions of the given type. Depth 0
  means only bare literals."
  ([opts schema table type depth]
   (condp = type
     ast/boolean-type (gen-expr-boolean opts schema table depth)
     ; For any other type, we only generate leaves
     (gen-expr-leaf opts schema table type))))

;; Statements

(defn gen-where
  "Generates a (possibly nil) WHERE clause."
  [opts schema table]
  (g/frequency
    [[1 (g/return nil)]
     [3 (gen-expr-boolean opts schema table)]]))

(defn gen-row
  "Generator of row maps (e.g. {:foo 2}) for a Table."
  [opts schema table]
  (flet [vals (->> (:cols table)
                   (mapv (comp (partial gen-lit opts schema) :type))
                   (apply g/tuple))]
        (zipmap (map (comp keyword :name) (:cols table))
                (map :x vals))))

(defn gen-insert
  "Generator of Inserts"
  [opts schema]
  (g/let [table (g/elements (:tables schema))
          cols  (g/shuffle (:cols table))
          vals  (->> cols
                     (mapv (comp (partial gen-lit opts schema) :type))
                     (apply g/tuple))]
    (ast/insert table
                (mapv ast/column-name cols)
                vals)))

(defn gen-update-pair
  "A generator which produces a [col-name value-expr] pair for an UPDATE"
  [opts schema table]
  (g/let [col (g/elements (:cols table))
          value (gen-lit opts schema (:type col))]
    [(ast/column-name col) value]))

(defn gen-update
  "Generator of Updates."
  [opts schema]
  (g/let [table (g/elements (:tables schema))
          set (let [n (count (:cols table))]
                 ; Without duplicates
                 (g/vector-distinct-by first
                                       (gen-update-pair opts schema table)
                                       {:min-elements 1, :max-elements n}))
          where (gen-where opts schema table)]
    (ast/update table set where)))

(defn gen-delete
  "Generator of Deletes."
  [opts schema]
  (g/let [table (g/elements (:tables schema))
          where (gen-where opts schema table)]
    (ast/delete table where)))

(defn gen-select
  "Generator of select statements"
  [opts schema]
  (->> (g/elements (:tables schema))
       (bind (fn [table]
               (flet [where (gen-where opts schema table)]
                 (ast/select table where))))))

(defn gen-statement
  "Generator of statements."
  [opts schema]
  (g/frequency [[8 (gen-select opts schema)]
                [8 (gen-insert opts schema)]
                [8 (gen-update opts schema)]
                [1 (gen-delete opts schema)]]))

(defn gen-case
  "Generator for a Case. Options:

      :duplicate-primary-keys?  Whether to generate tables with duplicate
                                primary keys sometimes.
      :max-table-count      The maximum number of tables
      :max-column-count     The maximum number of columns
      :max-statement-count  The maximum number of statements"
  [opts]
  (g/let [schema (gen-schema opts)
          statements (g/vector (gen-statement opts schema)
                               1 (:max-statement-count opts 64))]
    (Case. schema statements)))

(defn generate
  "Generates a Case given options. Options are:

      :duplicate-primary-keys?  Whether to generate tables with duplicate
                                primary keys sometimes.
      :seed                 The random seed
      :size                 The size parameter for generation
      :max-table-count      The maximum number of tables
      :max-column-count     The maximum number of columns
      :max-statement-count  The maximum number of statements"
  [opts]
  (let [size (:size opts 32)
        seed (:seed opts (rand/long))]
    (g/generate (gen-case opts) size seed)))
