(ns statechart-viz.session-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [com.fulcrologic.statecharts :as sc]
   [com.fulcrologic.statecharts.events :refer [new-event]]
   [com.fulcrologic.statecharts.protocols :as sp]
   [com.fulcrologic.statecharts.simple :as simple]
   [statechart-viz.core :as viz]
   [statechart-viz.fixtures :as f]
   [statechart-viz.render :as r]
   [taoensso.timbre :as log]))

(defn- game-env
  "A simple env running the invoke fixture as session :g, after `events`."
  [& events]
  (log/set-min-level! :warn)
  (let [env   (simple/simple-env)
        store (::sc/working-memory-store env)
        proc  (::sc/processor env)]
    (simple/register! env `f/with-invoke f/with-invoke)
    (simple/register! env `f/child f/child)
    (simple/start! env `f/with-invoke :g)
    (doseq [e events]
      (let [env' (assoc env ::sc/session-id :g)]
        (sp/save-working-memory! store env' :g
                                 (sp/process-event! proc env' (sp/get-working-memory store env' :g) (new-event e)))))
    env))

(deftest a-session-by-its-id
  (let [env (game-env)
        s   (viz/session-tree env :g)]
    (is (= `f/with-invoke (:src s)))
    (is (= #{:game :game/ready} (:active s)))
    (is (= [] (:children s)))
    (is (= (viz/dot f/with-invoke {:active #{:game :game/ready}})
           (viz/session-dot env :g))
        "the same DOT as passing the chart and its configuration")
    (is (str/includes? (viz/session-dot env :g {:title "mine"}) "mine") "other options pass through")))

(deftest invoked-children
  (let [env  (game-env :play)
        tree (viz/session-tree env :g)]
    (is (= #{:game :game/playing} (:active tree)))
    (testing "the child is found by its invoke id, with its own chart and configuration"
      (let [[child] (:children tree)]
        (is (= :game.child (:session-id child)))
        (is (= `f/child (:src child)))
        (is (= :g (:parent child)))
        (is (some #{:coin/heads :coin/tails} (:active child)))))
    (is (str/includes? (viz/session-dot env :game.child) "state:coin/decide")
        "a child session draws its own chart")))

(deftest no-such-session
  (let [env (game-env)]
    (is (nil? (viz/session-tree env :nope)))
    (is (nil? (viz/session-dot env :nope)))
    (is (= {:error "No session :nope"} (viz/render-session env :nope)))))

(deftest render-a-session
  (if-not (r/dot-available?)
    (println "SKIP render-a-session: Graphviz dot is not on PATH")
    (is (str/includes? (String. ^bytes (:ok (viz/render-session (game-env :play) :g {:format :svg})) "UTF-8")
                       "<svg"))))
