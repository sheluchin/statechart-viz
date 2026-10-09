(ns com.fulcrologic.guardrails.malli.core
  "Browser shim: guardrails' >defn as plain defn, its specs dropped.")

(def => '=>)
(def ? '?)

(defn- drop-gspec [body]
  (if (and (vector? (first body)) (some #{'=>} (first body)))
    (rest body)
    body))

(defn- arity [[args & body]] (list* args (drop-gspec body)))

(defn- defn-form [op forms]
  (let [[head tail] (split-with #(not (or (vector? %) (list? %))) forms)]
    (if (vector? (first tail))
      `(~op ~@head ~@(arity tail))
      `(~op ~@head ~@(map arity tail)))))

(defmacro >defn [& forms] (defn-form 'clojure.core/defn forms))
(defmacro >defn- [& forms] (defn-form 'clojure.core/defn- forms))
(defmacro >def [& _] nil)
