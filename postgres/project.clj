(defproject io.jepsen/postgres "0.1.3-SNAPSHOT"
  :description "Jepsen tests for PostgreSQL."
  :url "https://github.com/jepsen-io/postgres"
  :scm     {:name "git"
            :url "https://github.com/jepsen-io/jepsen/"
            :dir ".."}
  :license {:name "EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0"
            :url "https://www.eclipse.org/legal/epl-2.0/"}
  :dependencies [[org.clojure/clojure "1.12.5"]
                 [jepsen "0.3.13"]
                 [io.jepsen/sql "0.1.0"]
                 [com.github.seancorfield/next.jdbc "1.3.1093"]
                 [org.postgresql/postgresql "42.7.11"]
                 [cheshire "6.2.0"]
                 [clj-wallhack "1.0.1"]]
  :main jepsen.postgres.cli
  :aot [jepsen.postgres.cli]
  :jvm-opts ["-Djava.awt.headless=true"
             "-server"]
  :repl-options {:init-ns jepsen.postgres}
  :profiles {:uberjar {:aot :all}})
