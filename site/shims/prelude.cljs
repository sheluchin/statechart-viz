;; Loaded before everything else: clojure.core fns SCI's ClojureScript core lacks.
(intern 'clojure.core 'swap-vals!
        (fn [a f & args]
          (loop []
            (let [old @a new (apply f old args)]
              (if (compare-and-set! a old new) [old new] (recur))))))
