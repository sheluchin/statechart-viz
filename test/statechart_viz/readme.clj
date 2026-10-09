(ns statechart-viz.readme
  "The pictures in README.md: `bb readme-images` writes doc/<name>.dot and,
  with Graphviz `dot` on PATH, doc/<name>.svg. The traffic light is the
  example from https://fulcrologic.github.io/statecharts/, run for real.

  The SVGs in the repo were drawn inside `nix develop`, with the Graphviz
  flake.lock pins. Graphviz 2.43 draws the traffic light's regions in reverse
  order (see README.md)."
  (:require
   [clojure.java.io :as io]
   [com.fulcrologic.statecharts :as sc]
   [com.fulcrologic.statecharts.chart :refer [statechart]]
   [com.fulcrologic.statecharts.elements :refer [final history invoke parallel script state transition]]
   [com.fulcrologic.statecharts.events :refer [new-event]]
   [com.fulcrologic.statecharts.protocols :as sp]
   [com.fulcrologic.statecharts.simple :as simple]
   [com.fulcrologic.statecharts.util :refer [extend-key]]
   [statechart-viz.core :as viz]
   [taoensso.timbre :as log]))

(defn- signal [id initial]
  (let [k #(extend-key id %)]
    (state {:id id :initial (k (name initial))}
           (state {:id (k "red")} (transition {:event :swap-flow :target (k "green")}))
           (state {:id (k "yellow")} (transition {:event :swap-flow :target (k "red")}))
           (state {:id (k "green")} (transition {:event :warn-traffic :target (k "yellow")})))))

(defn- ped-signal [id initial]
  (let [k #(extend-key id %)]
    (state {:id id :initial (k (name initial))}
           (state {:id (k "red")} (transition {:event :swap-flow :target (k "white")}))
           (state {:id (k "flashing-white")} (transition {:event :swap-flow :target (k "red")}))
           (state {:id (k "white")} (transition {:event :warn-pedestrians :target (k "flashing-white")})))))

(def traffic-lights
  (statechart {}
              (parallel {:diagram/label "Traffic lights"}
                        (signal :east-west :green)
                        (signal :north-south :red)
                        (ped-signal :cross-ew :red)
                        (ped-signal :cross-ns :white))))

(defn- traffic-config
  "The lights' configuration after `events`, from a real session."
  [events]
  (let [env  (simple/simple-env)
        _    (simple/register! env ::lights traffic-lights)
        proc (::sc/processor env)
        wmem (reduce #(sp/process-event! proc env %1 (new-event %2))
                     (sp/start! proc env ::lights {::sc/session-id 1})
                     events)]
    (::sc/configuration wmem)))

(def player
  "Nesting, history, an internal transition, a self-loop and finals."
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
              (final {:id :player/unplugged})))

(def playing #{:player :player/on :on/playing :playing/track})

(defn- yes? [_ _] true)

(def checkout
  "Annotated: guard text, actions and kind colours."
  (statechart {}
              (state {:id :checkout/cart}
                     (transition {:event :pay :target :checkout/charging}))
              (state {:id :checkout/charging :diagram/kind :waiting}
                     (transition {:event :charged :cond yes? :target :checkout/paid
                                  :diagram/condition "amount matches"}
                                 (script {:expr (fn [_ _] nil) :diagram/label "send receipt"}))
                     (transition {:event :charged :target :checkout/review})
                     (transition {:event :declined :target :checkout/failed}))
              (state {:id :checkout/review :diagram/kind :decision}
                     (transition {:event :approve :target :checkout/paid})
                     (transition {:event :reject :target :checkout/failed}))
              (final {:id :checkout/paid :diagram/kind :success})
              (final {:id :checkout/failed :diagram/kind :failure})))

(def coin
  (statechart {}
              (state {:id :coin/flipping}
                     (transition {:cond yes? :target :coin/heads :diagram/condition "rand < ½"})
                     (transition {:target :coin/tails}))
              (state {:id :coin/heads} (transition {:event :flip :target :coin/flipping}))
              (state {:id :coin/tails} (transition {:event :flip :target :coin/flipping}))))

(def game
  "A state that invokes `coin` as a child session."
  (statechart {}
              (state {:id :game}
                     (state {:id :game/ready}
                            (transition {:event :play :target :game/playing}))
                     (state {:id :game/playing :diagram/label "Playing"}
                            (invoke {:id :game.coin :type :statechart :src `coin})
                            (transition {:event :finish :target :game/over}))
                     (final {:id :game/over :diagram/label "Game Over"}))))

(defn views []
  [["traffic-lights" traffic-lights {:active (traffic-config [:warn-pedestrians :warn-traffic])
                                     :title  "after :warn-pedestrians, :warn-traffic"}]
   ["player"         player   {:active playing}]
   ["zoom-focus"     player   {:active playing :focus :player/on}]
   ["zoom-depth"     player   {:active playing :depth 2}]
   ["invoke"         game     {:active #{:game :game/playing}}]
   ["invoke-child"   coin     {:active #{:coin/tails} :title "game › Playing › ⤵ coin"}]
   ["checkout"       checkout {:active #{:checkout/charging}}]])

(defn -main [& [dir]]
  (log/set-min-level! :warn)
  (let [dir (or dir "doc")]
    (.mkdirs (io/file dir))
    (doseq [[n chart opts] (views)]
      (spit (str dir "/" n ".dot") (viz/dot chart opts))
      (let [{:keys [ok error]} (viz/render chart (assoc opts :format :svg))]
        (if ok
          (do (with-open [o (io/output-stream (str dir "/" n ".svg"))] (.write o ^bytes ok))
              (println "wrote" (str dir "/" n ".svg")))
          (println "dot only" n error))))))
