(ns statechart-viz.nav-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [statechart-viz.core :as viz]
   [statechart-viz.fixtures :as f]))

(deftest from-the-whole-chart
  (let [{:keys [in open out stubs]} (viz/moves f/nested nil)]
    (testing "every container, in the chart's order"
      (is (= [:app :app/running :run/work] (map :id in)))
      (is (= "state:run/work" (:svg-id (last in)))))
    (is (= [] open stubs))
    (is (nil? out) "nowhere further out")))

(deftest from-a-zoomed-view
  (let [{:keys [in out stubs]} (viz/moves f/nested :app/running)]
    (is (= [:run/work] (map :id in)) "only what is inside it")
    (is (= {:id :app :label "app"} out))
    (testing "a stub for each state outside its transitions reach, and the view holding both ends"
      (is (= [{:id :app/paused :label "paused" :svg-id "stub:app/paused" :to :app}] stubs)))))

(deftest the-title-leads-back-out
  (testing "the whole chart, then each container around the view, from the top"
    (is (= [{:id :ROOT :label "the whole chart" :svg-id "a_crumb:ROOT" :to :ROOT}
            {:id :app :label "app" :svg-id "a_crumb:app" :to :app}
            {:id :app/running :label "running" :svg-id "a_crumb:app/running" :to :app/running}]
           (:crumbs (viz/moves f/nested :run/work)))))
  (testing "the only top-level state leads to the whole chart"
    (is (= :ROOT (:to (second (:crumbs (viz/moves f/parallel-chart :axis/who)))))))
  (is (= [] (:crumbs (viz/moves f/nested nil))) "none on the whole chart"))

(deftest the-only-top-state-is-the-whole-chart
  (testing "not a container to zoom into, and one up from its child is the whole chart"
    (is (= [:axis/who :axis/what] (map :id (:in (viz/moves f/parallel-chart nil)))))
    (is (= {:id :ROOT :label "the whole chart"} (:out (viz/moves f/parallel-chart :axis/who)))))
  (testing "focused on it is the whole chart"
    (is (= (viz/moves f/parallel-chart nil) (viz/moves f/parallel-chart :decide)))))

(deftest invoked-charts
  (is (= [{:src 'statechart-viz.fixtures/child :id :game.child :label "child" :state :game/playing
           :svg-id "state:game/playing"}]
         (:open (viz/moves f/with-invoke nil))))
  (testing "only those invoked inside the view"
    (is (= [] (:open (viz/moves f/nested :run/work))))))

(deftest an-unknown-focus-is-the-whole-chart
  (is (= (viz/moves f/nested nil) (viz/moves f/nested :nope))))
