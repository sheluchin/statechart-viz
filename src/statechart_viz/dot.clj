(ns statechart-viz.dot
  "Graphviz DOT from the chart model. Pure: data in, string out.

  Drawing rules:
    compound state   rounded cluster holding its children
    parallel state   dashed cluster with a ∥ badge; its regions sit side by side
    final state      rounded box with a double border
    history          small circle, H or H*, dotted edge to its default
    invoke           3D box inside the invoking state, named after the child chart
    initial          dot inside its region, one arrow to the initial child
    guarded edge     coloured, labelled [guard] unless the transition has a :diagram/label
    eventless edge   no event text
    internal / targetless transition   dotted self-loop
    active state     gold fill (leaves) or gold border (containers)

  Zoom: `:focus` draws only that state's subtree; `:depth` collapses containers
  deeper than that many levels below the focus. Transitions that leave the drawn
  area end at a small grey stub named after the outside state."
  (:require
   [clojure.string :as str]
   [statechart-viz.model :as m]
   [statechart-viz.theme :as theme]))

;; ---------------------------------------------------------------------------
;; DOT text helpers

(defn- esc [s]
  (-> (str s)
      (str/replace "\\" "\\\\")
      (str/replace "\"" "\\\"")
      (str/replace "\n" "\\n")))

(defn- q [s] (str "\"" (esc s) "\""))

(defn- attrs [m]
  (str "["
       (->> m
            (remove (comp nil? val))
            (map (fn [[k v]] (str (name k) "=" (if (number? v) v (q v)))))
            (str/join " "))
       "]"))

;; ---------------------------------------------------------------------------
;; Visibility: what is drawn, and what stands in for what isn't

(defn- preorder-ids [tree]
  (letfn [(walk [n] (cons (:id n) (mapcat walk (:children n))))]
    (walk tree)))

(defn- node-ids
  "Stable, DOT-safe ids by tree position: s0, s1, ..."
  [tree]
  (into {} (map-indexed (fn [i id] [id (str "s" i)]) (preorder-ids tree))))

(defn- collapsed?
  [ctx node]
  (let [{:keys [depth focus-depth idx]} ctx]
    (and depth
         (m/compound? node)
         (>= (- (:depth (get idx (:id node))) focus-depth) depth)
         (not= (:id node) (:focus ctx)))))

(defn- representative
  "The drawn node that stands for `id`: itself, its nearest collapsed
  ancestor, or :outside when it is not under the focus."
  [ctx id]
  (let [{:keys [idx focus]} ctx]
    (if (and (not= focus :ROOT) (not= id focus) (not (m/descendant? idx id focus)))
      :outside
      (let [chain (reverse (cons id (m/ancestors-of idx id)))]
        (or (some #(when (collapsed? ctx (get idx %)) %) chain)
            id)))))

(defn- subtree-active? [ctx node]
  (or (contains? (:active ctx) (:id node))
      (some #(subtree-active? ctx %) (:children node))))

(defn- count-states
  "Real states under `node`; history pseudo-states are not counted."
  [node]
  (let [kids (remove #(= :history (:kind %)) (:children node))]
    (reduce + (count kids) (map count-states kids))))

(defn- container-loop?
  "A targetless transition on a drawn container: shown in the container's
  label, since a self-loop on a cluster has nothing to attach to."
  [ctx tr]
  (and (empty? (:targets tr))
       (let [node (get (:idx ctx) (:source tr))]
         (and (m/compound? node) (not (collapsed? ctx node))))))

(declare edge-label)

(defn- loop-labels [ctx node]
  (->> (:transitions ctx)
       (filter #(and (= (:id node) (:source %)) (container-loop? ctx %)))
       (map #(str "↺ " (edge-label %)))))

;; ---------------------------------------------------------------------------
;; Nodes

(defn- fill-for [ctx node active?]
  (let [t (:theme ctx)]
    (cond active? (:active-fill t)
          (:style-kind node) (get (:kinds t) (:style-kind node) (:state-fill t))
          :else (:state-fill t))))

(defn- leaf-line [ctx node]
  (let [t       (:theme ctx)
        active? (contains? (:active ctx) (:id node))
        id      (get (:ids ctx) (:id node))]
    (case (:kind node)
      :history
      (str "  " (q id) " "
           (attrs {:label     (if (= :deep (:history-type node)) "H*" "H")
                   :shape     "circle" :width 0.3 :fixedsize "true"
                   :fontsize  10 :style "filled"
                   :fillcolor (fill-for ctx node active?)
                   :color     (if active? (:active-border t) (:state-border t))})
           ";\n")
      (str "  " (q id) " "
           (attrs {:label      (:label node)
                   :shape      "box"
                   :style      "rounded,filled"
                   :peripheries (when (= :final (:kind node)) 2)
                   :fillcolor  (fill-for ctx node active?)
                   :color      (if active? (:active-border t) (:state-border t))
                   :penwidth   (if active? 2.5 1.2)})
           ";\n"))))

(defn- collapsed-line [ctx node]
  (let [t       (:theme ctx)
        active? (subtree-active? ctx node)
        n       (count-states node)]
    (str "  " (q (get (:ids ctx) (:id node))) " "
         (attrs {:label     (str (:label node) "\n⊞ " n (if (= 1 n) " state" " states"))
                 :shape     "box"
                 :style     "rounded,filled,bold"
                 :fillcolor (fill-for ctx node active?)
                 :color     (if active? (:active-border t) (:state-border t))
                 :penwidth  (if active? 2.5 1.5)})
         ";\n")))

(defn- invoke-line [ctx inv]
  (let [t (:theme ctx)]
    (str "  " (q (str "inv_" (get (:ids ctx) (:parent-id inv)) "_" (:n inv))) " "
         (attrs {:label     (str "⤵ " (:label inv))
                 :shape     "box3d"
                 :style     "dashed,filled"
                 :fillcolor (:invoke-fill t)
                 :color     (:invoke-border t)
                 :fontsize  11})
         ";\n")))

(defn- initial-lines
  "The initial dot sits inside its region, so it lays out next to its target."
  [ctx node]
  (when-let [target (:initial node)]
    (let [t   (:theme ctx)
          rep (representative ctx target)]
      (when (and (not= rep :outside) (not= rep (:id node)))
        (let [dot-id (str "init_" (get (:ids ctx) (:id node)))]
          (str "  " (q dot-id) " "
               (attrs {:shape "point" :width 0.12 :color (:initial t) :fillcolor (:initial t)})
               ";\n"))))))

(declare emit-node)

(defn- cluster [ctx node]
  (let [t        (:theme ctx)
        id       (get (:ids ctx) (:id node))
        active?  (contains? (:active ctx) (:id node))
        par?     (= :parallel (:kind node))]
    (str "subgraph " (q (str "cluster_" id)) " {\n"
         "  label=" (q (str/join "\n" (cons (if par? (str (:label node) "   ∥") (:label node))
                                            (loop-labels ctx node)))) ";\n"
         "  labeljust=\"l\"; labelloc=\"t\"; fontsize=" (:font-size t) ";\n"
         "  style=" (q (if par? "rounded,dashed" "rounded")) ";\n"
         "  color=" (q (cond active? (:active-border t) par? (:parallel-border t) :else (:cluster-border t))) ";\n"
         "  penwidth=" (cond active? 2.5 par? 1.6 :else 1) ";\n"
         (if (contains? (:ports ctx) (:id node))
           ;; a visible port at the top: transitions between the container and its own
           ;; children start or end here, since they cannot be clipped to its border
           (str "  {rank=min; " (q id) " "
                (attrs {:shape "circle" :width 0.13 :height 0.13 :fixedsize "true" :label ""
                        :style "filled" :fillcolor (:background t)
                        :color (if active? (:active-border t) (:state-border t))})
                "}\n")
           ;; invisible anchor: edges to/from the container attach here and are clipped to the border
           (str "  " (q id) " [shape=point style=invis width=0.01 height=0.01 label=\"\"];\n"))
         (initial-lines ctx node)
         (apply str (map-indexed (fn [n inv] (invoke-line ctx (assoc inv :parent-id (:id node) :n n)))
                                 (:invokes node)))
         (apply str (map #(emit-node ctx %) (:children node)))
         "}\n")))

(defn- emit-node [ctx node]
  (cond (collapsed? ctx node)   (collapsed-line ctx node)
        (m/compound? node)      (cluster ctx node)
        :else                   (leaf-line ctx node)))

;; ---------------------------------------------------------------------------
;; Edges

(defn- clustered? [ctx id]
  (let [node (get (:idx ctx) id)]
    (and (m/compound? node) (not (collapsed? ctx node)))))

(defn- event-text
  "`:game/reset` reads as `reset`. Platform events keep their full name, since
  `:done.state.intake/handling` read as `handling` would mislead."
  [e]
  (cond (not (keyword? e)) (str e)
        (some-> (namespace e) (str/starts-with? "done.")) (subs (str e) 1)
        (some-> (namespace e) (str/starts-with? "error.")) (subs (str e) 1)
        :else (name e)))

(defn- edge-label [tr]
  (or (:label tr)
      (let [evs (str/join ", " (map event-text (:events tr)))]
        (str/trim (str evs (when (:guarded? tr) " [guard]"))))))

(defn- endpoint
  "DOT id for a drawn endpoint, plus the cluster to clip to when it is a container."
  [ctx id ghost-ids]
  (if (contains? ghost-ids id)
    {:dot (get ghost-ids id)}
    {:dot (get (:ids ctx) id)
     :cluster (when (clustered? ctx id) (str "cluster_" (get (:ids ctx) id)))}))

(defn- edge-specs
  "Resolve every transition to drawn endpoints; drop ones that vanish inside a
  collapsed state or lie wholly outside the focus."
  [ctx]
  (->> (:transitions ctx)
       (mapcat (fn [tr]
                 (let [targets (if (seq (:targets tr)) (:targets tr) [(:source tr)])]
                   (for [tgt targets]
                     {:tr      tr
                      :src     (:source tr)
                      :tgt     tgt
                      :self?   (empty? (:targets tr))
                      :src-rep (representative ctx (:source tr))
                      :tgt-rep (representative ctx tgt)}))))
       (remove (fn [{:keys [tr src src-rep tgt-rep self?]}]
                 (or (and (= :outside src-rep) (= :outside tgt-rep))
                     ;; internal loops are drawn on their own state only
                     (and self? (or (not= src src-rep) (container-loop? ctx tr)))
                     (and (not self?) (= src-rep tgt-rep)
                          (collapsed? ctx (get (:idx ctx) src-rep))))))
       (map (fn [{:keys [src tgt src-rep tgt-rep] :as e}]
              (assoc e
                     :from (if (= :outside src-rep) [:ghost src] src-rep)
                     :to   (if (= :outside tgt-rep) [:ghost tgt] tgt-rep))))
       ;; a collapsed box can stand for several identical edges; draw one
       (reduce (fn [[seen out] e]
                 (let [k [(:from e) (:to e) (edge-label (:tr e)) (:guarded? (:tr e)) (:self? e)]]
                   (if (seen k) [seen out] [(conj seen k) (conj out e)])))
               [#{} []])
       second))

(defn- nested-edge?
  "An edge between a container and something inside it (or itself)."
  [idx from-id to-id]
  (or (= from-id to-id)
      (m/descendant? idx to-id from-id)
      (m/descendant? idx from-id to-id)))

(defn- ports
  "Containers that need a visible port: endpoints of nested edges."
  [ctx specs]
  (set (for [{:keys [from to self?]} specs
             :when (and (not self?) (not (vector? from)) (not (vector? to))
                        (nested-edge? (:idx ctx) from to))
             id [from to]
             :when (clustered? ctx id)]
         id)))

(defn- ghost-lines [ctx specs]
  (let [t      (:theme ctx)
        ghosts (distinct (concat (keep #(when (vector? (:from %)) (second (:from %))) specs)
                                 (keep #(when (vector? (:to %)) (second (:to %))) specs)))
        ids    (into {} (map-indexed (fn [i g] [g (str "ghost" i)]) ghosts))]
    {:ids   ids
     :lines (apply str
                   (for [g ghosts]
                     (str "  " (q (get ids g)) " "
                          (attrs {:label     (str "↗ " (:label (get (:idx ctx) g)))
                                  :shape     "plaintext"
                                  :fontcolor (:muted t)
                                  :fontsize  10})
                          ";\n")))}))

(defn- edge-line [ctx ghost-ids {:keys [tr from to self?]}]
  (let [t       (:theme ctx)
        idx     (:idx ctx)
        from-id (if (vector? from) (second from) from)
        to-id   (if (vector? to) (second to) to)
        a       (endpoint ctx from-id ghost-ids)
        b       (endpoint ctx to-id ghost-ids)
        ;; a container cannot clip an edge to itself or to something inside it
        nested? (nested-edge? idx from-id to-id)]
    (str "  " (q (:dot a)) " -> " (q (:dot b)) " "
         (attrs {:label     (let [l (edge-label tr)] (when-not (str/blank? l) (str " " l " ")))
                 :ltail     (when-not nested? (:cluster a))
                 :lhead     (when-not nested? (:cluster b))
                 :style     (when (or self? (:internal? tr)) "dotted")
                 :color     (if (:guarded? tr) (:guard-edge t) (:edge t))
                 :fontcolor (if (:guarded? tr) (:guard-edge t) (:text t))})
         ";\n")))

(defn- initial-edges [ctx]
  (let [t (:theme ctx)]
    (apply str
           (for [node (vals (:idx ctx))
                 :let [target (:initial node)]
                 :when (and target
                            (m/compound? node)
                            (not (collapsed? ctx node))
                            (not= :outside (representative ctx (:id node)))
                            (= (:id node) (representative ctx (:id node))))
                 :let [rep (representative ctx target)]
                 :when (and (not= rep :outside) (not= rep (:id node)))]
             (str "  " (q (str "init_" (get (:ids ctx) (:id node)))) " -> " (q (get (:ids ctx) rep)) " "
                  (attrs {:lhead (when (clustered? ctx rep) (str "cluster_" (get (:ids ctx) rep)))
                          :color (:initial t) :arrowsize 0.7})
                  ";\n")))))

(defn- history-edges [ctx]
  (let [t (:theme ctx)]
    (apply str
           (for [node (vals (:idx ctx))
                 :when (and (= :history (:kind node)) (:default-target node)
                            (= (:id node) (representative ctx (:id node))))
                 :let [rep (representative ctx (:default-target node))]
                 :when (not= rep :outside)]
             (str "  " (q (get (:ids ctx) (:id node))) " -> " (q (get (:ids ctx) rep)) " "
                  (attrs {:style "dotted" :color (:muted t) :arrowsize 0.7
                          :lhead (when (clustered? ctx rep) (str "cluster_" (get (:ids ctx) rep)))})
                  ";\n")))))

;; ---------------------------------------------------------------------------
;; Entry point

(defn- breadcrumb [idx focus]
  (->> (cons focus (m/ancestors-of idx focus))
       (remove #{:ROOT})
       reverse
       (map #(:label (get idx %)))
       (str/join " › ")))

(defn dot
  "DOT for a compiled chart. Options:
    :active  set of active state ids (a session's configuration)
    :focus   state id to zoom into; default the whole chart
    :depth   container levels to expand below the focus; default all
    :title   graph title; default the chart's :diagram/label or nothing
    :theme   partial theme map, see statechart-viz.theme/default"
  ([chart] (dot chart {}))
  ([chart {:keys [active focus depth title] :as opts}]
   (let [tree   (m/tree chart)
         idx    (m/index tree)
         focus  (or focus :ROOT)
         _      (when-not (contains? idx focus)
                  (throw (ex-info (str "No state " focus " in this chart") {:focus focus})))
         t      (theme/resolve-theme (:theme opts))
         ctx    {:idx         idx
                 :ids         (node-ids tree)
                 :active      (set active)
                 :focus       focus
                 :focus-depth (:depth (get idx focus))
                 :depth       depth
                 :theme       t
                 :transitions (m/transitions chart)}
         specs  (edge-specs ctx)
         ctx    (assoc ctx :ports (ports ctx specs))
         ghosts (ghost-lines ctx specs)
         crumb  (when (not= focus :ROOT) (breadcrumb idx focus))
         title  (or title (:diagram/label chart))
         gtitle (str/join "  ·  " (remove str/blank? [title crumb]))
         start  (get idx focus)]
     (str "digraph statechart {\n"
          "  compound=true; newrank=true; rankdir=TB;\n"
          "  bgcolor=" (q (:background t)) "; pad=0.3; nodesep=0.4; ranksep=0.45;\n"
          "  fontname=" (q (:font t)) "; fontcolor=" (q (:text t)) ";\n"
          (when-not (str/blank? gtitle)
            (str "  label=" (q gtitle) "; labelloc=\"t\"; labeljust=\"l\"; fontsize=" (+ 2 (:font-size t)) ";\n"))
          "  node [fontname=" (q (:font t)) " fontsize=" (:font-size t) " fontcolor=" (q (:text t)) " margin=\"0.15,0.06\"];\n"
          "  edge [fontname=" (q (:edge-font t)) " fontsize=" (:edge-font-size t) " arrowsize=0.8];\n"
          (cond (= :root (:kind start)) (str (initial-lines ctx start)
                                             (apply str (map #(emit-node ctx %) (:children start))))
                (m/compound? start)     (cluster ctx start)
                :else                   (leaf-line ctx start))
          (:lines ghosts)
          (initial-edges ctx)
          (history-edges ctx)
          (apply str (map #(edge-line ctx (:ids ghosts) %) specs))
          "}\n"))))
