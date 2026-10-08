(ns statechart-viz.render
  "DOT -> SVG or PNG with the Graphviz `dot` binary. Optional: a host that
  renders elsewhere (the bridge, a browser) only needs the DOT text."
  (:require
   [babashka.process :as p]))

(defn dot-available?
  "True when a `dot` binary is on PATH."
  []
  (try
    (zero? (:exit @(p/process ["dot" "-V"] {:out :string :err :string})))
    (catch Exception _ false)))

(defn render-dot
  "Render DOT text. Returns {:ok bytes} or {:error message}; never throws."
  [dot-text {:keys [format dpi] :or {format :png dpi 144}}]
  (try
    (let [args (cond-> ["dot" (str "-T" (name format))]
                 (= :png format) (conj (str "-Gdpi=" dpi)))
          out  (java.io.ByteArrayOutputStream.)
          res  @(p/process args {:in dot-text :out out :err :string})]
      (if (zero? (:exit res))
        {:ok (.toByteArray out)}
        {:error (str "dot exited " (:exit res) ": " (:err res))}))
    (catch Exception e
      {:error (str "could not run dot: " (ex-message e))})))
