(ns statechart-viz.core
  "Public API. Works on any compiled Fulcro statechart; knows nothing about
  any particular one.

    (dot chart opts)      Graphviz DOT, see statechart-viz.dot/dot for opts
    (outline chart)       the state tree as data, for zoom / nav menus
    (zoom-targets chart)  every state worth zooming into, with its path
    (render chart opts)   DOT rendered by Graphviz to :svg or :png bytes"
  (:require
   [statechart-viz.dot :as d]
   [statechart-viz.model :as m]
   [statechart-viz.render :as r]))

(defn dot
  "Graphviz DOT for `chart`. Options: :active :focus :depth :title :theme."
  ([chart] (d/dot chart {}))
  ([chart opts] (d/dot chart opts)))

(defn- outline-node [node active]
  (cond-> {:id    (:id node)
           :label (:label node)
           :kind  (:kind node)}
    (contains? active (:id node)) (assoc :active? true)
    (seq (:invokes node)) (assoc :invokes (mapv #(select-keys % [:id :src :label]) (:invokes node)))
    (seq (:children node)) (assoc :children (mapv #(outline-node % active) (:children node)))))

(defn outline
  "The chart's states as nested data: {:id :label :kind :active? :invokes :children}.
  Pass a configuration as `active` to mark the active states."
  ([chart] (outline chart #{}))
  ([chart active]
   (outline-node (m/tree chart) (set active))))

(defn zoom-targets
  "Containers you can zoom into, outermost first:
  [{:id :label :kind :path [labels from the top] :states n}]"
  [chart]
  (let [tree (m/tree chart)
        idx  (m/index tree)]
    (->> (vals idx)
         (filter #(and (not= :root (:kind %)) (m/compound? %)))
         (sort-by (juxt :depth #(str (:id %))))
         (mapv (fn [n]
                 {:id     (:id n)
                  :label  (:label n)
                  :kind   (:kind n)
                  :path   (->> (cons (:id n) (m/ancestors-of idx (:id n)))
                               (remove #{:ROOT})
                               reverse
                               (mapv #(:label (get idx %))))
                  :states (count (:children n))})))))

(defn render
  "Render with the Graphviz `dot` binary. Options are those of `dot` plus
  :format (:svg or :png, default :png) and :dpi (png only, default 144).
  Returns {:ok bytes} or {:error message}."
  ([chart] (render chart {}))
  ([chart opts]
   (r/render-dot (d/dot chart opts) opts)))
