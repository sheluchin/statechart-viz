;; Annotated: guard text, actions and kind colours.
;; The last form must evaluate to a chart.
(ns example.checkout
  (:require
   [com.fulcrologic.statecharts.chart :refer [statechart]]
   [com.fulcrologic.statecharts.elements :refer [state final transition script]]))

(defn amount-ok? [_env _data] (< (rand) 0.7))

(statechart {}
  (state {:id :checkout/cart}
    (transition {:event :pay :target :checkout/charging}))
  (state {:id :checkout/charging :diagram/kind :waiting}
    (transition {:event :charged :cond amount-ok? :target :checkout/paid
                 :diagram/condition "amount matches"}
      (script {:expr (fn [_ _] (js/console.log "receipt sent")) :diagram/label "send receipt"}))
    (transition {:event :charged :target :checkout/review})
    (transition {:event :declined :target :checkout/failed}))
  (state {:id :checkout/review :diagram/kind :decision}
    (transition {:event :approve :target :checkout/paid})
    (transition {:event :reject :target :checkout/failed}))
  (final {:id :checkout/paid :diagram/kind :success})
  (final {:id :checkout/failed :diagram/kind :failure}))
