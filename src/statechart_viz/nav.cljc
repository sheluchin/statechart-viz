(ns statechart-viz.nav
  "Where a viewer can go from one view of a chart: the moves a page turns into
  clicks and a chat into buttons. Pure: chart in, data out.

  A view is a chart focused on one state (`:focus`), or the whole chart. A
  chart's only top-level state stands for the whole chart: zoomed into, it
  draws the same."
  (:require
   [statechart-viz.dot :as d]
   [statechart-viz.model :as m]))

(defn- sole-top?
  [idx id]
  (and (= :ROOT (:parent (get idx id))) (= 1 (count (:children (get idx :ROOT))))))

(defn view-focus
  "`focus` as a view's focus: :ROOT for the whole chart, which nil, :ROOT and
  the chart's only top-level state all show."
  [chart focus]
  (let [idx (m/index (m/tree chart))]
    (if (or (nil? focus) (= :ROOT focus) (sole-top? idx focus) (not (contains? idx focus))) :ROOT focus)))

(defn- inside? [idx focus id]
  (or (= :ROOT focus) (= id focus) (m/descendant? idx id focus)))

(defn- containing
  "The innermost container holding both `focus` and `id`, as a view's focus."
  [idx focus id]
  (let [around (set (cons focus (m/ancestors-of idx focus)))
        c      (or (first (filter around (m/ancestors-of idx id))) :ROOT)]
    (if (sole-top? idx c) :ROOT c)))

(defn moves
  "The moves from the view of `chart` focused on `focus` (nil for the whole
  chart):

    :in     [{:id :label :svg-id}]  the containers inside it to zoom into,
            in the chart's order
    :open   [{:src :id :label :svg-id :state}]  the charts its states invoke:
            the invoke's :src and :id, and `:svg-id`, what to click in a
            drawing (the state, or a container's invoke box)
    :out    {:id :label}  one container up, :ROOT for the whole chart; nil on
            the whole chart
    :stubs  [{:id :label :svg-id :to}]  the states outside it that its
            transitions reach, drawn as `↗` stubs: `:to` is the view that
            holds both ends
    :crumbs [{:id :label :svg-id :to}]  the parts of its title's breadcrumb
            that lead back out, from the top: the title itself (:ROOT, drawn
            only when the view has a title), then each container around
            it. `:to` is the view each leads to"
  [chart focus]
  (let [tree  (m/tree chart)
        idx   (m/index tree)
        focus (view-focus chart focus)
        nodes (map #(get idx (:id %)) (rest (tree-seq :children :children tree)))
        label #(if (= :ROOT %) "the whole chart" (:label (get idx %)))]
    {:in    (vec (for [n nodes
                       :when (and (m/compound? n) (not= focus (:id n)) (not (sole-top? idx (:id n)))
                                  (inside? idx focus (:id n)))]
                   {:id (:id n) :label (:label n) :svg-id (d/svg-id n)}))
     :open  (vec (for [n  nodes
                       :when (inside? idx focus (:id n))
                       iv (:invokes n)]
                   {:src    (:src iv) :id (:id iv) :label (:label iv) :state (:id n)
                    :svg-id (if (m/compound? n) (str "invoke:" (:src iv)) (d/svg-id n))}))
     :out   (when (not= :ROOT focus)
              (let [up (or (first (filter #(and (not= :ROOT %) (m/compound? (get idx %)) (not (sole-top? idx %)))
                                          (m/ancestors-of idx focus)))
                           :ROOT)]
                {:id up :label (label up)}))
     :crumbs (if (= :ROOT focus)
               []
               (vec (cons {:id :ROOT :label "the whole chart" :svg-id (d/crumb-id :ROOT) :to :ROOT}
                          (for [id (reverse (m/ancestors-of idx focus))
                                :when (not= :ROOT id)]
                            {:id id :label (label id) :svg-id (d/crumb-id (get idx id)) :to (view-focus chart id)}))))
     :stubs (if (= :ROOT focus)
              []
              (vec (for [id (distinct
                             (for [{:keys [source targets]} (m/transitions chart)
                                   :let [ends (cons source targets)]
                                   :when (some #(inside? idx focus %) ends)
                                   e ends
                                   :when (not (inside? idx focus e))]
                               e))
                         :let [n (get idx id)]]
                     {:id id :label (:label n) :svg-id (str "stub:" (:key n)) :to (containing idx focus id)})))}))
