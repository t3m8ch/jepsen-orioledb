(ns jepsen.sql
  "Main API for SQL tests."
  (:require [clojure.string :as str]
            [jepsen [cli :as cli]]
            [jepsen.sql [encoding :as encoding]]
            [jepsen.sql.workload [append :as append]
                                 [default-value :as default-value]
                                 [internal :as internal]
                                 [internal-sim :as internal-sim]
                                 [rw :as rw]]))

(def key-types
  "The various ways we can select something by logical key."
  #{:primary :secondary})

(def upsert-types
  "The various upsert tactics we use."
  #{:update-insert-update :on-conflict :copy-on-write})

(defn parse-comma-kws
  "Parses comma-separated strings into a vector of keywords."
  [s]
  (mapv keyword (str/split s #",")))

(def cli-opts
  "CLI options suitable for use with tools.cli. These are the keys we expect to
  find in our CLI and test maps. Not every workload uses all keys; see the
  specific workload code for details. You can merge this into your own option
  spec like so:

    (jepsen.cli/merge-opt-specs jepsen.sql/cli-opts
                                [...your options here...])

  This way your options can override any provided here."
  [(let [encodings (set (mapv :name encoding/all-encodings))]
     [nil "--encodings ENCODING_NAMES" "A comma-separated list of encoding names---different ways we can encode our integer values into the database."
      :default (sort encodings)
      :parse-fn parse-comma-kws
      :validate [(partial every? encodings)
                 (cli/one-of encodings)]])

   ["-i" "--isolation LEVEL" "What level of isolation should we use for transactions? serializable, repeatable-read, etc."
    :default :serializable
    :parse-fn keyword
    :validate [#{:read-uncommitted
                 :read-committed
                 :repeatable-read
                 :serializable}
               "Should be one of read-uncommitted, read-committed, repeatable-read, or serializable"]]

   [nil "--expected-consistency-model MODEL" "What level of isolation do we *expect* to observe? Defaults to the same as --isolation. This is usually passed to Elle's checker."
    :default nil
    :parse-fn keyword]

   [nil "--[no-]indirection" "Allows workloads to look up rows via a separate, indirect lookup table."
    :id :indirection?
    :default false]

   [nil "--key-types TYPES" "Some workloads have multiple ways to select the row for a specific key. TYPES is a comma-separated list of types like primary,secondary, which means we use a mix of primary keys and (un-indexed) secondary keys."
    :default  (vec key-types)
    :parse-fn parse-comma-kws
    :validate [(partial every? key-types) (cli/one-of key-types)]]

   [nil "--key-count NUM" "Number of keys in active rotation."
    :default  10
    :parse-fn parse-long
    :validate [pos? "Must be a positive integer"]]

   [nil "--key-dist DISTRIBUTION" "Key distribution pattern for workload generation."
    :default :exponential
    :parse-fn keyword
    :validate [#{:uniform :exponential :zipf}
               "Should be one of uniform, exponential, or zipf"]]

   [nil "--[no-]linearizable-keys" "If set, assumes keys are linearizable for the wr workload."
    :id :linearizable-keys?
    :default false]

   ; TODO: id :log-sql?
   [nil "--log-sql" "Logs SQL queries"]

   [nil "--max-txn-length NUM" "Maximum number of operations in a transaction."
    :default  4
    :parse-fn parse-long
    :validate [pos? "Must be a positive integer"]]

   [nil "--max-writes-per-key NUM" "Maximum number of writes to any given key."
    :default  256
    :parse-fn parse-long
    :validate [pos? "Must be a positive integer."]]

   [nil "--mop-delay MS" "Maximum delay of a transactional micro-operation, in milliseconds. Delays are Zipfian, so mostly very small, but occasionally large."
    :default 100
    :parse-fn parse-long
    :validate [(complement neg?) "Must not be negative"]]

   [nil "--nemesis-interval SECS" "Roughly how long between nemesis operations."
    :default 5
    :parse-fn read-string
    :validate [pos? "Must be a positive number."]]

   [nil "--[no-]savepoints" "Does this database support savepoints?"
    :default true]

   [nil "--[no-]sequential-keys" "If set, assumes keys are sequentially consistent for the rw workload."
    :id :sequential-keys?
    :default false]

   [nil "--upsert-types TACTICS" "A comma-separated list of upsert tactics. For example, update,on-conflict."
    :default (vec upsert-types)
    :parse-fn parse-comma-kws
    :validate [(partial every? upsert-types) (cli/one-of upsert-types)]]

   [nil "--[no-]wfr-keys" "If set, assumes keys obey writes-follow-reads within a single transaction, for the rw workload. This is almost *certainly* a safe assumption."
    :id :wfr-keys?
    :default true]
   ])

(defn opt-fn
  "Transforms CLI options before execution. You can compose this with your own
  opt fn as well."
  [parsed]
  (update-in parsed [:options :expected-consistency-model]
             #(or % (get-in parsed [:options :isolation]))))

(defn workloads
  "Constructs a map of workload names (e.g. `:internal`) to functions which
  take CLI options and return a workload map (e.g. `{:generator ..., :client
  ..., :checker ...}`). Options are:

      :open     (fn [test conn] ...), a function which opens a next.jdbc
                connection to the given node.
      :error-fn (fn [exception] ...), a function which transforms exceptions
                into maps.

  See the README for details."
  [{:keys [open error-fn]}]
   ; Our workload functions take a single map of merged options from the CLI
   ; and here; we construct a little wrapper function.
   (let [sql-opts {::open open, ::error-fn error-fn}
         wrap (fn wrap [workload-fn]
                (fn workload [cli-opts]
                  (workload-fn (merge cli-opts sql-opts))))]
     (update-vals {:append        append/workload
                   :default-value default-value/workload
                   :internal      internal/workload
                   :internal-sim  internal-sim/workload
                   :rw            rw/workload}
                  wrap)))
