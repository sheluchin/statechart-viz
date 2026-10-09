(ns statechart-viz.core
  "Public API. Works on any compiled Fulcro statechart; knows nothing about
  any particular one.

    (dot chart opts)      Graphviz DOT, see statechart-viz.dot/dot for opts
    (outline chart)       the state tree as data, for zoom / nav menus
    (zoom-targets chart)  every state worth zooming into, with its path
    (render chart opts)   DOT rendered by Graphviz to :svg or :png bytes

  For a running session, pass the statecharts env and its session id
  instead of the chart and its configuration:

    (session-dot env session-id opts)
    (render-session env session-id opts)
    (session-tree env session-id)   the session and the charts it invoked"
  (:require
   [statechart-viz.dot :as d]
   [statechart-viz.model :as m]
   [statechart-viz.render :as r]
   [statechart-viz.session :as s]))

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

(defn session-tree
  "The session `session-id` in `env` and the charts it invoked, recursively:
  {:session-id :src :active :parent :children [...]}, or nil if the env's
  store has no such session. For a menu of a running session's charts."
  [env session-id]
  (s/session-tree env session-id))

(defn- session-opts [env session-id opts]
  (let [found (s/session env session-id)]
    (when found
      [(:chart found) (assoc opts :active (:active found))])))

(defn session-dot
  "Graphviz DOT for the running session `session-id` in `env`: its chart
  from the env's registry, its active states from the working-memory store.
  Options as for `dot`, less `:active`. Nil if there is no such session."
  ([env session-id] (session-dot env session-id {}))
  ([env session-id opts]
   (when-let [[chart opts] (session-opts env session-id opts)]
     (d/dot chart opts))))

(defn render-session
  "`render` for the running session `session-id` in `env`.
  Returns {:ok bytes} or {:error message}."
  ([env session-id] (render-session env session-id {}))
  ([env session-id opts]
   (if-let [[chart opts] (session-opts env session-id opts)]
     (r/render-dot (d/dot chart opts) opts)
     {:error (str "No session " session-id)})))
