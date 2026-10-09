(ns statechart-viz.model
  "Turn any compiled Fulcro statechart into a plain tree of states plus a flat
  list of transitions. Everything downstream (DOT, outlines, nav menus) reads
  this model, never the element map, so the library knows nothing about any
  particular chart.

  Charts can annotate elements with optional `:diagram/*` keys. The first two
  are the statecharts library's own conventions (see its chart/diagram-label
  and chart/transition-label):
    :diagram/label      display text for a state, a transition, or an action
                        inside a transition
    :diagram/condition  what a transition's guard checks, shown as [text]
    :diagram/kind       ours: a keyword the theme maps to a fill colour"
  (:require
   [clojure.string :as str]
   [com.fulcrologic.statecharts :as sc]))

(def ^:private state-types #{:state :parallel :final :history})

(defn- elements [chart] (::sc/elements-by-id chart))

(defn- document-order
  "Element ids in document order, so output is stable across runs."
  [chart]
  (let [order (::sc/ids-in-document-order chart)]
    (if (seq order)
      (into {} (map-indexed (fn [i id] [id i]) order))
      {})))

(defn- children-of
  "Elements whose :parent is `parent-id`, in document order."
  [elems order parent-id]
  (->> (vals elems)
       (filter #(= parent-id (:parent %)))
       (sort-by #(get order (:id %) Long/MAX_VALUE))))

(defn- initial-pseudo? [el] (boolean (:initial? el)))

(defn- event-list [t]
  (let [e (:event t)]
    (cond (nil? e) []
          (keyword? e) [e]
          (sequential? e) (vec e)
          :else [e])))

(defn- default-label
  "The last `/` or `.` segment of the id's name: nesting already shows the
  rest, so `:round1.heads.round2` reads as `round2` inside `heads`."
  [id]
  (let [n (if (keyword? id) (name id) (str id))]
    (last (str/split n #"[/.]"))))

(defn generated-id?
  "True for an id the statecharts library made up because the chart gave
  none: `(genid \"parallel\")` gives `:parallel29883`, and the number
  changes every run."
  [el]
  (let [id (:id el) t (:node-type el)]
    (boolean (and (keyword? id) (nil? (namespace id)) (keyword? t)
                  (re-matches (re-pattern (str "\\Q" (name t) "\\E\\d+")) (name id))))))

(defn label
  "Display text for an element. An unnamed element reads as its type."
  [el]
  (or (:diagram/label el)
      (if (generated-id? el) (name (:node-type el)) (default-label (:id el)))))

(defn- src-label [src]
  (cond (symbol? src) (let [ns-part (namespace src)
                            short   (when ns-part (last (str/split ns-part #"\.")))]
                        ;; my.app.coin/chart reads as "coin"; my.app/child as "child"
                        (if (and short (= "chart" (name src))) short (name src)))
        (keyword? src) (default-label src)
        (some? src) (str src)
        :else "invoke"))

(defn- initial-target
  "Target of the region's initial pseudo-state. SCXML falls back to the first
  child state in document order when no initial is declared."
  [elems order el kids]
  (when (= :state (:node-type el :state))
    (let [init (first (filter initial-pseudo? kids))
          t    (when init
                 (->> (children-of elems order (:id init))
                      (filter #(= :transition (:node-type %)))
                      first))]
      (or (first (:target t))
          (:id (first (remove initial-pseudo? (filter #(state-types (:node-type %)) kids))))))))

(defn- id-str [id] (if (keyword? id) (subs (str id) 1) (str id)))

(defn- child-keys
  "A stable key per child state: its id, or for an unnamed one (whose
  generated id changes every run) the parent's key, its type and its place
  among unnamed siblings of that type: `@parallel.1`, `lights@state.2`."
  [parent-key states]
  (let [seen (volatile! {})]
    (mapv (fn [c]
            (if (generated-id? c)
              (let [t (:node-type c)
                    n (get (vswap! seen update t (fnil inc 0)) t)]
                (str parent-key "@" (name t) "." n))
              (id-str (:id c))))
          states)))

(defn- build-node [elems order el key]
  (let [id     (:id el)
        kids   (children-of elems order id)
        states (->> kids
                    (filter #(state-types (:node-type %)))
                    (remove initial-pseudo?))
        invs   (filter #(= :invoke (:node-type %)) kids)
        hist   (when (= :history (:node-type el))
                 (first (filter #(= :transition (:node-type %)) kids)))]
    (cond-> {:id       id
             :key      key
             :kind     (:node-type el)
             :label    (label el)
             :children (mapv #(build-node elems order %1 %2) states (child-keys key states))}
      (:diagram/kind el) (assoc :style-kind (:diagram/kind el))
      (seq states)       (assoc :initial (initial-target elems order el kids))
      (seq invs)         (assoc :invokes (mapv (fn [iv] {:id    (:id iv)
                                                         :src   (:src iv)
                                                         :label (or (:diagram/label iv) (src-label (:src iv)))})
                                               invs))
      hist               (assoc :history-type (:type el :shallow)
                                :default-target (first (:target hist))))))

(defn tree
  "The chart as a tree rooted at a synthetic `:ROOT` node."
  [chart]
  (let [elems (elements chart)
        order (document-order chart)
        root  {:id :ROOT :node-type :state}]
    (assoc (build-node elems order root "") :kind :root :label "chart")))

(defn- action-labels
  "`:diagram/label`s of a transition's executable content, as the statecharts
  library's own visualizer reads them."
  [elems t]
  (vec (keep #(:diagram/label (get elems %)) (:children t))))

(defn- else?
  "An unguarded transition is its source's fallback when an earlier sibling on
  the same events is guarded: it is taken only when every such guard is false."
  [siblings t]
  (and (nil? (:cond t))
       (some #(and (:cond %) (= (event-list %) (event-list t)))
             (take-while #(not= (:id %) (:id t)) siblings))))

(defn transitions
  "Every real transition, in document order. Initial and history default
  transitions are drawn from the tree instead, so they are left out."
  [chart]
  (let [elems   (elements chart)
        order   (document-order chart)
        ordered (->> (vals elems)
                     (filter #(= :transition (:node-type %)))
                     (remove #(let [p (get elems (:parent %))]
                                (or (initial-pseudo? p) (= :history (:node-type p)))))
                     (sort-by #(get order (:id %) Long/MAX_VALUE)))
        by-source (group-by :parent ordered)]
    (->> ordered
         (mapv (fn [t]
                 (cond-> {:id       (:id t)
                          :source   (:parent t)
                          :targets  (vec (:target t))
                          :events   (event-list t)
                          :guarded? (some? (:cond t))
                          :internal? (= :internal (:type t))}
                   (else? (by-source (:parent t)) t) (assoc :else? true)
                   (:diagram/label t)     (assoc :label (:diagram/label t))
                   (:diagram/condition t) (assoc :condition (:diagram/condition t))
                   (seq (action-labels elems t)) (assoc :actions (action-labels elems t))))))))

(defn index
  "id -> node, plus :parent links, for every node in the tree."
  [tree]
  (letfn [(walk [acc node parent depth]
            (reduce (fn [a c] (walk a c (:id node) (inc depth)))
                    (assoc acc (:id node) (assoc node :parent parent :depth depth))
                    (:children node)))]
    (walk {} tree nil 0)))

(defn ancestors-of
  "Ids from the node's parent up to :ROOT."
  [idx id]
  (loop [p (:parent (get idx id)) acc []]
    (if p (recur (:parent (get idx p)) (conj acc p)) acc)))

(defn descendant?
  "True when `id` is strictly inside `ancestor`."
  [idx id ancestor]
  (boolean (some #{ancestor} (ancestors-of idx id))))

(defn compound?
  "A state drawn as a container: it has child states. A state that only
  invokes charts is drawn as one box listing them."
  [node]
  (boolean (seq (:children node))))
