(ns jepsen.orioledb.checker
  (:require [jepsen.checker :as checker]))

(defn lose-unfsynced-writes-checker []
  (reify checker/Checker
    (check [_ test history _]
      (if-not (:lose-unfsynced-writes test)
        {:valid? true :disabled true}
        (let [kills (filter #(and (= :nemesis (:process %))
                                  (= :info (:type %))
                                  (= :kill (:f %)))
                            history)
              ;; Jepsen journals both dispatch and completion as :info.
              attempts (partition-all 2 kills)
              confirmed? (fn [[_ {:keys [error exception value]}]]
                           (and (not error) (not exception) (map? value) (seq value)
                                (every? #{:cache-cleared} (vals value))))
              [confirmed unconfirmed] ((juxt filter remove) confirmed? attempts)]
          {:valid? (and (boolean (seq confirmed)) (empty? unconfirmed))
           :confirmed (count confirmed)
           :unconfirmed (count unconfirmed)})))))
