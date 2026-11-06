# Jepsen SQL Workloads

These are common workloads for testing SQL databases using Jepsen and JDBC. A
Jepsen test for (e.g.) Postgres can use this library to generate transactions,
submit them over a JDBC client, and evaluate their correctness.

[![clojars badge](https://img.shields.io/clojars/v/io.jepsen/sql.svg)](https://clojars.org/io.jepsen/sql)
[![cljdoc badge](https://cljdoc.org/badge/io.jepsen/sql)](https://cljdoc.org/d/io.jepsen/sql)

## Workloads

### Internal

The [internal](src/jepsen/sql/workload/internal.clj) workload checks for
[internal
consistency](https://drops.dagstuhl.de/storage/00lipics/lipics-vol042-concur2015/LIPIcs.CONCUR.2015.58/LIPIcs.CONCUR.2015.58.pdf)
(i.e. within individual transactions). It performs simple transactions over a
map of integer keys to integer values. These keys are stored in a table like
so:

```sql
CREATE TABLE IF NOT EXISTS internal (
  id int not null primary key,
  val integer
)
```

Transactions can insert, update, and read values by key, generally interacting
with a small pool of keys which constantly rotate over time. We then check the
internal consistency of each individual transaction by playing forward each
operation it performed, and ensuring that reads observe the effects of previous
operations. Errors from this workload look like:

```clj
{:op {:index 5414,
      :time 13880933098,
      :type :ok,
      :process 3,
      :f :txn,
      :value [[:r 474 6] [:r 474 nil]]},
 :mop [:r 474 nil],
 :k 474,
 :expected 6,
 :actual nil}
```

This transaction performed two micro-operations: a read of key `474` which
observed the value `6`, and then immediately again, observing `nil`. The first
faulty micro-operation was `[:r 474 nil]`; we expected to observe `6`, but
actually observed `nil`. This transaction violates Read Atomic.

### Internal Sim

The [internal-sim](src/jepsen/sql/workload/internal_sim.clj) test is another
approach to verifying internal consistency. It generates random schemas and
transactions over them, then within each transaction, simulates the effects of
that transaction to build up a lower bound on the state of the database. It
flags situations where a select should have observed a row we know existed, but
did not, or where update or delete affected fewer rows than we know they should
have.

Internal-sim should, but does not, maintain a corresponding *upper* bound; it
is unable to identify that a transaction which deletes everything, then reads a
row, has violated internal consistency.

### RW

The [rw](src/jepsen/sql/workload/rw.clj) workload looks for transactional
consistency over a map of integer keys to integer values using
[Elle](https://github.com/jepsen-io/elle). It stores each key in a single row,
spread across several tables, and accesses them either by primary or secondary
key. Transactions perform a mix of reads and writes of unique (for that key)
integers. From there, Elle infers version orders based on a few heuristics, and
from there infers constraints on the dependency graph between transactions. It
looks for a variety of transactional anomalies based both on cycle detection
and other heuristics. For more details, see Elle's
[rw-register](https://github.com/jepsen-io/elle/blob/main/src/elle/rw_register.clj).

RW can encode values in several different ways; see `--encodings`.

In general, the `append` workload is much better at inferring transactional
anomalies. However, `rw` may help narrow down failures.

### Append

The [append](src/jepsen/sql/workload/append.clj) workload looks for transactional
consistency over a map of integer keys to lists of integer values, using over a
map of integer keys to integer values using
[Elle](https://github.com/jepsen-io/elle). It stores each key in a single row,
spread across several tables, and encodes values as a `TEXT` field of
comma-separated values. Transactions acceess rows either by primary or
secondary key. Transactions perform a mix of reads and appends of unique (for
that key) integers. From there, Elle infers version orders based on a few
heuristics, and from there infers constraints on the dependency graph between
transactions. It looks for a variety of transactional anomalies based both on
cycle detection and other heuristics. For more details, see Elle's
[list-append](https://github.com/jepsen-io/elle/blob/main/src/elle/list_append.clj).

Append can indirect operations through a second lookup table.

### Default Value

The [default-value](src/jepsen/sql/workload/default_value.clj) workload creates
tables and adds columns to them with a default value. It concurrently inserts
rows into those tables without providing values, and checks to make sure that
reads observe no rows with NULL values.

## Usage

SQL databases are all alike, except for the ways in which they are very
different. Your test needs to tell this one how to open a JDBC client and how
to interpret errors. The top-level entry point is `jepsen.sql/workloads`

```clj
(jepsen.sql/workloads
  {:open (fn [test node] ...)
   :error-fn (fn [e] ...)})
```

`open` opens a JDBC connection to a node. `error-fn` rewrites errors thrown by
operations on that connection as maps.

This returns a map of workload names (e.g. `:append`) to functions which take
CLI option maps and return a workload (e.g. `{:client ..., :generator ...,
:checker ...}`). You can use these workloads to build a test map.

Workloads use a set of standard keys in the test map, like `:wfr-keys?` and
`:max-writes-per-key`, which control various behaviors. These options are
documented in a data structure, `jepsen.sql/cli-opts`, which can be merged into
your CLI options for `tools.cli`.

See [`jepsen.sql-test`](test/jepsen/sql_test.clj) for example usage.

### Opening a Client

You'll need a function `(open test node)` which takes a Jepsen test map and a
node string, and returns a `next.jdbc` Connection. For example:

```clj
(defn open
  "Opens a connection to the given node."
  [test node]
  (let [spec {:dbtype   "postgresql"
              :host     node
              :port     5432
              :user     "postgres"
              :password "pw"
              :sslmode  "disable"}
        ds    (j/get-datasource spec)
        conn  (j/get-connection ds)]
    conn))
```

### Errors

Each database and each client throw different types of exceptions, and with
different error messages. However, workloads need to understand at least a
little bit of what happened when an error is thrown. To do that, we transform
exceptions using an error function `(error-fn exception)`. For example:

```clj
(defn error-fn
  [e]
  (let [msg (.getMessage e)]
    (condp identical? (class e)
      org.postgresql.util.PSQLException
      (condp re-find msg
        #"current transaction is aborted"
        {:type :txn-aborted
         :definite? true}

        #"duplicate key value"
        {:type :duplicate-key-value
         :definite? true}
        nil)
      nil)))
```

`error-fn` should return either a Clojure map (which will be thrown as an
ExceptionInfo via Slingshot `throw+`), or `nil`, in which case the original
error is thrown as if nothing had happened.

Error maps must have a `:type` key, which may be any object. They can also
include a `:definite?` key, which should be `true` iff the operation performed
in the body which threw had no effects. Definite errors are converted to `:type
:fail` ops; all others are `:type :info`.

**This implies that any `try` form using this error handling should ensure its
effects are atomic.** It would be bad, for example, to run (outside a
transaction):

```clj
(c/with-errors op
  (j/execute! ["INSERT ..."])
  (j/execute! ["INSERT ..."]))
```

If the first `INSERT` succeeded, but the second threw an error which had
`:definite? true`, this `with-errors` form would return a `:type :fail`, even
though some of its effects took place. The clients in this library are careful
not to do this; if you write your own, you need to take care too.

Error maps can have other keys at your discretion.

Any error map which has `:critical? true` will be reported as a failure by the
`:sql` checker included in each workload. We use this to identify things the
database should never do, like returning a string where it should have given a
number.

There are some well-known error types which may be helpful; we suggest you
produce these if possible, as they help the workloads interpret various
failures correctly. They are:

-------------------------------------------------------------------------------
`:type`                   Meaning
------------------------  -----------------------------------------------------
`:aborted`                A transaction has been aborted.

`:column-not-found`       An expected column does not exist. This is normal in
                          some workloads, and disastrous in others.

`:table-not-found`        An expected table does not exist. This is normal in
                          some workloads, and disastrous in others.
-------------------------------------------------------------------------------

## License

Copyright © 2026 Jepsen, LLC

This program and the accompanying materials are made available under the
terms of the Eclipse Public License 2.0 which is available at
https://www.eclipse.org/legal/epl-2.0.

This Source Code may also be made available under the following Secondary
Licenses when the conditions for such availability set forth in the Eclipse
Public License, v. 2.0 are satisfied: GNU General Public License as published by
the Free Software Foundation, either version 2 of the License, or (at your
option) any later version, with the GNU Classpath Exception which is available
at https://www.gnu.org/software/classpath/license.html.
