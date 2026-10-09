;; Nesting, history, an internal transition, a self-loop and finals.
;; The last form must evaluate to a chart.
(ns example.player
  (:require
   [com.fulcrologic.statecharts.chart :refer [statechart]]
   [com.fulcrologic.statecharts.elements :refer [state final transition history]]))

(statechart {}
  (state {:id :player :initial :player/on}
    (transition {:event :unplug :target :player/unplugged})
    (state {:id :player/on :initial :on/stopped}
      (history {:id :on/resume} :on/stopped)
      (transition {:event :power :target :player/off})
      (state {:id :on/stopped}
        (transition {:event :play :target :playing/track}))
      (state {:id :on/playing}
        (transition {:event :volume :type :internal})
        (transition {:event :stop :target :on/stopped})
        (state {:id :playing/track}
          (transition {:event :next :target :playing/track})
          (transition {:event :ended :target :playing/done}))
        (final {:id :playing/done})))
    (state {:id :player/off}
      (transition {:event :power :target :on/resume})))
  (final {:id :player/unplugged}))
