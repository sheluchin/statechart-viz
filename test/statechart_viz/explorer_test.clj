(ns statechart-viz.explorer-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [statechart-viz.explorer :as ex]
   [statechart-viz.fixtures :as f]
   [statechart-viz.render :as r]))

(def ^:private charts {'statechart-viz.fixtures/child f/child})

(deftest a-view-for-each-chart-and-container
  (let [{:keys [start views]} (ex/plan f/with-invoke {:charts charts :title "game"})]
    (is (= "c0" start))
    (is (= #{"c0" "c1"} (set (keys views))))
    (testing "labels: the root's name, an invoked chart's"
      (is (= ["game" "⤵ child"] (map #(:label (get views %)) ["c0" "c1"]))))
    (let [views (:views (ex/plan f/nested {}))]
      (is (= "running" (:label (get views "c0.app/running"))) "a container's"))))

(deftest clicks-zoom-in-and-open-invoked-charts
  (let [go (:go (get (:views (ex/plan f/with-invoke {:charts charts})) "c0"))]
    (is (= {:view "c1" :hint "Open child"} (get go "state:game/playing"))))
  (let [go (:go (get (:views (ex/plan f/nested {})) "c0"))]
    (is (= {:view "c0.app/running" :hint "Zoom into running"} (get go "state:app/running"))))
  (testing "a chart not given is drawn, not opened"
    (let [{:keys [views]} (ex/plan f/with-invoke {})]
      (is (= #{"c0"} (set (keys views))))
      (is (nil? (get-in views ["c0" :go "state:game/playing"]))))))

(deftest a-stub-steps-out-to-the-state-holding-both-ends
  (let [views (:views (ex/plan f/nested {}))
        go    (:go (get views "c0.app/running"))]
    (is (str/includes? (:dot (get views "c0.app/running")) "id=\"stub:app/paused\""))
    (is (= {:view "c0.app" :hint "Out to app"} (get go "stub:app/paused")))
    (testing "the title's breadcrumb leads out too"
      (is (= {:view "c0.app" :hint "Out to app"} (get go "a_crumb:app"))))
    (testing "only what the view draws is clickable"
      (is (every? #(or (str/includes? (:dot (get views "c0.app/running")) (str "id=\"" % "\""))
                       (str/includes? (:dot (get views "c0.app/running")) (str "ID=\"" (subs % 2) "\"")))
                  (keys go)))
      (is (nil? (get go "a_crumb:ROOT")) "a zoomed view has no title, so no part for the whole chart")
      (is (nil? (get go "state:app"))))))

(deftest only-a-charts-own-view-is-titled
  (let [views (:views (ex/plan f/nested {:title "My app"}))]
    (is (str/includes? (:dot (get views "c0")) "label=\"My app\""))
    (is (not (str/includes? (:dot (get views "c0.app/running")) "My app")) "the page's breadcrumb names it")))

(deftest the-only-top-state-is-the-whole-chart
  (let [{:keys [views]} (ex/plan f/with-invoke {})]
    (testing "no view of its own, so it does not open the same drawing again"
      (is (not (contains? views "c0.game")))
      (is (nil? (get-in views ["c0" :go "state:game"]))))))

(deftest a-chart-invoking-itself-is-one-chart
  (let [{:keys [views]} (ex/plan f/with-invoke {:charts {'statechart-viz.fixtures/child f/with-invoke}})]
    (is (= #{"c0" "c1"} (set (keys views))))))

(deftest the-page
  (if-not (r/dot-available?)
    (println "SKIP the-page: Graphviz dot is not on PATH")
    (let [html (ex/fragment f/with-invoke {:charts charts :dark {:background "#111111"}})
          data (second (re-find #"(?s)<script type=\"application/json\">(.*?)</script>" html))]
      (testing "every view rendered, in both themes"
        (is (= 2 (count (re-seq #"\"light\":\"\\u003csvg" data))))
        (is (= 2 (count (re-seq #"\"dark\":\"\\u003csvg" data)))))
      (testing "the data can't close its <script> early"
        (is (not (str/includes? data "<"))))
      (testing "zoom controls, and a wheel listener that can stop the page's scroll"
        (is (every? #(str/includes? html %) ["class=\"sv-out\"" "class=\"sv-in\"" "class=\"sv-fit\"" "'wheel'" "{passive:false}"])))
      (testing "a whole page around it"
        (is (str/starts-with? (ex/page f/with-invoke {:charts charts}) "<!doctype html>"))))))
