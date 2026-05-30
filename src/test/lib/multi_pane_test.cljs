(ns test.lib.multi-pane-test
  "Phase 8 — integration tests for reactive cross-pane sync: two <Editor> panes sharing
  ONE Workspace and showing the SAME file (the split-pane case). Proves live propagation,
  cursor preservation under a remote edit, no echo back to the origin, and bidirectional
  flow. The transport itself (ref-counting, echo-filter, seq ordering, ChangeSet apply) is
  unit-tested in test.lib.workspace.doc-sync-test; here we prove the end-to-end wiring
  through real mounted editors and real CodeMirror views."
  (:require
   [clojure.test :refer [deftest is testing async use-fixtures]]
   [clojure.core.async :refer [go <! timeout]]
   [reagent.core :as r]
   ["react" :as react]
   ["react-dom/client" :as rdclient]
   ["@codemirror/view" :refer [EditorView]]
   [lib.core :refer [Editor createWorkspace]]
   [lib.workspace :as ws]
   [lib.editor.syntax :as syntax]
   [lib.state :refer [resources]]))

(use-fixtures :each
  {:before (fn []
             (reset! resources {:lsp {} :tree-sitter {}})
             @syntax/ts-init-promise
             (reset! syntax/languages {})
             (ws/reset-workspace! @ws/default-workspace))})

(defn- mount-editor!
  "Mounts an <Editor> bound to `workspace` into a fresh DOM container.
  Returns {:root :container :ref-atom}; ref-atom is filled with the editor handle."
  [workspace]
  (let [container (js/document.createElement "div")
        ref-atom (atom nil)
        root (rdclient/createRoot container)]
    (js/document.body.appendChild container)
    (.render root (react/createElement Editor #js {:workspace workspace
                                                   :ref (fn [r] (reset! ref-atom r))}))
    (r/flush)
    {:root root :container container :ref-atom ref-atom}))

(defn- unmount! [{:keys [root container]}]
  (.unmount root)
  (when #_{:splint/disable [style/prefer-clj-string]}
        (.contains js/document.body container)
        (js/document.body.removeChild container)))

(defn- view-of
  "The live CodeMirror EditorView for a mounted pane (via the DOM)."
  [{:keys [container]}]
  (.findFromDOM EditorView (.querySelector container ".cm-editor")))

(defn- doc-str [^js view]
  (.. view -state -doc (toString)))

(defn- cursor-head [^js view]
  (.. view -state -selection -main -head))

(deftest split-panes-sync-live
  (testing "two panes over one file: A's user edit propagates live to B, B's cursor rebases, no echo"
    (async done
      (go
        (let [workspace (createWorkspace)
              pane-a (mount-editor! workspace)
              pane-b (mount-editor! workspace)
              uri "inmemory:///split.txt"]
          (<! (timeout 200))                       ; both editors mount + views ready
          (let [ea @(:ref-atom pane-a)
                eb @(:ref-atom pane-b)]
            (is (some? ea) "pane A handle present")
            (is (some? eb) "pane B handle present")
            (.openDocument ea uri "hello world" "text")  ; creates + activates in shared ws
            (<! (timeout 250))
            (.activateDocument eb uri)               ; B splits onto the same file
            (<! (timeout 250))
            (let [va (view-of pane-a)
                  vb (view-of pane-b)]
              (is (some? va) "pane A view exists")
              (is (some? vb) "pane B view exists")
              (is (= "hello world" (doc-str va)) "A is seeded with the file content")
              (is (= "hello world" (doc-str vb)) "B is seeded from the shared workspace")
              ;; Put B's caret after "hello" (position 5). Selection-only change: no publish.
              (.dispatch vb #js {:selection #js {:anchor 5}})
              ;; Simulate a USER edit in A (un-annotated): insert "XYZ " at the start.
              (.dispatch va #js {:changes #js {:from 0 :insert "XYZ "}})
              (<! (timeout 150))
              (is (= "XYZ hello world" (doc-str vb)) "A's edit propagated live to B")
              (is (= 9 (cursor-head vb)) "B's caret rebased 5 -> 9 (not reset to 0)")
              (is (= "XYZ hello world" (doc-str va)) "A shows exactly its own edit (no echo back)")
              (unmount! pane-a)
              (unmount! pane-b)
              (done))))))))

(deftest split-panes-sync-bidirectional
  (testing "either pane can drive: an edit in B also propagates to A"
    (async done
      (go
        (let [workspace (createWorkspace)
              pane-a (mount-editor! workspace)
              pane-b (mount-editor! workspace)
              uri "inmemory:///bidir.txt"]
          (<! (timeout 200))
          (let [ea @(:ref-atom pane-a)
                eb @(:ref-atom pane-b)]
            (.openDocument ea uri "base" "text")
            (<! (timeout 250))
            (.activateDocument eb uri)
            (<! (timeout 250))
            (let [va (view-of pane-a)
                  vb (view-of pane-b)]
              (is (= "base" (doc-str va)) "A seeded")
              (is (= "base" (doc-str vb)) "B seeded")
              ;; Edit in A -> B follows.
              (.dispatch va #js {:changes #js {:from 4 :insert "-A"}})
              (<! (timeout 120))
              (is (= "base-A" (doc-str vb)) "A->B propagation")
              ;; Now edit in B -> A follows (B is also a publisher).
              (.dispatch vb #js {:changes #js {:from 6 :insert "-B"}})
              (<! (timeout 120))
              (is (= "base-A-B" (doc-str va)) "B->A propagation")
              (is (= "base-A-B" (doc-str vb)) "B consistent with its own edit")
              (unmount! pane-a)
              (unmount! pane-b)
              (done))))))))
