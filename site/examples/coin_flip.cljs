;; A real coin: the guard runs in your browser, so each :flip lands
;; heads or tails at random. The last form must evaluate to a chart.
(ns example.coin-flip
  (:require
   [com.fulcrologic.statecharts.chart :refer [statechart]]
   [com.fulcrologic.statecharts.elements :refer [state transition]]))

(defn heads? [_env _data] (< (rand) 0.5))

(statechart {}
  (state {:id :coin/flipping}
    (transition {:cond heads? :target :coin/heads :diagram/condition "rand < ½"})
    (transition {:target :coin/tails}))
  (state {:id :coin/heads} (transition {:event :flip :target :coin/flipping}))
  (state {:id :coin/tails} (transition {:event :flip :target :coin/flipping})))
