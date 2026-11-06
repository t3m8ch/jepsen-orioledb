(ns jepsen.sql.checker-test
  (:require [clojure [test :refer :all]]
            [jepsen [checker :as checker]
                    [history :as h]]
            [jepsen.sql.checker :as c]))

(deftest ^:focus missing-table-column-test
  (is (= {:valid? false
          :error-types [:table-not-found]
          :errors {:table-not-found
                   {:count 1
                    :example (h/op {:process 0
                                    :type :info
                                    :f nil
                                    :value nil
                                    :error {:type :table-not-found}
                                    :index 7
                                    :time 7})}}}
         (checker/check
           (c/missing-table-column-checker)
           {:nodes ["n1" "n2"]}
           (h/history
             [{:index 0, :time 0, :process 0, :type :invoke}
              {:index 1, :time 1, :process 0, :type :ok}
              ; This is fine; we haven't finished the initial phase yet.
              {:index 2, :time 2, :process 1, :type :invoke}
              {:index 3, :time 3, :process 1, :type :info, :error {:type :table-not-found}}
              ; Finish
              {:index 4, :time 4, :process 1, :type :invoke}
              {:index 5, :time 5, :process 1, :type :ok}
              ; Now hit an error
              {:index 6, :time 6, :process 0, :type :invoke}
              {:index 7, :time 7, :process 0, :type :info, :error {:type :table-not-found}}])
           {}))))
