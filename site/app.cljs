(ns app
  "The playground: evaluates the editor's code with SCI, runs the chart it
  returns in a simple-env session, and draws it with Graphviz compiled to
  WASM. One button per event; the ones the active states accept are enabled.

  The URL hash picks what the editor starts with:
    #example=coin_flip   a built-in example, run at once
    #code=<compressed>   a chart from a Share link
    #src=<url>           a chart fetched from a URL (a raw gist or repo file)
  Code from a link runs as soon as the page opens, with full access to it."
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

(defn- show-notice! [msg]
  (set! (.-textContent (el "notice")) (or msg ""))
  (.toggle (.-classList (el "notice")) "hidden" (nil? msg)))

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
  (show-notice! nil)
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

(defn- set-hash! [hash]
  (.replaceState js/history nil "" (str js/location.pathname js/location.search hash)))

(defn- clear-output! []
  (reset! state {})
  (show-error! nil)
  (.replaceChildren (el "events"))
  (.replaceChildren (el "diagram"))
  (set! (.-textContent (el "active")) ""))

(defn load-example! [file]
  (show-notice! nil)
  (set! (.-value (el "example")) file)
  (set! (.-value (el "code")) (aget js/STATECHART_VIZ "examples" file))
  (set-hash! (str "#example=" file))
  (start!))

;; ---------------------------------------------------------------------------
;; Share links: the code, deflated and base64url-encoded, in the URL hash

(defn- piped
  "Bytes of `text` (a string) or `bytes` sent through a compression stream."
  [input stream]
  (-> (js/Response. (.pipeThrough (.stream (js/Blob. #js [input])) stream))
      (.arrayBuffer)
      (.then #(js/Uint8Array. %))))

(defn- bytes->base64url [bytes]
  (let [bin (loop [i 0 acc (transient [])]
              (if (< i (.-length bytes))
                (recur (inc i) (conj! acc (js/String.fromCharCode (aget bytes i))))
                (apply str (persistent! acc))))]
    (-> (js/btoa bin) (str/replace "+" "-") (str/replace "/" "_") (str/replace #"=+$" ""))))

(defn- base64url->bytes [s]
  (let [bin (js/atob (-> s (str/replace "-" "+") (str/replace "_" "/")))
        out (js/Uint8Array. (.-length bin))]
    (dotimes [i (.-length bin)] (aset out i (.charCodeAt bin i)))
    out))

(defn- encode [code]
  (.then (piped code (js/CompressionStream. "deflate-raw")) bytes->base64url))

(defn- decode [s]
  (-> (js/Promise.resolve s)
      (.then #(piped (base64url->bytes %) (js/DecompressionStream. "deflate-raw")))
      (.then #(.decode (js/TextDecoder.) %))))

(defn share! []
  (-> (encode (.-value (el "code")))
      (.then (fn [encoded]
               (set-hash! (str "#code=" encoded))
               (let [link (.-href js/location)]
                 (-> (.writeText js/navigator.clipboard link)
                     (.then #(show-notice! (str "Link copied (" (count link) " characters). "
                                                "Anyone who opens it gets this code.")))
                     (.catch #(show-notice! (str "Copy this link: " link)))))))))

(defn- load-shared! [code source]
  (clear-output!)
  (set! (.-value (el "example")) "")
  (set! (.-value (el "code")) code)
  (start!)
  (show-notice! (str "Chart loaded from " source ".")))

(defn- hash-params []
  (let [h (subs js/location.hash 1)]
    (into {} (for [part (str/split h #"&")
                   :let [i (str/index-of part "=")]
                   :when i]
               [(subs part 0 i) (let [v (subs part (inc i))]
                                  (try (js/decodeURIComponent v) (catch :default _ v)))]))))

(defn load-from-hash!
  "Fill the editor from the URL hash, or with the first example."
  []
  (let [{:strs [code src example]} (hash-params)
        fail #(do (clear-output!) (show-error! (str "Could not load the linked chart: " %)))]
    (cond
      code    (-> (decode code)
                  (.then #(load-shared! % "a share link"))
                  (.catch #(fail "the link is truncated or damaged.")))
      src     (-> (js/fetch src)
                  (.then #(if (.-ok %) (.text %) (throw (js/Error. (str "HTTP " (.-status %))))))
                  (.then #(load-shared! % src))
                  (.catch #(fail (str src ": " (.-message %)))))
      (and example (aget js/STATECHART_VIZ "examples" example)) (load-example! example)
      :else   (load-example! (first (first examples))))))

(defn init []
  (let [picker (el "example")
        shared (js/document.createElement "option")]
    (set! (.-value shared) "")
    (set! (.-textContent shared) "Shared chart")
    (set! (.-hidden shared) true)
    (.append picker shared)
    (doseq [[file label] examples]
      (let [o (js/document.createElement "option")]
        (set! (.-value o) file)
        (set! (.-textContent o) label)
        (.append picker o)))
    (.addEventListener picker "change" #(load-example! (.-value picker)))
    (.addEventListener (el "run") "click" start!)
    (.addEventListener (el "reset") "click" start!)
    (.addEventListener (el "share") "click" share!)
    (.addEventListener js/window "hashchange" load-from-hash!)
    (.addEventListener (el "code") "keydown"
                       #(when (and (= "Enter" (.-key %)) (or (.-metaKey %) (.-ctrlKey %)))
                          (.preventDefault %)
                          (start!)))
    (load-from-hash!)))

(init)
