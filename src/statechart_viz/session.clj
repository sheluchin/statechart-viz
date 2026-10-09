(ns statechart-viz.session
  "Running sessions, looked up by session id in a statecharts env: the chart
  from the env's registry, the configuration and the invoked children from
  its working-memory store. Works with any store and registry."
  (:require
   [com.fulcrologic.statecharts :as sc]
   [com.fulcrologic.statecharts.protocols :as sp]
   [statechart-viz.model :as m]))

(defn- working-memory [env session-id]
  (when-let [store (::sc/working-memory-store env)]
    (sp/get-working-memory store env session-id)))

(defn session
  "The session `session-id` in `env`, or nil when the store has none (never
  started, or finished and deleted):
  {:session-id :src :chart :active :parent :children}.

  `:children` are the ids of the charts it invoked that are still running. A
  child's session id is its invoke's `:id`, so give each invoke one."
  [env session-id]
  (when-let [wmem (working-memory env session-id)]
    (let [src    (::sc/statechart-src wmem)
          chart  (some-> (::sc/statechart-registry env) (sp/get-statechart src))
          active (set (::sc/configuration wmem))]
      (when-not chart
        (throw (ex-info (str "Session " session-id " runs " src ", which is not in the env's registry")
                        {:session-id session-id :src src})))
      (cond-> {:session-id session-id
               :src        src
               :chart      chart
               :active     active
               :children   (vec (for [node  (sort-by :key (vals (m/index (m/tree chart))))
                                      :when (contains? active (:id node))
                                      iv    (:invokes node)
                                      :when (and (:id iv) (working-memory env (:id iv)))]
                                  (:id iv)))}
        (::sc/parent-session-id wmem) (assoc :parent (::sc/parent-session-id wmem))))))

(defn session-tree
  "`session-id` and the charts it invoked, recursively:
  {:session-id :src :active :children [...]}, or nil."
  [env session-id]
  (when-let [s (session env session-id)]
    (-> (select-keys s [:session-id :src :active :parent])
        (assoc :children (vec (keep #(session-tree env %) (:children s)))))))
