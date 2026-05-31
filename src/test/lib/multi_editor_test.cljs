(ns test.lib.multi-editor-test
  "Phase 8 — integration tests for Workspace sharing vs isolation across multiple <Editor>
  instances. Proves: editors with no `workspace` prop share the single defonce default
  workspace; editors given the same createWorkspace() handle share one DataScript conn (so a
  document opened in one is visible to the other); editors given distinct workspaces are fully
  isolated (distinct conns; the same URI is two independent documents)."
  (:require
   [clojure.test :refer [deftest is testing async use-fixtures]]
   [clojure.core.async :refer [go <! timeout]]
   [reagent.core :as r]
   ["react" :as react]
   ["react-dom/client" :as rdclient]
   [lib.core :refer [Editor create-workspace]]
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
  "Mounts an <Editor>; `workspace` may be nil (resolves to the shared default).
  Returns {:root :container :ref-atom}."
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

(defn- editor-db [^js editor]
  (.getDb editor))

(defn- open-document! [^js editor uri text language]
  (.openDocument editor uri text language))

(defn- editor-text [^js editor uri]
  (.getText editor uri))

(deftest editors-share-default-workspace
  (testing "two editors with no workspace prop share the defonce default workspace conn"
    (async done
      (go
        (let [pane-a (mount-editor! nil)
              pane-b (mount-editor! nil)
              uri "inmemory:///default-shared.txt"]
          (<! (timeout 200))
          (let [^js ea @(:ref-atom pane-a)
                ^js eb @(:ref-atom pane-b)]
            (is (identical? (editor-db ea) (editor-db eb))
                "both editors resolve to the same default-workspace conn")
            (open-document! ea uri "shared via default" "text")
            (<! (timeout 200))
            (is (= "shared via default" (editor-text eb uri))
                "a document opened in A is visible to B (shared default workspace)")
            (unmount! pane-a)
            (unmount! pane-b)
            (done)))))))

(deftest editors-share-explicit-workspace
  (testing "two editors given the same createWorkspace() handle share one conn"
    (async done
      (go
        (let [workspace (create-workspace)
              pane-a (mount-editor! workspace)
              pane-b (mount-editor! workspace)
              uri "inmemory:///explicit-shared.txt"]
          (<! (timeout 200))
          (let [^js ea @(:ref-atom pane-a)
                ^js eb @(:ref-atom pane-b)]
            (is (identical? (editor-db ea) (editor-db eb))
                "both editors share the explicit workspace conn")
            (is (not (identical? (editor-db ea) (ws/default-conn)))
                "the explicit workspace is NOT the default workspace")
            (open-document! ea uri "shared explicitly" "text")
            (<! (timeout 200))
            (is (= "shared explicitly" (editor-text eb uri))
                "B sees the document A opened in the shared explicit workspace")
            (unmount! pane-a)
            (unmount! pane-b)
            (done)))))))

(deftest distinct-workspaces-are-isolated
  (testing "editors in distinct workspaces have distinct conns; same URI = independent docs"
    (async done
      (go
        (let [ws1 (create-workspace)
              ws2 (create-workspace)
              pane-a (mount-editor! ws1)
              pane-b (mount-editor! ws2)
              uri "inmemory:///iso.txt"]
          (<! (timeout 200))
          (let [^js ea @(:ref-atom pane-a)
                ^js eb @(:ref-atom pane-b)]
            (is (not (identical? (editor-db ea) (editor-db eb)))
                "distinct workspaces -> distinct conns")
            (open-document! ea uri "from A" "text")
            (<! (timeout 200))
            (is (nil? (editor-text eb uri))
                "B (isolated workspace) does NOT see the document A opened")
            (open-document! eb uri "from B" "text")
            (<! (timeout 200))
            (is (= "from A" (editor-text ea uri))
                "A's document is unaffected by B opening the same URI in another workspace")
            (is (= "from B" (editor-text eb uri))
                "B has its own independent document at the same URI")
            (unmount! pane-a)
            (unmount! pane-b)
            (done)))))))
