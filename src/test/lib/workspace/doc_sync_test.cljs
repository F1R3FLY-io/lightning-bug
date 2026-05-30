(ns test.lib.workspace.doc-sync-test
  "Unit tests for the reactive cross-pane document-sync transport (Phase 4).
  The full two-editor integration test lives in multi_pane_test."
  (:require
   [clojure.test :refer [deftest is testing]]
   [lib.workspace :as ws]
   [lib.workspace.doc-sync :as ds]
   ["@codemirror/state" :refer [EditorState ChangeSet]]
   ["@codemirror/view" :refer [EditorView]]))

(deftest ref-counting-and-has-peers
  (testing "stream ref-counting + has-peers?"
    (let [w (ws/make-workspace)
          uri "inmemory:///x.txt"]
      (is (false? (ds/has-peers? w uri)) "no subscribers -> no peers")
      (ds/get-or-create-stream! w uri)
      (is (false? (ds/has-peers? w uri)) "one subscriber -> no peers (single editor pays nothing)")
      (ds/get-or-create-stream! w uri)
      (is (true? (ds/has-peers? w uri)) "two subscribers -> peers")
      (ds/release-stream! w uri)
      (is (false? (ds/has-peers? w uri)) "back to one -> no peers")
      (ds/release-stream! w uri)
      (is (nil? (get @(:doc-streams w) uri)) "last release drops the stream"))))

(deftest echo-suppression-and-ordering
  (testing "a pane receives deltas from OTHER origins (not its own), stamped with seq"
    (let [w (ws/make-workspace)
          uri "inmemory:///y.txt"
          a-got (atom [])
          b-got (atom [])
          sub-a (ds/subscribe-pane w uri :pane-a (fn [d] (swap! a-got conj d)))
          sub-b (ds/subscribe-pane w uri :pane-b (fn [d] (swap! b-got conj d)))]
      (is (ds/has-peers? w uri) "two panes subscribed")
      (ds/publish-delta! w uri {:origin :pane-a :changes "CHANGES-A"})
      (is (= 0 (count @a-got)) "origin pane does NOT receive its own delta (echo-suppressed)")
      (is (= 1 (count @b-got)) "the other pane receives it")
      (is (= "CHANGES-A" (:changes (first @b-got))) "delta payload intact")
      (is (= uri (:uri (first @b-got))) "delta stamped with uri")
      (is (= 0 (:seq (first @b-got))) "first delta has seq 0")
      (ds/publish-delta! w uri {:origin :pane-b :changes "CHANGES-B"})
      (is (= 1 (count @a-got)) "pane-a now receives pane-b's delta")
      (is (= 1 (:seq (first @a-got))) "seq monotonically increments")
      (.unsubscribe sub-a)
      (.unsubscribe sub-b)
      (ds/release-stream! w uri)
      (ds/release-stream! w uri)
      ;; after both panes leave, a new publish reaches nobody (stream dropped)
      (ds/publish-delta! w uri {:origin :pane-a :changes "IGNORED"})
      (is (= 1 (count @a-got)) "no delivery after unsubscribe/release"))))

(deftest apply-remote-delta-updates-view
  (testing "applying a remote delta reconstructs the ChangeSet and updates the view doc"
    (let [view (EditorView. #js {:state (.create EditorState #js {:doc "hello" :extensions #js []})
                                 :parent js/document.body})
          changes (.of ChangeSet #js {:from 5 :insert " world"} 5)
          delta {:uri "u" :origin :other :changes (.toJSON changes)}]
      (ds/apply-remote-delta! view delta)
      (is (= "hello world" (str (.-doc (.-state view)))) "remote insert applied to peer view")
      (.destroy view))))
