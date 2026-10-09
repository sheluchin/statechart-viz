(ns statechart-viz.core-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [com.fulcrologic.statecharts :as sc]
   [com.fulcrologic.statecharts.chart :refer [statechart]]
   [com.fulcrologic.statecharts.elements :refer [parallel state]]
   [statechart-viz.core :as viz]
   [statechart-viz.fixtures :as f]
   [statechart-viz.render :as r]))

(defn- count-of [re s] (count (re-seq re s)))

(defn- line-for
  "The DOT line that draws the node labelled `label`."
  [dot label]
  (first (filter #(str/includes? % (str "label=\"" label "\"")) (str/split-lines dot))))

(deftest every-fixture-is-valid-graphviz
  (if-not (r/dot-available?)
    (println "SKIP every-fixture-is-valid-graphviz: Graphviz dot is not on PATH")
    (doseq [[n chart] f/all
            opts [{} {:depth 1}]]
      (testing (str n " " opts)
        (let [{:keys [ok error]} (r/render-dot (viz/dot chart opts) {:format :svg})]
          (is (nil? error))
          (is (str/includes? (String. ^bytes ok "UTF-8") "<svg")))))))

(deftest output-is-stable-and-hides-generated-ids
  (let [a (viz/dot f/nested {:active #{:work/b}})
        b (viz/dot f/nested {:active #{:work/b}})]
    (is (= a b))
    (is (not (re-find #"initial\d+|transition\d+" a)))))

(deftest labels
  (let [dot (viz/dot f/nested)]
    (testing "default label is the last id segment"
      (is (line-for dot "paused")))
    (testing ":diagram/label wins, and quotes are escaped"
      (is (str/includes? dot "label=\"step \\\"A\\\"\"")))))

(deftest unnamed-states-read-as-their-type
  (let [dot (viz/dot f/unnamed)]
    (is (str/includes? dot "label=\"parallel   ∥\""))
    (is (not (re-find #"parallel\d+   ∥" dot)))))

(deftest active-states-are-highlighted
  (let [dot (viz/dot f/flat {:active #{:gate/triage}})]
    (is (str/includes? (line-for dot "triage") "#ffe08a"))
    (is (not (str/includes? (line-for dot "idle") "#ffe08a")))))

(deftest diagram-kind-sets-the-fill
  (let [dot (viz/dot f/flat)]
    (is (str/includes? (line-for dot "reply") "#dcefdf"))
    (is (str/includes? (line-for dot "silent") "#f8e2e2"))))

(deftest element-shapes
  (testing "parallel state is a dashed cluster with a badge"
    (let [dot (viz/dot f/parallel-chart)]
      (is (str/includes? dot "label=\"decide   ∥\""))
      (is (str/includes? dot "style=\"rounded,dashed\""))))
  (testing "final states get a double border"
    (is (str/includes? (line-for (viz/dot f/parallel-chart) "noise") "peripheries=2")))
  (testing "history is an H circle with a dotted edge to its default"
    (let [dot (viz/dot f/nested)]
      (is (str/includes? (line-for dot "H") "shape=\"circle\""))))
  (testing "an invoke is a 3D box named after the child chart"
    (is (str/includes? (viz/dot f/with-invoke) "label=\"⤵ child\" shape=\"box3d\""))))

(deftest top-level-siblings-are-all-drawn
  ;; the old ambient renderer drew only the first top-level state and lost :ambient/off
  (is (line-for (viz/dot f/nested) "off")))

(deftest one-initial-arrow-per-region
  ;; nested: ROOT, app, running, work. parallel: ROOT, who, what (a parallel state has no initial)
  (is (= 4 (count-of #"\"init_s\d+\" -> " (viz/dot f/nested))))
  (is (= 3 (count-of #"\"init_s\d+\" -> " (viz/dot f/parallel-chart)))))

(deftest edge-labels
  (testing "guard without a label is marked [guard] and coloured"
    (let [dot (viz/dot f/parallel-chart)]
      (is (str/includes? dot "label=\" who-done [guard] \""))
      (is (str/includes? dot "color=\"#7a6bbf\""))))
  (testing ":diagram/label replaces the generated text"
    (is (str/includes? (viz/dot f/flat) "label=\" addressed? \"")))
  (testing ":diagram/condition and action labels, in the statecharts library's form"
    (is (str/includes? (viz/dot f/annotated) "label=\" open [unlocked?] / log-open, chime \"")))
  (testing "several events are listed"
    (is (str/includes? (viz/dot f/parallel-chart) "what-done, timeout"))))

(deftest internal-transition-on-a-container-goes-in-its-label
  (let [dot (viz/dot f/nested)]
    (is (str/includes? dot "label=\"work\\n↺ tick\""))
    (is (not (re-find #"-> .*label=\" tick \"" dot)))))

(deftest zoom-by-focus
  (let [dot (viz/dot f/nested {:focus :app/running})]
    (testing "only the focused subtree is drawn"
      (is (line-for dot "running"))
      (is (nil? (line-for dot "off"))))
    (testing "edges leaving the subtree end at a stub"
      (is (str/includes? dot "label=\"↗ paused\"")))
    (testing "the title is a breadcrumb"
      (is (str/includes? dot "label=\"app › running\""))))
  (testing "an unknown focus is an error"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"No state :nope"
                          (viz/dot f/nested {:focus :nope})))))

(deftest zoom-by-depth
  (let [dot (viz/dot f/nested {:depth 1 :active #{:work/b}})]
    (testing "deeper containers collapse to one box with a state count"
      (is (str/includes? dot "label=\"app\\n⊞ 6 states\""))
      (is (nil? (line-for dot "b"))))
    (testing "a collapsed box is highlighted when something inside is active"
      (is (str/includes? (line-for dot "app\\n⊞ 6 states") "#ffe08a")))
    (testing "loops hidden inside the collapsed box are not drawn"
      (is (not (str/includes? dot "tick"))))))

(deftest svg-ids-map-clicks-back-to-states
  (let [dot (viz/dot f/with-invoke {:depth 2})]
    (testing "containers, states and invokes carry ids a viewer can read"
      (is (str/includes? dot "id=\"state:game/playing\"; class=\"sv-container\""))
      (is (str/includes? (line-for dot "Game Over") "id=\"state:game/over\" class=\"sv-state\""))
      (is (str/includes? dot "id=\"invoke:statechart-viz.fixtures/child\" class=\"sv-invoke\""))))
  (testing "collapsed boxes and stubs are marked too"
    (is (str/includes? (viz/dot f/nested {:depth 1}) "id=\"state:app\" class=\"sv-collapsed\""))
    (is (str/includes? (viz/dot f/nested {:focus :app/running}) "id=\"stub:app/paused\" class=\"sv-stub\""))))

(defn- lights
  "Compiles a fresh copy each call, so its unnamed states get new generated ids."
  []
  (statechart {}
              (parallel {}
                        (state {:id :ew} (state {:id :ew/green}))
                        (state {} (state {:id :ns/red}) (state {:id :ns/green}))
                        (state {} (state {:id :walk/off})))))

(deftest unnamed-states-get-stable-svg-ids
  (let [a (lights) b (lights)]
    (is (not= (keys (::sc/elements-by-id a)) (keys (::sc/elements-by-id b)))
        "two compiles really do generate different ids")
    (is (= (viz/dot a {:active #{:ew/green}}) (viz/dot b {:active #{:ew/green}}))
        "but draw the same DOT")
    (let [dot (viz/dot a)]
      (is (str/includes? dot "id=\"state:@parallel.1\"; class=\"sv-container\""))
      (is (str/includes? dot "id=\"state:@parallel.1@state.1\""))
      (is (str/includes? dot "id=\"state:@parallel.1@state.2\""))
      (is (str/includes? dot "id=\"state:ew\"") "named states keep their id")
      (is (not (re-find #"(parallel|state)\d+" dot)) "no generated id anywhere"))
    (testing "outline and zoom-targets carry the same svg ids, to map clicks back"
      (is (= ["state:@parallel.1" "state:@parallel.1@state.1" "state:@parallel.1@state.2" "state:ew"]
             (mapv :svg-id (viz/zoom-targets a))))
      (is (= "state:@parallel.1" (-> (viz/outline a) :children first :svg-id))))))

(deftest outline-and-zoom-targets
  (let [o (viz/outline f/nested #{:work/b})]
    (is (= :root (:kind o)))
    (is (= [:app :app/off] (mapv :id (:children o))))
    (is (true? (-> o :children first :children first :children (nth 1) :children (nth 1) :active?)))
    (is (= [:app :app/running :run/work]
           (mapv :id (viz/zoom-targets f/nested))))
    (is (= ["app" "running" "work"] (:path (last (viz/zoom-targets f/nested)))))))

(deftest render
  (if-not (r/dot-available?)
    (println "SKIP render: Graphviz dot is not on PATH")
    (do
      (testing "png bytes"
        (let [{:keys [ok]} (viz/render f/flat {:format :png})]
          (is (= [-119 80 78 71] (vec (take 4 ok))))))
      (testing "bad DOT is an error value, not an exception"
        (is (:error (r/render-dot "digraph {" {:format :svg})))))))
