(ns statechart-viz.fixtures
  "Small charts that between them use every element the library draws."
  (:require
   [com.fulcrologic.statecharts.chart :refer [statechart]]
   [com.fulcrologic.statecharts.elements :refer [state parallel final transition history invoke]]))

(defn- yes? [_ _] true)

(def flat
  "Three states, an event loop and a guarded eventless decision."
  (statechart {}
              (state {:id :gate/idle}
                     (transition {:event :message :target :gate/triage}))
              (state {:id :gate/triage :diagram/kind :decision}
                     (transition {:cond yes? :target :gate/reply :diagram/label "addressed?"})
                     (transition {:target :gate/silent}))
              (state {:id :gate/reply :diagram/kind :success}
                     (transition {:event :done :target :gate/idle}))
              (state {:id :gate/silent :diagram/kind :failure}
                     (transition {:event :done :target :gate/idle}))))

(def nested
  "Compound states three deep, a history node, an internal transition, a
  top-level final next to the main state, and a label with quotes in it."
  (statechart {}
              (state {:id :app :initial :app/running}
                     (transition {:event :quit :target :app/off})
                     (state {:id :app/running :initial :run/work}
                            (history {:id :run/hist} :run/work)
                            (transition {:event :pause :target :app/paused})
                            (state {:id :run/work}
                                   (transition {:event :tick :type :internal})
                                   (state {:id :work/a :diagram/label "step \"A\""}
                                          (transition {:event :next :target :work/b}))
                                   (state {:id :work/b}
                                          (transition {:event :next :target :run/done})))
                            (final {:id :run/done}))
                     (state {:id :app/paused}
                            (transition {:event :resume :target :run/hist})))
              (final {:id :app/off})))

(def parallel-chart
  "Two regions side by side, each ending in finals."
  (statechart {}
              (parallel {:id :decide}
                        (state {:id :axis/who}
                               (state {:id :who/checking}
                                      (transition {:event :who-done :cond yes? :target :who/direct})
                                      (transition {:event :who-done :target :who/broadcast}))
                               (final {:id :who/direct})
                               (final {:id :who/broadcast}))
                        (state {:id :axis/what}
                               (state {:id :what/checking}
                                      (transition {:event [:what-done :timeout] :target :what/noise}))
                               (final {:id :what/noise})))))

(def child
  (statechart {}
              (state {:id :coin/decide}
                     (transition {:cond yes? :target :coin/heads})
                     (transition {:target :coin/tails}))
              (state {:id :coin/heads} (transition {:event :flip :target :coin/decide}))
              (state {:id :coin/tails} (transition {:event :flip :target :coin/decide}))))

(def with-invoke
  "A state that invokes another chart as a child session."
  (statechart {}
              (state {:id :game}
                     (state {:id :game/ready}
                            (transition {:event :play :target :game/playing}))
                     (state {:id :game/playing :diagram/label "Playing"}
                            (invoke {:id :game.child :type :statechart :src `child})
                            (transition {:event :finish :target :game/over}))
                     (final {:id :game/over :diagram/label "Game Over"}))))

(def unnamed
  "A parallel state with no :id, as in the statecharts docs' traffic light."
  (statechart {}
              (parallel {}
                        (state {:id :ew} (state {:id :ew/green}))
                        (state {:id :ns} (state {:id :ns/red})))))

(def all
  {:flat flat :nested nested :parallel parallel-chart :invoke with-invoke :child child
   :unnamed unnamed})
