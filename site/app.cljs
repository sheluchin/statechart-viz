(ns app
  "The playground: evaluates the editor's code with SCI, runs the chart it
  returns in a simple-env session, and draws it with Graphviz compiled to
  WASM. One button per event; the ones the active states accept are enabled."
  (:require
   [clojure.string :as str]
   [com.fulcrologic.statecharts :as sc]
   [com.fulcrologic.statecharts.events :refer [new-event]]
   [com.fulcrologic.statecharts.protocols :as sp]
   [com.fulcrologic.statecharts.simple :as simple]
   [statechart-viz.core :as viz]))

(def examples
  [["traffic_lights" "Traffic lights"]
   ["coin_flip" "Coin flip"]
   ["player" "Media player"]
   ["checkout" "Checkout"]])

(defonce state (atom {}))
(defonce graphviz (.instance js/Viz))

(defn- el [id] (js/document.getElementById id))

(defn- transitions [chart]
  (filter #(= :transition (:node-type %)) (vals (::sc/elements-by-id chart))))

(defn- event-names [t]
  (let [e (:event t)] (if (sequential? e) e (when e [e]))))

(defn- events
  "Every event the chart names, each with whether an active state handles it."
  [chart active]
  (->> (transitions chart)
       (mapcat (fn [t] (map #(vector % (contains? active (:parent t))) (event-names t))))
       (reduce (fn [m [e on?]] (update m e #(or % on?))) {})
       (sort-by (comp str key))))

(defn- show-error! [msg]
  (set! (.-textContent (el "error")) (or msg ""))
  (.toggle (.-classList (el "error")) "hidden" (nil? msg)))

(declare send!)

(defn- draw! []
  (let [{:keys [chart wmem log]} @state
        active (::sc/configuration wmem)
        title  (if (seq log) (str "after " (str/join ", " log)) "started")]
    (-> graphviz
        (.then (fn [g]
                 (let [svg (.renderSVGElement g (viz/dot chart {:active active :title title}))]
                   (.replaceChildren (el "diagram") svg))))
        (.catch #(show-error! (str "Graphviz: " (.-message %)))))
    (let [box (el "events")]
      (.replaceChildren box)
      (doseq [[e on?] (events chart active)]
        (let [b (js/document.createElement "button")]
          (set! (.-textContent b) (str e))
          (set! (.-disabled b) (not on?))
          (.addEventListener b "click" #(send! e))
          (.append box b))))
    (set! (.-textContent (el "active"))
          (if (seq active)
            (str/join "  " (sort (map str active)))
            "Finished: the chart reached a top-level final state. Reset to start again."))))

(defn send! [event]
  (let [{:keys [env wmem]} @state
        proc (::sc/processor env)]
    (swap! state assoc
           :wmem (sp/process-event! proc env wmem (new-event event))
           :log (conj (:log @state) event))
    (draw!)))

(defn start! []
  (show-error! nil)
  (try
    (let [chart (js/scittle.core.eval_string (.-value (el "code")))
          _     (when-not (::sc/elements-by-id chart)
                  (throw (ex-info "The last form must evaluate to a chart, e.g. (statechart {} ...)" {})))
          env   (simple/simple-env)
          _     (simple/register! env ::chart chart)
          proc  (::sc/processor env)
          wmem  (sp/start! proc env ::chart {::sc/session-id (random-uuid)})]
      (reset! state {:chart chart :env env :wmem wmem :log []})
      (draw!))
    (catch :default e
      (show-error! (ex-message e)))))

(defn load-example! [file]
  (set! (.-value (el "code")) (aget js/STATECHART_VIZ "examples" file))
  (start!))

(defn init []
  (let [picker (el "example")]
    (doseq [[file label] examples]
      (let [o (js/document.createElement "option")]
        (set! (.-value o) file)
        (set! (.-textContent o) label)
        (.append picker o)))
    (.addEventListener picker "change" #(load-example! (.-value picker)))
    (.addEventListener (el "run") "click" start!)
    (.addEventListener (el "reset") "click" start!)
    (.addEventListener (el "code") "keydown"
                       #(when (and (= "Enter" (.-key %)) (or (.-metaKey %) (.-ctrlKey %)))
                          (.preventDefault %)
                          (start!)))
    (load-example! (first (first examples)))))

(init)
