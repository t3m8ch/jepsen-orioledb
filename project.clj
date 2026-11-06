(defproject io.jepsen/sql "0.1.2-SNAPSHOT"
  :description "A library for writing Jepsen tests of SQL databases"
  :url "https://github.com/jepsen-io/sql"
  :license {:name "EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0"
            :url "https://www.eclipse.org/legal/epl-2.0/"}
  :dependencies [[org.clojure/clojure "1.12.6" :scope "provided"]
                 [jepsen "0.3.14"]
                 [com.github.seancorfield/next.jdbc "1.3.1118"]
                 [com.gfredericks/test.chuck "0.2.15"]
                 [org.clojure/test.check "1.1.3"]]
  :repl-options {:init-ns io.jepsen.sql}
  :profiles {:dev {:dependencies [[org.postgresql/postgresql "42.7.13"]]}}
  :test-selectors {:default (fn [m] (not (or (:perf m)
                                             (:slow m))))
                   :all         (fn [m] true)
                   :focus       :focus})

