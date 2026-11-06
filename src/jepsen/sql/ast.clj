(ns jepsen.sql.ast
  "An abstract syntax tree representation of basic SQL schemas and statements."
  (:refer-clojure :exclude [eval update compare])
  (:require [clj-commons.slingshot :refer [try+ throw+]]
            [clojure [core :as c]
                     [math :refer [round]]
                     [pprint :refer [pprint]]
                     [set :as set]
                     [string :as str]
                     [walk :as walk]]
            [clojure.test.check :as tc]
            [clojure.test.check [generators :as g]]
            [clojure.tools.logging :refer [info warn]]
            [jepsen [random :as rand]]
            [dom-top.core :refer [loopr]])
  (:import (java.text Collator
                      RuleBasedCollator)
           (java.util Locale)))

;; Protocols

(defprotocol Type
  (super [this]
         "Returns the Type which is the supertype of this one."))

(defprotocol Eval
  (eval-without-row? [this]
                     "Can this be evaluated without a row?")

  (eval [this row]
        "Evaluates an expression in the context of a row. A row is a map of
        keyword local column names to values, like `{:name \"streisand\",
        :effect_power 42}`"))

(defprotocol SQL
  (sql [this] "Converts this AST node to an SQL vector suitable for use with
              `j/execute!"))

(defprotocol DDL
  (setup [this]
    "A vector of SQL statements that will create this thing.")
  (teardown [this]
    "A vector of SQL statements that will tear down this thing."))

(defn splice
  "Splices n SQL vectors or strings together. We do this by concatenating
  their strings and remaining parameters. Nil vanishes."
  ([a]
   (if (string? a)
     [a]
     a))
  ([a b]
   (cond (nil? a) (splice b)
         (nil? b) (splice a)
         true (if (string? a)
                (if (string? b)
                  [(str a b)]
                  (assoc b 0 (str a (first b))))
                (if (string? b)
                  (assoc a 0 (str (first a) b))
                  (-> (assoc a 0 (str (first a) (first b)))
                      (into (subvec b 1)))))))
  ([a b & more]
   (reduce splice
           (splice a b)
           more)))

(defn splice*
  "Splices n SQL strings or vectors together, optionally joining them with the
  given separator."
  ([sqls]
   (case (count sqls)
     0 [""]
     1 (first sqls)
     (reduce splice "" sqls)))
  ([separator sqls]
   (case (count sqls)
     0 [""]
     1 (first sqls)
     (reduce (fn [acc sql]
               (-> acc
                   (splice separator)
                   (splice sql)))
             sqls))))

;; Types

; In general, we represent each SQL type using a different type of record; this
; way we can parameterize types like CHARACTER VARYING(3).

(defrecord NumericType []
  Type
  (super [_]))

(def numeric-type
  "The singleton NumericType."
  (NumericType.))

(defrecord ExactNumericType []
  Type
  (super [_] numeric-type))

(def exact-numeric-type
  "The singleton ExactNumericType."
  (ExactNumericType.))

(defrecord IntegerType []
  Type
  (super [_] exact-numeric-type)

  SQL
  (sql [_] ["INTEGER"]))

(def integer-type
  "The singleton IntegerType."
  (IntegerType.))

(defrecord StringType []
  Type
  (super [_]))

(def string-type
  "The singleton StringType."
  (StringType.))

(defrecord CharacterStringType []
  Type
  (super [_] string-type))

(def character-string-type
  "The singleton CharacterStringType."
  (CharacterStringType.))

(def text-collation
  "The collation we use for all textual types. I haven't exactly figured out
  how this should work--at some point we should probably thread this down
  through the various generator option maps, but for now I'll just define it
  here and monkeypatch it in tests."

  "en_US.utf8")

(defrecord TextType []
  Type
  (super [_] character-string-type)

  SQL
  (sql [_] [(str "TEXT collate \"" text-collation \")]))

(def text-type
  "The singleton TextType."
  (TextType.))

(defrecord BooleanType []
  Type
  (super [_])

  SQL
  (sql [_] ["BOOLEAN"]))

(def boolean-type
  "The singleton BooleanType."
  (BooleanType.))

;; General Expressions

; A general-purpose box for any SQL vector
(defrecord Statement [sql]
  SQL
  (sql [_] sql))

; Represents an SQL parameterized literal value like 2 or 'hi'.
(defrecord Literal [x]
  SQL
  (sql [_]
    ["?" x])

  Eval
  (eval-without-row? [_] true)
  (eval [_ row] x))

(defn literal
  "Constructs a Literal."
  [x]
  (assert (not (satisfies? SQL x)))
  (Literal. x))

; Represents a column used as a part of a table.
(defrecord Column
  [name
   type
   primary-key?]
  SQL
  (sql [_]
    (splice name " " (sql type)
            (when primary-key? " PRIMARY KEY"))))

(defn column
  "Takes a name, type, and a map of options; constructs a Column."
  ([name type]
   (assert (string? name))
   (assert (satisfies? Type type))
   (Column. name type false))
  ([name type opts]
   (assert (string? name))
   (assert (satisfies? Type type))
   (map->Column (assoc opts :name name :type type))))

; Represents a column name like `age`. We use these to represent columns in
; statements, to distinguish them from literals.
(defrecord ColumnName [name]
  SQL
  (sql [_] name)
  Eval
  (eval-without-row? [_] false)
  (eval [_ row]
    (let [r (get row (keyword name) ::not-found)]
      (when (identical? ::not-found r)
        (throw (IllegalArgumentException.
                 (str "Row " (pr-str row) " has no column "
                      (pr-str (keyword name))))))
      r)))

(defn column-name
  "Constructs a ColumnName from either a Column or a string."
  [col]
  (cond (string? col)
        (ColumnName. col)

        (instance? Column col)
        (ColumnName. (:name col))

        true
        (throw (IllegalArgumentException.
                 "Expected a string column name or a Column"))))

; Represents an SQL table schema
(defrecord Table
  [name cols]
  DDL
  (setup [this]
    [(Statement.
      (splice*
         (conj ["CREATE TABLE " name " ("]
               (splice* ", " (map sql cols))
               ")")))])

  (teardown [_]
    [(Statement. [(str "DROP TABLE " name)])]))

(defn table
  "Constructs a Table, given a name and a vector of Columns."
  [name cols]
  (assert (string? name))
  (assert (every? (partial instance? Column) cols))
  (Table. name cols))

; Represents a table name like 't'
(defrecord TableName [name]
  SQL
  (sql [_] name))

(defn table-name
  "Constructs a TableName from either a Column or a string."
  [table]
  (cond (string? table)
        (TableName. table)

        (instance? Table table)
        (TableName. (:name table))

        true
        (throw (IllegalArgumentException.
                 "Expected a string column name or a Column"))))

; A Schema defines a collection of tables, and also some common values you
; might use to work with them.
(defrecord Schema
  [; A vector of Tables
   tables
   ; A map of types to a handful of values, like {:integer [3, 4]}. We use this
   ; to generate similar values often in a given schema, which makes joins and
   ; equality predicates more likely to match.
   vpool]

  DDL
  (setup [_]
    (reduce into [] (map setup tables)))
  (teardown [_]
    (reduce into [] (map teardown tables))))

(defn schema
  "Constructs a schema from a vector of tables, and optionally a vpool."
  ([tables]
   (schema tables {}))
  ([tables vpool]
   (assert (vector? tables))
   (assert (every? (partial instance? Table) tables))
   (Schema. tables vpool)))

(defrecord Insert [table cols values]
  SQL
  (sql [_]
    (splice "INSERT INTO " (sql table) " ("
            (splice* ", " (map sql cols))
            ") VALUES ("
            (splice* ", " (map sql values))
            ")")))

;; Expressions

; Represents an equality comparison like `name = 'regina'
(defrecord Equals [left right]
  SQL
  (sql [_]
    (splice "(" (sql left) " = " (sql right) ")"))

  Eval
  (eval-without-row? [_]
    (and (eval-without-row? left)
         (eval-without-row? right)))

  (eval [_ row]
    (let [l (eval left row)]
      (when-not (nil? l)
        (let [r (eval right row)]
          (when-not (nil? r)
            (= l r)))))))

(defn equals
  "Constructs an Equals expression."
  [left right]
  (assert (satisfies? SQL left))
  (assert (satisfies? SQL right))
  (Equals. left right))

(def ^Collator collator
  "Ugh I just cannot for the life of me figure out the JVM collator API.
  Postgres wants to sort spaces before underscores, but convincing a collator
  to do that seems impossible."
  ;(let [c (Collator/getInstance java.util.Locale/US)
  ;      ; you do NOT want to know
  ;      rules (str/replace (.getRules c)
  ;                         #"<'\u005f'"
  ;                         "<' '<'\u005f'")]
  ;  (doto (RuleBasedCollator. rules)
  ;    (.setStrength 3)))
  (Collator/getInstance (java.util.Locale/of "en" "US")))

(defn compare+
  "Compares two objects the same way we expect an SQL database to do."
  [a b]
  (cond (string? a)
        (.compare collator a b)

        true
        (c/compare a b)))

; Represents a binary comparator like `x = 2` or `a > b`
(defrecord Compare [op left right]
  SQL
  (sql [_]
    (let [; Columns will have collations from the schema, but if we're
          ; comparing two literal strings, we also need to provide an explicit
          ; collation.
          collate (when (and (instance? Literal left)
                           (instance? Literal right)
                           (string? (:x left))
                           (string? (:x right)))
                    (str " COLLATE \"" text-collation "\""))]
    (splice "(" (sql left) " " (name op) " " (sql right) collate ")")))

  Eval
  (eval-without-row? [_]
    (and (eval-without-row? left)
         (eval-without-row? right)))

  (eval [expr row]
    (try
      (let [l (eval left row)]
        (when-not (nil? l)
          (let [r (eval right row)]
            (when-not (nil? r)
              (case op
                :=  (= l r)
                :<> (not= l r)
                :<  (<    (compare+ l r) 0)
                :<= (<=   (compare+ l r) 0)
                :>  (<  0 (compare+ l r))
                :>= (<= 0 (compare+ l r)))))))
      (catch Exception e
        (throw+ {:type :eval-error
                 :expr expr
                 :row row
                 :left  (eval left row)
                 :right (eval right row)}
                e)))))

(def compare-ops
  "Legal ops for Compare"
  [:= :<> :< :<= :> :>=])

(defn compare
  "Constructs a Compare expression given a keyword (:=, :<>, :<=, ...) and two
  expressions."
  [op left right]
  (assert (some #{op} compare-ops))
  (assert (satisfies? SQL left))
  (assert (satisfies? SQL right))
  (Compare. op left right))

; Represents a (NOT A) expression
(defrecord Not [expr]
  SQL
  (sql [_]
    (splice "(NOT " (sql expr) ")"))

  Eval
  (eval-without-row? [_]
    (eval-without-row? expr))

  ; Remember, in SQL, NOT NULL is NULL!
  (eval [_ row]
    (let [x (eval expr row)]
      (if (nil? x)
        nil
        (not x)))))

(defn ->not
  "Constructs a Not expression."
  [expr]
  (assert (satisfies? SQL expr))
  (Not. expr))

; Represents (A AND B AND ...)
(defrecord And [exprs]
  SQL
  (sql [_]
    (splice "("
            (splice* " AND " (map sql exprs))
            ")"))

  Eval
  (eval-without-row? [_]
    (every? eval-without-row? exprs))

  ; Note that we're specifically representing SQL three-valued logic here.
  ; Weirdly, `NULL AND NULL AND NULL AND FALSE` is false--we can't
  ; short-circuit NULL.
  (eval [_ row]
    (loop [have-nil?  false
           exprs      exprs]
      (if (seq exprs)
        (let [x (eval (first exprs) row)]
          (cond (false? x)  false
                (nil? x)    (recur true (next exprs))
                true        (recur have-nil? (next exprs))))
        ; No more expressions to check
        (if have-nil?
          nil
          true)))))

(defn ->and
  "Constructs an And expression."
  [exprs]
  (assert (every? (partial satisfies? SQL) exprs))
  (condp = (count exprs)
    0 (literal false)
    1 (first exprs)
    (And. exprs)))

; Represents (A OR B OR ...)
(defrecord Or [exprs]
  SQL
  (sql [_]
    (splice "(" (splice* " OR " (map sql exprs)) ")"))

  Eval
  (eval-without-row? [_]
    (every? eval-without-row? exprs))

  ; Note that we're specifically representing SQL three-valued logic here.
  ; FALSE OR FALSE OR NULL returns NULL, not FALSE, so we can't short-circuit
  ; NULL.
  (eval [_ row]
    (loop [have-nil? false
           exprs      exprs]
      (if (seq exprs)
        (let [x (eval (first exprs) row)]
          (cond (true? x) true
                (nil? x)  (recur true (next exprs))
                true      (recur have-nil? (next exprs))))
        (if have-nil?
          nil
          false)))))

(defn ->or
  "Constructs an Or expression."
  [exprs]
  (assert (every? (partial satisfies? SQL) exprs))
  (condp = (count exprs)
    0 (literal true)
    1 (first exprs)
    (Or. exprs)))

(defn row->pred
  "Turns a row map into a predicate which matches that row, or any row with
  additional fields."
  [m]
  (And. (mapv (fn [[k v]]
                (Equals. (column-name (name k)) (literal v)))
              m)))

(defn column-names-in
  "Takes an SQL expression and collects a set of all ColumnNames in it."
  [expr]
  (let [cols (volatile! (transient #{}))]
    (walk/prewalk
      (fn search [x]
        (when (instance? ColumnName x)
          (vswap! cols conj! x))
        x)
      expr)
    (persistent! @cols)))

;; Statements

(defn insert
  "Constructs an Insert."
  [table cols values]
  (assert (every? (partial instance? ColumnName) cols))
  (assert (every? (partial satisfies? SQL) values))
  (Insert. (table-name table) cols values))


; An UPDATE statement like "UPDATE cats SET age = age + 1 WHERE name =
; 'professor meowington'
(defrecord Update [table ; A TableName
                   set   ; A vector of [col-name val-expr] pairs
                   where ; A predicate expression
                   ]
  SQL
  (sql [_]
    (splice "UPDATE " (sql table) " SET "
            (splice* ", "
                     (map (fn [[col expr]]
                            (splice (sql col) " = " (sql expr)))
                          set))
            (when where
              (splice " WHERE " (sql where))))))

(defn update
  "Constructs an Update."
  [table set where]
  (assert (vector? set))
  (assert (every? (partial instance? ColumnName) (map first set)))
  (assert (every? (partial satisfies? SQL) (map second set)))
  (assert (or (nil? where)
              (satisfies? SQL where)))
  (Update. (table-name table) set where))

; A DELETE statement like "DELETE FROM treats WHERE tasty FALSE"
(defrecord Delete [table ; A TableName
                   where ; A predicate expression
                   ]
  SQL
  (sql [_]
    (splice "DELETE FROM " (sql table)
            (when where
              (splice " WHERE " (sql where))))))

(defn delete
  "Constructs a Delete."
  [table where]
  (assert (or (nil? where) (satisfies? SQL where)))
  (Delete. (table-name table) where))

; Represents a select statement like `select * from people where name =
; regina`.
(defrecord Select [table where]
  SQL
  (sql [_]
    (splice ["SELECT * FROM "]
            (sql table)
            (when where
              (splice " WHERE " (sql where))))))

(defn select
  "Constructs a Select."
  [table where]
  (assert (or (nil? where) (satisfies? SQL where)))
  (Select. (table-name table) where))

; A Case is a schema and a vector of statements.
(defrecord Case [schema statements]
  DDL
  (setup    [_] (setup schema))
  (teardown [_] (teardown schema)))

(defn rewrite-tables
  "Rewrites all table names in a case using a function (f table-name-str)."
  [case f]
  (walk/prewalk
    (fn rewrite [x]
      ; Ugh, this is going to break on hot code reloading because we define a
      ; new class for Table etc. with every defrecord, and they won't match
      (condp instance? x
        Table     (c/update x :name f)
        TableName (c/update x :name f)
        x))
    case))

(defn unique-tables
  "Rewrites all tables in a case to have a unique suffix, like t_1"
  [case i]
  (rewrite-tables case #(str % "_" i)))

;; Expression simplification

; This is not fantastic, but it significantly cuts down the complexity of the
; predicates we have to look at when debugging internal-sim examples.
(defn fixed-point
  "Iterates f on x until unchanged."
  [f x]
  (loop [x x]
    (let [x' (f x)]
      (if (= x x')
        x
        (recur x')))))

(defprotocol Simplify
  (simplify-2 [expr]))

(extend-protocol Simplify
  Not
  (simplify-2 [expr]
    (let [e (:expr expr)]
      (if (instance? Not e)
        ; Double negation
        (:expr e)
        expr)))

  And
  (simplify-2 [expr]
    (let [exprs (:exprs expr)]
      (case (count exprs)
        ; Degenerate cases
        0 (literal true)
        1 (first exprs)

        (loopr [exprs  []
                seen   #{}
                a-nil? false]
               [e (:exprs expr)]
               (if (seen e)
                 ; We've already seen this
                 (recur exprs seen a-nil?)
                 ; New expression
                 (let [seen (conj seen e)]
                   (cond (instance? Literal e)
                         (case (:x e)
                           ; AND TRUE is superfluous
                           true (recur exprs seen a-nil?)
                           ; AND FALSE is always false
                           false (literal false)
                           ; AND NULL... we have to wait and see if we get a
                           ; false.
                           nil (recur exprs seen true)
                           (recur (conj exprs e) seen a-nil?))

                         ; Lift nested ANDs
                         (instance? And e)
                         (recur (into exprs (:exprs e)) seen a-nil?)

                         ; Otherwise...
                         true
                         (recur (conj exprs e) seen a-nil?))))
               ; If we encountered a null but made it here, it's actually a bit
               ; weird. We don't know if we'll evaluate to NULL or FALSE until
               ; we actually eval in the context of the row, so we *can't*
               ; condense further.
               (if a-nil?
                 (->and (conj exprs (literal nil)))
                 (->and exprs))))))

  Or
  (simplify-2 [expr]
    (let [exprs (:exprs expr)]
      (case (count exprs)
        ; Degenerate cases
        0 (literal true)
        1 (first exprs)

        (loopr [exprs  []
                seen   #{}
                a-nil? false]
               [e (:exprs expr)]
               (if (seen e)
                 ; We've already seen this
                 (recur exprs seen a-nil?)
                 ; New expression
                 (let [seen (conj seen e)]
                   (cond (instance? Literal e)
                         (case (:x e)
                           ; OR FALSE is superfluous
                           false (recur exprs seen a-nil?)
                           ; OR TRUE is always true
                           true (literal true)
                           ; OR NULL... we have to wait and see if we get a
                           ; true.
                           nil (recur exprs seen true)
                           (recur (conj exprs e) seen a-nil?))

                         ; Lift nested ORs
                         (instance? Or e)
                         (recur (into exprs (:exprs e)) seen a-nil?)

                         ; Otherwise
                         true
                         (recur (conj exprs e) seen a-nil?))))
               ; If we encountered a null but made it here, it's actually a bit
               ; weird. We don't know if we'll evaluate to NULL or FALSE until
               ; we actually eval in the context of the row, so we *can't*
               ; condense further.
               (if a-nil?
                 (->or (conj exprs (literal nil)))
                 (->or exprs)))))))

(defn simplify-1
  "A single simplification pass."
  [expr]
  (if (satisfies? Simplify expr)
    (simplify-2 expr)
    expr))

(defn simplify-static
  "Walks an expression tree, replacing anything that can be statically
  evaluated."
  [expr]
  (walk/postwalk (fn [expr]
                   (if (and (satisfies? Eval expr)
                            (eval-without-row? expr))
                     (literal (eval expr nil))
                     expr))
                 expr))

(defn simplify
  "Takes an expression like (And [(Or [...]) (Not (Equals ...))]) and returns
  an equivalent, simpler version of it."
  [expr]
  (fixed-point simplify-1 (simplify-static expr)))
