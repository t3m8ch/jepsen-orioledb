(ns jepsen.orioledb.checker-test
  (:require [clojure.test :refer [deftest is]]
            [jepsen.checker :as checker]
            [jepsen.orioledb.checker :as orioledb-checker]))

(deftest loss-checker-requires-confirmation-for-each-kill
  (let [check #(checker/check (orioledb-checker/lose-unfsynced-writes-checker)
                              {:lose-unfsynced-writes true} % {})
        dispatch {:type :info :process :nemesis :f :kill :value :all}
        confirmed (assoc dispatch :value {"n1" :cache-cleared})
        failed (assoc dispatch :error "timeout")]
    (is (= {:valid? true :confirmed 1 :unconfirmed 0} (check [dispatch confirmed])))
    (is (= {:valid? false :confirmed 1 :unconfirmed 1}
           (check [dispatch confirmed dispatch failed])))
    (is (false? (:valid? (check [dispatch]))))
    (is (false? (:valid? (check []))))))
