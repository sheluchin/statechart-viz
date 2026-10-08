(ns statechart-viz.model
  "Turn any compiled Fulcro statechart into a plain tree of states plus a flat
  list of transitions. Everything downstream (DOT, outlines, nav menus) reads
  this model, never the element map, so the library knows nothing about any
  particular chart.

  Charts can annotate elements with optional `:diagram/*` keys:
    :diagram/label  display text for a state or a transition
    :diagram/kind   a keyword the theme maps to a fill colour, e.g. :success"
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

(defn label
  "Display text for an element."
  [el]
  (or (:diagram/label el) (default-label (:id el))))

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

(defn- build-node [elems order el]
  (let [id     (:id el)
        kids   (children-of elems order id)
        states (->> kids
                    (filter #(state-types (:node-type %)))
                    (remove initial-pseudo?))
        invs   (filter #(= :invoke (:node-type %)) kids)
        hist   (when (= :history (:node-type el))
                 (first (filter #(= :transition (:node-type %)) kids)))]
    (cond-> {:id       id
             :kind     (:node-type el)
             :label    (label el)
             :children (mapv #(build-node elems order %) states)}
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
    (assoc (build-node elems order root) :kind :root :label "chart")))

(defn transitions
  "Every real transition, in document order. Initial and history default
  transitions are drawn from the tree instead, so they are left out."
  [chart]
  (let [elems (elements chart)
        order (document-order chart)]
    (->> (vals elems)
         (filter #(= :transition (:node-type %)))
         (remove #(let [p (get elems (:parent %))]
                    (or (initial-pseudo? p) (= :history (:node-type p)))))
         (sort-by #(get order (:id %) Long/MAX_VALUE))
         (mapv (fn [t]
                 (cond-> {:id       (:id t)
                          :source   (:parent t)
                          :targets  (vec (:target t))
                          :events   (event-list t)
                          :guarded? (some? (:cond t))
                          :internal? (= :internal (:type t))}
                   (:diagram/label t) (assoc :label (:diagram/label t))))))))

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
  "A state drawn as a container: it has child states or invokes a chart."
  [node]
  (boolean (or (seq (:children node)) (seq (:invokes node)))))
