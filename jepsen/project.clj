(defproject jepsen-orioledb "0.1.0-SNAPSHOT"
  :description "Local reproducible OrioleDB tests using Jepsen"
  :dependencies [[org.clojure/clojure "1.12.6"]
                 [jepsen "0.3.15"]
                 [com.github.seancorfield/next.jdbc "1.4.72"]
                 [org.postgresql/postgresql "42.7.14"]
                 [com.gfredericks/test.chuck "0.2.15"]
                 [org.clojure/test.check "1.1.3"]
                 [cheshire "6.2.0"]
                 [clj-wallhack "1.0.1"]]
  :source-paths ["src"
                 "../vendor/jepsen-sql/src"
                 "../vendor/jepsen-postgres/postgres/src"]
  :resource-paths ["resources"
                   "../vendor/jepsen-postgres/postgres/resources"]
  :test-paths ["test"]
  :main jepsen.orioledb.cli
  :jvm-opts ["-Xmx2g" "-Djava.awt.headless=true" "-server"]
  :profiles {:uberjar {:aot :all}})
