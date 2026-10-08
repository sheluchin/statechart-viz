(ns statechart-viz.theme
  "Default look. Pass a partial map as `:theme` to override any key; `:kinds`
  merges one level deep, so a chart can add its own `:diagram/kind` colours.")

(def default
  {:font            "Helvetica"
   :edge-font       "Courier"
   :font-size       12
   :edge-font-size  10
   :background      "#f7f8fa"
   :text            "#1c2230"
   :muted           "#6b7484"
   :state-fill      "#ffffff"
   :state-border    "#5a7a99"
   :cluster-border  "#aab2bd"
   :parallel-border "#7a6bbf"
   :active-fill     "#ffe08a"
   :active-border   "#b8860b"
   :edge            "#5a6472"
   :guard-edge      "#7a6bbf"
   :initial         "#2b313b"
   :invoke-fill     "#e7f3ff"
   :invoke-border   "#3b7bbf"
   ;; :diagram/kind -> fill. Active states still get the active fill.
   :kinds           {:success  "#dcefdf"
                     :failure  "#f8e2e2"
                     :decision "#e9e4f5"
                     :waiting  "#dde7f3"
                     :neutral  "#eef2f7"}})

(defn resolve-theme [overrides]
  (-> (merge default (dissoc overrides :kinds))
      (assoc :kinds (merge (:kinds default) (:kinds overrides)))))
