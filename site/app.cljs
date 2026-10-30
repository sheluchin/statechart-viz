(ns app
  "The playground: evaluates the editor's code with SCI, runs the chart it
  returns in a simple-env session, and draws it with Graphviz compiled to
  WASM. One button per event; the ones the active states accept are enabled.
  Click a box in the drawing to zoom into it and a `↗` stub to step back out
  (statechart-viz.nav/moves); the wheel magnifies it and a drag pans it.

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
;; the drawing's magnification: a scale and an offset, as the explorer's
(defonce zoom (atom {:s 1 :x 0 :y 0}))
(defonce drag (atom nil))
(def max-zoom 8)

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

(declare send! go!)

;; ---------------------------------------------------------------------------
;; Zoom: the wheel magnifies at the pointer, a drag pans, − + Fit and the keys
;; - + 0 do the same. Magnified, the drawing's box grows to three quarters of
;; the window, so a wide chart isn't seen through a slit

(defn- canvas [] (.-firstElementChild (el "diagram")))

(defn- clamp! []
  (when-let [c (canvas)]
    (let [stage (el "diagram")
          {:keys [s x y]} @zoom
          cw (.-offsetWidth c)
          ch (.-offsetHeight c)]
      (set! (.. stage -style -height)
            (if (= 1 s) "" (str (max ch (min (* ch s) (* 0.75 js/window.innerHeight))) "px")))
      (swap! zoom assoc
             :x (min 0 (max (- (.-clientWidth stage) (* cw s)) x))
             :y (min 0 (max (- (.-clientHeight stage) (* ch s)) y))))))

(defn- show-zoom! []
  (let [{:keys [s x y]} @zoom]
    (when-let [c (canvas)]
      (set! (.. c -style -transform) (if (= 1 s) "" (str "translate(" x "px," y "px) scale(" s ")"))))
    (set! (.-textContent (el "zoom-level")) (str (js/Math.round (* 100 s)) "%"))
    (set! (.-disabled (el "zoom-out")) (= 1 s))
    (set! (.-disabled (el "zoom-fit")) (= 1 s))
    (set! (.-disabled (el "zoom-in")) (>= s max-zoom))
    (.toggle (.-classList (el "diagram")) "zoomed" (> s 1))))

(defn- zoom-at! [f px py]
  (let [{:keys [s x y]} @zoom
        s2 (min max-zoom (max 1 (* s f)))]
    (when (not= s s2)
      (reset! zoom {:s s2 :x (- px (* (- px x) (/ s2 s))) :y (- py (* (- py y) (/ s2 s)))})
      (clamp!)
      (show-zoom!))))

(defn- zoom-mid! [f]
  (let [stage (el "diagram")]
    (zoom-at! f (/ (.-clientWidth stage) 2) (/ (.-clientHeight stage) 2))))

(defn- fit! []
  (reset! zoom {:s 1 :x 0 :y 0})
  (clamp!)
  (show-zoom!))

;; ---------------------------------------------------------------------------
;; Clicking through: the view is a stack of focuses, the whole chart (:ROOT)
;; at the bottom. Its moves (viz/moves) become clicks on the drawing

(defn- label-of [chart focus]
  (if (= :ROOT focus)
    (or (:diagram/label chart) "Whole chart")
    (or (some #(when (= focus (:id %)) (:label %)) (viz/zoom-targets chart)) (str focus))))

(defn- link-moves!
  "Mark the boxes and stubs of `svg` that go somewhere, and keep where."
  [svg chart focus]
  (let [{:keys [in stubs]} (viz/moves chart focus)
        drawn (into {} (map (fn [g] [(.-id g) g])) (js/Array.from (.querySelectorAll svg "g[id]")))
        goes  (concat (for [{:keys [id label svg-id]} in] [svg-id id (str "Zoom into " label)])
                      (for [{:keys [to svg-id]} stubs] [svg-id to (str "Out to " (label-of chart to))]))]
    (swap! state assoc :go
           (into {} (for [[svg-id to hint] goes
                          :let [g (get drawn svg-id)]
                          :when g]
                      (do (.add (.-classList g) "go")
                          (.setAttribute g "data-go" svg-id)
                          (.setAttribute g "tabindex" "0")
                          (.setAttribute g "role" "link")
                          (.setAttribute g "aria-label" hint)
                          (some-> (.querySelector g "title") (set! -textContent hint))
                          [svg-id to]))))))

(defn- show-crumbs! [chart views]
  (let [box (el "crumbs")]
    (.replaceChildren box)
    (doseq [[i focus] (map-indexed vector views)]
      (when (pos? i)
        (let [s (js/document.createElement "span")]
          (set! (.-className s) "sep")
          (set! (.-textContent s) "›")
          (.append box s)))
      (let [b (js/document.createElement "button")]
        (set! (.-type b) "button")
        (set! (.-textContent b) (label-of chart focus))
        (if (= i (dec (count views)))
          (.setAttribute b "aria-current" "page")
          (.addEventListener b "click" #(go! focus)))
        (.append box b)))))

(defn- draw!
  "Draw the chart as it is now. `keep-zoom?` keeps the magnification: an event
  changes what is active, not what the reader is looking at."
  ([] (draw! false))
  ([keep-zoom?]
   (let [{:keys [chart wmem log views]} @state
         focus  (peek views)
         active (::sc/configuration wmem)
         title  (if (seq log) (str "after " (str/join ", " log)) "started")]
     (-> graphviz
         (.then (fn [g]
                  (let [svg (.renderSVGElement g (viz/dot chart {:active active :title title :focus focus}))
                        c   (js/document.createElement "div")]
                    (set! (.-className c) "canvas")
                    (.append c svg)
                    (.replaceChildren (el "diagram") c)
                    (link-moves! svg chart focus)
                    (show-crumbs! chart views)
                    (if keep-zoom? (do (clamp!) (show-zoom!)) (fit!)))))
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
             "Finished: the chart reached a top-level final state. Reset to start again.")))))

(defn go!
  "Show the view focused on `focus`: back to it when it is in the breadcrumb,
  else one step further."
  [focus]
  (let [views (:views @state)
        i     (.indexOf views focus)]
    (swap! state assoc :views (if (neg? i) (conj views focus) (subvec views 0 (inc i))))
    (draw!)))

(defn send! [event]
  (let [{:keys [env wmem]} @state
        proc (::sc/processor env)]
    (swap! state assoc
           :wmem (sp/process-event! proc env wmem (new-event event))
           :log (conj (:log @state) event))
    (draw! true)))

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
      (reset! state {:chart chart :env env :wmem wmem :log [] :views [:ROOT]})
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
  (.replaceChildren (el "crumbs"))
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

(defn- wire-diagram!
  "Clicks, keys, the wheel and drags on the drawing."
  []
  (let [stage    (el "diagram")
        dragged? (atom false)
        target   #(some-> (.-target %) (.closest "[data-go]") (.getAttribute "data-go"))
        follow!  #(when-let [to (get (:go @state) %)] (go! to))
        end!     (fn [] (reset! drag nil) (.remove (.-classList stage) "dragging"))]
    (.addEventListener stage "click"
                       #(if @dragged? (reset! dragged? false) (some-> (target %) follow!)))
    (.addEventListener stage "keydown"
                       #(when-let [id (and (#{"Enter" " "} (.-key %)) (target %))]
                          (.preventDefault %)
                          (follow! id)))
    ;; at the fitted size, scrolling down scrolls the page instead
    (.addEventListener stage "wheel"
                       (fn [e]
                         (let [dy (* (.-deltaY e) (case (.-deltaMode e) 1 16 2 400 1))]
                           (when-not (and (>= dy 0) (<= (:s @zoom) 1) (not (.-ctrlKey e)))
                             (.preventDefault e)
                             (let [r (.getBoundingClientRect stage)]
                               (zoom-at! (js/Math.exp (* -0.002 dy)) (- (.-clientX e) (.-left r)) (- (.-clientY e) (.-top r)))))))
                       #js {:passive false})
    ;; a drag pans a magnified drawing; the click that ends it opens nothing
    (.addEventListener stage "pointerdown"
                       (fn [e]
                         (reset! dragged? false)
                         (when (and (> (:s @zoom) 1) (zero? (.-button e)))
                           (reset! drag {:x (.-clientX e) :y (.-clientY e) :zx (:x @zoom) :zy (:y @zoom)
                                         :id (.-pointerId e) :on false}))))
    (.addEventListener stage "pointermove"
                       (fn [e]
                         (when-let [{:keys [x y zx zy id on]} @drag]
                           (let [dx (- (.-clientX e) x) dy (- (.-clientY e) y)]
                             (when (or on (>= (+ (js/Math.abs dx) (js/Math.abs dy)) 5))
                               (when-not on
                                 (swap! drag assoc :on true)
                                 (reset! dragged? true)
                                 (.setPointerCapture stage id)
                                 (.add (.-classList stage) "dragging"))
                               (swap! zoom assoc :x (+ zx dx) :y (+ zy dy))
                               (clamp!)
                               (show-zoom!))))))
    (.addEventListener stage "pointerup" end!)
    (.addEventListener stage "pointercancel" end!)
    (.addEventListener (el "zoom-in") "click" #(zoom-mid! 1.5))
    (.addEventListener (el "zoom-out") "click" #(zoom-mid! (/ 1 1.5)))
    (.addEventListener (el "zoom-fit") "click" fit!)
    (.addEventListener js/window "resize" #(do (clamp!) (show-zoom!)))
    (.addEventListener js/document "keydown"
                       (fn [e]
                         (when-not (or (.-ctrlKey e) (.-metaKey e) (.-altKey e)
                                       (#{"INPUT" "TEXTAREA" "SELECT"} (.. e -target -tagName)))
                           (case (.-key e)
                             "Escape" (let [views (:views @state)]
                                        (when (> (count views) 1) (go! (peek (pop views)))))
                             ("+" "=") (zoom-mid! 1.5)
                             "-" (zoom-mid! (/ 1 1.5))
                             "0" (fit!)
                             nil))))))

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
    (.addEventListener (el "copy-link") "click" share!)
    (.addEventListener js/window "hashchange" load-from-hash!)
    (.addEventListener (el "code") "keydown"
                       #(when (and (= "Enter" (.-key %)) (or (.-metaKey %) (.-ctrlKey %)))
                          (.preventDefault %)
                          (start!)))
    (wire-diagram!)
    (load-from-hash!)))

(init)
