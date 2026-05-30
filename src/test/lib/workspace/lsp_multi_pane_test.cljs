(ns test.lib.workspace.lsp-multi-pane-test
  "Phase 5b — integration tests proving the LSP layer is per-workspace/per-file:
  - two split panes over one file in one workspace share ONE connection and emit exactly
    ONE didOpen (not one per pane);
  - edits made in EITHER pane reach the language server as didChange with monotonic
    versions (the 2nd pane could not send before Phase 5b);
  - a connection in one workspace does not leak into another (resource + state isolation).

  A language needs only an :lsp-url (no grammar) to connect, so these use a `text` language
  with a mock LSP — no tree-sitter wasm required."
  (:require
   [clojure.test :refer [deftest is testing async use-fixtures]]
   [clojure.core.async :refer [go <! timeout]]
   [reagent.core :as r]
   ["react" :as react]
   ["react-dom/client" :as rdclient]
   ["@codemirror/view" :refer [EditorView]]
   [lib.core :refer [Editor createWorkspace]]
   [lib.workspace :as ws]
   [lib.state :as state :refer [resources get-resource]]
   [lib.editor.syntax :as syntax]
   [test.lib.mock-lsp :refer [with-mock-lsp get-sent-methods get-sent-messages]]))

(use-fixtures :each
  {:before (fn []
             (reset! resources {:lsp {} :tree-sitter {}})
             @syntax/ts-init-promise
             (reset! syntax/languages {})
             (ws/reset-workspace! @ws/default-workspace))})

(def ^:private lsp-langs {"text" {:extensions [".txt"] :lsp-url "ws://mock"}})

(defn- mount-editor! [workspace langs]
  (let [container (js/document.createElement "div")
        ref-atom (atom nil)
        root (rdclient/createRoot container)]
    (js/document.body.appendChild container)
    (.render root (react/createElement Editor #js {:workspace workspace
                                                   :languages (clj->js langs)
                                                   :ref (fn [r] (reset! ref-atom r))}))
    (r/flush)
    {:root root :container container :ref-atom ref-atom}))

(defn- unmount! [{:keys [root container]}]
  (.unmount root)
  (when #_{:splint/disable [style/prefer-clj-string]}
        (.contains js/document.body container)
        (js/document.body.removeChild container)))

(defn- view-of [{:keys [container]}]
  (.findFromDOM EditorView (.querySelector container ".cm-editor")))

(defn- count-method [mock method]
  (count (filter #(= % method) (get-sent-methods mock))))

(defn- didchange-versions [mock]
  (->> (get-sent-messages mock)
       (filter #(= "textDocument/didChange" (get-in % [:body :method])))
       (map #(get-in % [:body :params :textDocument :version]))))

(deftest split-pane-lsp-single-connection-and-didopen
  (testing "two panes over one LSP file share one connection and emit exactly one didOpen"
    (async done
      (go
        (let [res (<! (with-mock-lsp
                        (fn [mock]
                          (go
                            (let [workspace (createWorkspace)
                                  pane-a (mount-editor! workspace lsp-langs)
                                  pane-b (mount-editor! workspace lsp-langs)
                                  uri "inmemory:///lsp-split.txt"]
                              (<! (timeout 200))
                              (let [ea @(:ref-atom pane-a)
                                    eb @(:ref-atom pane-b)]
                                (.openDocument ea uri "hello" "text")   ; creates the socket
                                (<! (timeout 300))
                                ((:trigger-open mock))                  ; fire onopen -> initialize + didOpen
                                (<! (timeout 400))
                                (.activateDocument eb uri)              ; same file, 2nd pane
                                (<! (timeout 400))
                                (is (= 1 (count-method mock "initialize"))
                                    "exactly one LSP connection initialized for two panes")
                                (is (= 1 (count-method mock "textDocument/didOpen"))
                                    "exactly one didOpen for the shared file")
                                ;; 2nd pane's hot-path cache was populated, so it knows the file is open
                                (is (get-in (js->clj (.getState eb) :keywordize-keys true)
                                            [:lsp :text :connected?])
                                    "pane B sees the shared connection as connected")
                                (unmount! pane-a)
                                (unmount! pane-b)
                                [:ok true]))))))]
          (is (= :ok (first res)) "mock-lsp body completed without error")
          (done))))))

(deftest split-pane-lsp-edits-from-both-panes-send-monotonic-didchange
  (testing "edits in EITHER pane reach the server as didChange with monotonic versions"
    (async done
      (go
        (let [res (<! (with-mock-lsp
                        (fn [mock]
                          (go
                            (let [workspace (createWorkspace)
                                  pane-a (mount-editor! workspace lsp-langs)
                                  pane-b (mount-editor! workspace lsp-langs)
                                  uri "inmemory:///lsp-edits.txt"]
                              (<! (timeout 200))
                              (let [ea @(:ref-atom pane-a)
                                    eb @(:ref-atom pane-b)]
                                (.openDocument ea uri "abc" "text")
                                (<! (timeout 300))
                                ((:trigger-open mock))
                                (<! (timeout 400))
                                (.activateDocument eb uri)
                                (<! (timeout 400))
                                (let [va (view-of pane-a)
                                      vb (view-of pane-b)]
                                  ;; user edit in A -> one didChange; space past the debounce
                                  (.dispatch va #js {:changes #js {:from 0 :insert "A"}})
                                  (<! (timeout 800))
                                  ;; user edit in B (the pane that could NOT send before 5b)
                                  (.dispatch vb #js {:changes #js {:from 0 :insert "B"}})
                                  (<! (timeout 800))
                                  (let [versions (vec (didchange-versions mock))]
                                    (is (>= (count versions) 2)
                                        "at least two didChanges (one per pane's edit)")
                                    (is (apply < versions)
                                        (str "didChange versions strictly increasing: " versions))
                                    ;; both views converged to the same content (XYZ ordering aside)
                                    (is (= (str (.. va -state -doc)) (str (.. vb -state -doc)))
                                        "panes converged"))
                                  (unmount! pane-a)
                                  (unmount! pane-b)
                                  [:ok true])))))))]
          (is (= :ok (first res)) "mock-lsp body completed without error")
          (done))))))

(deftest distinct-workspaces-isolated-lsp-state
  (testing "an LSP connection in one workspace does not leak into another"
    (async done
      (go
        (let [res (<! (with-mock-lsp
                        (fn [mock]
                          (go
                            (let [ws1 (createWorkspace)
                                  ws2 (createWorkspace)
                                  pane (mount-editor! ws1 lsp-langs)]
                              (<! (timeout 200))
                              (.openDocument @(:ref-atom pane) "inmemory:///iso-lsp.txt" "x" "text")
                              (<! (timeout 300))
                              ((:trigger-open mock))
                              (<! (timeout 400))
                              ;; ws1 connected...
                              (is (get-in @(:lsp ws1) [:lsp "text" :connected?])
                                  "ws1 has a connected LSP")
                              (is (some? (get-resource (:resources ws1) :lsp "text"))
                                  "ws1 holds the LSP socket resource")
                              ;; ...ws2 is untouched
                              (is (empty? (:lsp @(:lsp ws2)))
                                  "ws2 LSP state is empty (no leak)")
                              (is (nil? (get-resource (:resources ws2) :lsp "text"))
                                  "ws2 holds no LSP socket")
                              (unmount! pane)
                              [:ok true]))))) ]
          (is (= :ok (first res)) "mock-lsp body completed without error")
          (done))))))
