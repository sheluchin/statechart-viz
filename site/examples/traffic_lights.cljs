;; The traffic light from the statecharts docs: four parallel regions.
;; The last form must evaluate to a chart.
(ns example.traffic-lights
  (:require
   [com.fulcrologic.statecharts.chart :refer [statechart]]
   [com.fulcrologic.statecharts.elements :refer [state parallel transition]]
   [com.fulcrologic.statecharts.util :refer [extend-key]]))

(defn signal [id initial]
  (let [k #(extend-key id %)]
    (state {:id id :initial (k (name initial))}
      (state {:id (k "red")} (transition {:event :swap-flow :target (k "green")}))
      (state {:id (k "yellow")} (transition {:event :swap-flow :target (k "red")}))
      (state {:id (k "green")} (transition {:event :warn-traffic :target (k "yellow")})))))

(defn ped-signal [id initial]
  (let [k #(extend-key id %)]
    (state {:id id :initial (k (name initial))}
      (state {:id (k "red")} (transition {:event :swap-flow :target (k "white")}))
      (state {:id (k "flashing-white")} (transition {:event :swap-flow :target (k "red")}))
      (state {:id (k "white")} (transition {:event :warn-pedestrians :target (k "flashing-white")})))))

(statechart {}
  (parallel {:diagram/label "Traffic lights"}
    (signal :east-west :green)
    (signal :north-south :red)
    (ped-signal :cross-ew :red)
    (ped-signal :cross-ns :white)))
