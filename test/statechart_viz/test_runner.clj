(ns statechart-viz.test-runner
  (:require
   [clojure.test :as t]
   [statechart-viz.core-test]
   [taoensso.timbre :as log]))

(defn -main [& _]
  (log/set-min-level! :warn)
  (let [{:keys [fail error]} (t/run-tests 'statechart-viz.core-test)]
    (System/exit (if (zero? (+ fail error)) 0 1))))
