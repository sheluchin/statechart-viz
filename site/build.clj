(ns build
  "Collects the cljs sources the browser page loads into Scittle: this
  library, the statecharts namespaces it needs and the shims in site/shims,
  in dependency order. Writes site/vendor/ and site/vendor/load-order.json."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [edamame.core :as e]))

(def roots
  '[statechart-viz.core
    com.fulcrologic.statecharts.chart
    com.fulcrologic.statecharts.elements
    com.fulcrologic.statecharts.events
    com.fulcrologic.statecharts.protocols
    com.fulcrologic.statecharts.simple
    com.fulcrologic.statecharts.util])

(def builtin '#{clojure.string clojure.set clojure.edn clojure.walk})

(def patches
  "Source rewrites for code SCI can't run as written, by path."
  {"com/fulcrologic/statecharts/elements.cljc"
   ;; goog.DEBUG gates a dev-only warning; SCI has no goog global.
   [["goog.DEBUG" "false"]]
   "com/fulcrologic/statecharts/algorithms/v20150901_impl.cljc"
   ;; CLJS gets these two macros from the JVM side; SCI expands them itself.
   [["#?(:clj\n   (defmacro with-processing-context" "#?(:cljs\n   (defmacro with-processing-context"]
    ["#?(:clj\n   (defmacro in-state-context" "#?(:cljs\n   (defmacro in-state-context"]]})

(defn- patch [path src]
  (reduce (fn [s [from to]] (str/replace s from to)) src (get patches path)))

(defn- ns->path [ns] (-> (str ns) (str/replace "-" "_") (str/replace "." "/")))

(defn- source
  "[relative-path url] for `ns`, shims first, then the classpath."
  [ns]
  (let [base (ns->path ns)]
    (or (some (fn [ext]
                (let [f (io/file "site/shims" (str base ext))]
                  (when (.exists f) [(str base ext) (io/as-url f)])))
              [".cljs" ".cljc"])
        (some (fn [ext]
                (when-let [r (io/resource (str base ext))] [(str base ext) r]))
              [".cljc" ".cljs"])
        (throw (ex-info (str "No cljs source for " ns) {:ns ns})))))

(defn- requires [url]
  (let [form (e/parse-string (slurp url) {:all true :read-cond :allow :features #{:cljs}
                                           :auto-resolve name})]
    (for [clause (filter seq? form)
          :when  (#{:require :require-macros} (first clause))
          spec   (rest clause)]
      (if (sequential? spec) (first spec) spec))))

(defn- load-order
  "Depth-first, so every namespace comes after the ones it requires."
  [roots]
  (let [order (atom []) seen (atom #{})]
    (letfn [(visit [ns]
              (when-not (or (@seen ns) (builtin ns))
                (swap! seen conj ns)
                (let [[path url] (source ns)]
                  (run! visit (requires url))
                  (swap! order conj [ns path url]))))]
      (run! visit roots))
    @order))

(defn -main [& _]
  (let [out   (io/file "site/vendor")
        order (load-order roots)]
    (io/copy (io/file "site/shims/prelude.cljs") (doto (io/file out "prelude.cljs") io/make-parents))
    (doseq [[_ path url] order]
      (let [f (io/file out path)]
        (io/make-parents f)
        (spit f (patch path (slurp url)))))
    (spit (io/file out "load-order.json")
          (str "[" (str/join "," (map #(str "\"" % "\"") (cons "prelude.cljs" (map second order)))) "]"))
    (println "Wrote" (count order) "namespaces to site/vendor")))
