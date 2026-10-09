(ns clojure.spec.alpha
  "Browser shim: the statecharts sources only use spec for fdefs.")

(defmacro fdef [& _] nil)
(defmacro def [& _] nil)
(defmacro cat [& _] nil)
