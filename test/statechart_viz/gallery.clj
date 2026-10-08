(ns statechart-viz.gallery
  "Render each fixture chart, with sample active states and zoom views, to
  out/*.png for eyeballing."
  (:require
   [clojure.java.io :as io]
   [statechart-viz.core :as viz]
   [statechart-viz.fixtures :as f]))

(def views
  [["flat"            f/flat           {:active #{:gate/triage}}]
   ["nested"          f/nested         {:active #{:app :app/running :run/work :work/b}}]
   ["nested-depth1"   f/nested         {:active #{:app :app/running :run/work :work/b} :depth 1}]
   ["nested-focus"    f/nested         {:active #{:app :app/running :run/work :work/b} :focus :app/running}]
   ["parallel"        f/parallel-chart {:active #{:decide :axis/who :who/direct :axis/what :what/checking}}]
   ["invoke"          f/with-invoke    {:active #{:game :game/playing}}]
   ["invoke-child"    f/child          {:active #{:coin/tails} :title "game › Playing › ⤵ child"}]])

(defn -main [& [dir]]
  (let [dir (or dir "out")]
    (.mkdirs (io/file dir))
    (doseq [[n chart opts] views]
      (spit (str dir "/" n ".dot") (viz/dot chart opts))
      (let [{:keys [ok error]} (viz/render chart opts)]
        (if ok
          (do (with-open [o (io/output-stream (str dir "/" n ".png"))] (.write o ^bytes ok))
              (println "wrote" (str dir "/" n ".png")))
          (println "FAILED" n error))))))
