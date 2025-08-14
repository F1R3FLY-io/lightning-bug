(ns test.app.views.logs-test
  (:require
    ["react" :as react]
    ["react-dom/client" :as rdclient]
    [reagent.core :as r]
    [app.events :as e]
    [re-com.core :as rc]
    [re-frame.core :as rf]
    [clojure.test :refer [deftest is use-fixtures]]
    [datascript.core :as d]
    [day8.re-frame.test :as rf-test]
    [re-frame.db :refer [app-db]]
    [re-posh.core :as rp]
    [app.db :refer [default-db ds-conn]]
    [app.views.logs :as logs]
    [taoensso.timbre :as log]))

(set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) true)

;; Mock WebSocket to prevent real LSP connections during tests, avoiding connection errors.
(def mock-socket (js/Object.))
(set! (.-binaryType mock-socket) "arraybuffer")
(set! (.-onopen mock-socket) (fn []))
(set! (.-onmessage mock-socket) (fn []))
(set! (.-onclose mock-socket) (fn []))
(set! (.-onerror mock-socket) (fn []))
(set! (.-send mock-socket) (fn [_]))

(def old-ws js/WebSocket)

(defn act-flush
  [f]
  (react/act (fn [] (f) (r/flush))))

(defn act-mount
  [container comp]
  (let [root-atom (atom nil)]
    (act-flush
     (fn []
       (let [root (rdclient/createRoot container)]
         (.render root (r/as-element comp))
         (reset! root-atom root))))
    @root-atom))

(defn act-unmount [root]
  (act-flush #(.unmount root)))

(use-fixtures :each
  {:before (fn []
             (set! js/WebSocket (fn [_] mock-socket)) ;; Existing mock.
             (log/set-min-level! :info)
             (d/reset-conn! ds-conn (d/empty-db (:schema default-db)))
             (reset! app-db {})
             (rf/dispatch-sync [::e/initialize])
             ;; Remove :lsp-url from all languages to prevent any connection attempts.
             (swap! app-db update :languages
                    (fn [langs]
                      (into {} (map (fn [[k v]] [k (dissoc v :lsp-url)]) langs))))
             (swap! app-db assoc :languages {"text" {:extensions [".txt"]
                                                     :fallback-highlighter "none"
                                                     :file-icon "fas fa-file text-secondary"}} :default-language "text")
             (let [active (get-in @app-db [:workspace :active-file])]
               (swap! app-db assoc-in [:workspace :files active :language] "text")
               (swap! app-db assoc-in [:workspace :files active :name] "untitled.txt"))
             (rf/clear-subscription-cache!)
             (rp/connect! ds-conn))
   :after (fn []
            (set! js/WebSocket old-ws)
            (log/set-min-level! :debug))}) ;; Existing restore.

(deftest logs-renders
  (rf-test/run-test-sync
   (let [container (js/document.createElement "div")]
     (js/document.body.appendChild container)
     (let [root (act-mount container [logs/component])]
       (log/debug "Checking for heading button after render")
       (is (some? (.querySelector container "button")) "Heading button rendered")
       (is (some? (.querySelector container ".fa-chevron-up")) "Up arrow when collapsed")
       (is (some? (.querySelector container ".text-muted")) "No diagnostics message shown")
       (act-unmount root))
     (js/document.body.removeChild container))))

(deftest logs-toggle
  (rf-test/run-test-sync
   (act-flush #(rf/dispatch [::e/lsp-diagnostics-update [{:uri "inmemory://test.rho"
                                               :message "Test diagnostic"
                                               :startLine 0
                                               :startChar 0
                                               :endLine 0
                                               :endChar 5
                                               :severity 1}]]))
   (let [container (js/document.createElement "div")]
     (js/document.body.appendChild container)
     (let [root (act-mount container [logs/component])]
       (log/debug "Verifying content hidden initially")
       (is (nil? (.querySelector container ".logs-content")) "Content hidden initially")
       (act-flush #(rf/dispatch [::e/toggle-logs]))
       (log/debug "Verifying content visible after toggle")
       (is (some? (.querySelector container ".logs-content")) "Content visible after toggle")
       (is (some? (.querySelector container ".fa-chevron-down")) "Down arrow when expanded")
       (act-unmount root))
     (js/document.body.removeChild container))))

(deftest logs-clear-diagnostics
  (rf-test/run-test-sync
   (act-flush #(rf/dispatch [::e/lsp-diagnostics-update [{:uri "inmemory://test.rho"
                                               :message "Test diagnostic 1"
                                               :startLine 0
                                               :startChar 0
                                               :endLine 0
                                               :endChar 5
                                               :severity 1}]]))
   (let [container (js/document.createElement "div")]
     (js/document.body.appendChild container)
     (let [root (act-mount container [logs/component])]
       (act-flush #(rf/dispatch [::e/toggle-logs]))
       (log/debug "Verifying one diagnostic shown initially")
       (is (= 1 (.-length (.querySelectorAll container ".text-danger"))) "One diagnostic shown")
       (act-flush #(rf/dispatch [::e/lsp-diagnostics-update []]))
       (log/debug "Verifying no diagnostics message after clear")
       (is (some? (.querySelector container ".text-muted")) "No diagnostics message shown after clear")
       (act-unmount root))
     (js/document.body.removeChild container))))

(deftest logs-navigate-on-click
  (rf-test/run-test-sync
   (act-flush #(rf/dispatch [::e/lsp-diagnostics-update [{:uri "inmemory://test.rho"
                                               :message "Test diagnostic"
                                               :startLine 0
                                               :startChar 0
                                               :endLine 0
                                               :endChar 5
                                               :severity 1}]]))
   (let [container (js/document.createElement "div")]
     (js/document.body.appendChild container)
     (let [root (act-mount container [logs/component])]
       (act-flush #(rf/dispatch [::e/toggle-logs]))
       (let [label (.querySelector container ".text-danger")]
         (log/debug "Simulating click on diagnostic label")
         (is (some? label) "Diagnostic label found")
         (act-flush #(.dispatchEvent label (js/MouseEvent. "click"))))
       (is (= {:line 1 :column 1} @(rf/subscribe [:editor/cursor])) "Cursor set to diagnostic position")
       (act-unmount root))
     (js/document.body.removeChild container))))

(deftest logs-highlight-on-hover
  (rf-test/run-test-sync
   (act-flush #(rf/dispatch [::e/lsp-diagnostics-update [{:uri "inmemory://test.rho"
                                               :message "Test diagnostic"
                                               :startLine 0
                                               :startChar 0
                                               :endLine 0
                                               :endChar 5
                                               :severity 1}]]))
   (let [container (js/document.createElement "div")]
     (js/document.body.appendChild container)
     (let [root (act-mount container [logs/component])]
       (act-flush #(rf/dispatch [::e/toggle-logs]))
       (let [label (.querySelector container ".text-danger")]
         (log/debug "Simulating mouseenter on diagnostic label")
         (is (some? label) "Diagnostic label found")
         (act-flush #(.dispatchEvent label (js/MouseEvent. "mouseenter"))))
       (is (= {:from {:line 1 :column 1} :to {:line 1 :column 6}} @(rf/subscribe [:editor/highlights])) "Range highlighted on hover")
       (act-unmount root))
     (js/document.body.removeChild container))))

(deftest logs-clear-highlight-on-leave
  (rf-test/run-test-sync
   (act-flush #(rf/dispatch [::e/lsp-diagnostics-update [{:uri "inmemory://test.rho"
                                               :message "Test diagnostic"
                                               :startLine 0
                                               :startChar 0
                                               :endLine 0
                                               :endChar 5
                                               :severity 1}]]))
   (let [container (js/document.createElement "div")]
     (js/document.body.appendChild container)
     (let [root (act-mount container [logs/component])]
       (act-flush #(rf/dispatch [::e/toggle-logs]))
       (let [label (.querySelector container ".text-danger")]
         (log/debug "Simulating mouseenter then mouseleave on diagnostic label")
         (is (some? label) "Diagnostic label found")
         (act-flush #(.dispatchEvent label (js/MouseEvent. "mouseenter")))
         (act-flush #(.dispatchEvent label (js/MouseEvent. "mouseleave"))))
       (is (nil? @(rf/subscribe [:editor/highlights])) "Highlight cleared on mouse leave")
       (act-unmount root))
     (js/document.body.removeChild container))))

(deftest logs-resize-height
  (rf-test/run-test-sync
   (let [observer-callback (atom nil)]
     (with-redefs [js/ResizeObserver (fn [cb]
                                       (reset! observer-callback cb)
                                      #js {:observe (fn [_] nil)
                                            :disconnect (fn [] nil)})]
       (let [container (js/document.createElement "div")]
         (js/document.body.appendChild container)
         (let [root (act-mount container [logs/component])]
           (act-flush #(rf/dispatch [::e/toggle-logs]))
           (let [content (.querySelector container ".logs-content")]
             (log/debug "Simulating resize on logs content")
             (is (some? content) "Logs content found")
             (set! (.-offsetHeight content) 150)
             (when @observer-callback
               (act-flush #(@observer-callback [#js {:target content :offsetHeight 150}])))
             (is (= 150 @(rf/subscribe [:logs-height])) "Height updated on resize"))
           (act-unmount root))
         (js/document.body.removeChild container))))))

(deftest logs-resize-observer-setup
  (rf-test/run-test-sync
   (let [observer-called (atom false)]
     (with-redefs [js/ResizeObserver (fn [callback]
                                       (reset! observer-called true)
                                       #js {:observe (fn [_] nil) :disconnect (fn [] nil)})]
       (let [container (js/document.createElement "div")]
         (js/document.body.appendChild container)
         (let [root (act-mount container [logs/component])]
           (act-flush #(rf/dispatch [::e/toggle-logs]))
           (log/debug "Verifying ResizeObserver instantiation")
           (is @observer-called "ResizeObserver was instantiated")
           (act-unmount root))
         (js/document.body.removeChild container))))))

(deftest logs-content-component-renders
  (rf-test/run-test-sync
   (act-flush #(rf/dispatch [::e/lsp-diagnostics-update [{:uri "inmemory://test.rho"
                                               :message "Test diagnostic"
                                               :startLine 0
                                               :startChar 0
                                               :endLine 0
                                               :endChar 5
                                               :severity 1}]]))
   (let [container (js/document.createElement "div")]
     (js/document.body.appendChild container)
     (let [root (act-mount container [:f> logs/logs-content
                                      {:visible? true
                                       :diagnostics [{:diagnostic/message "Test diagnostic"
                                                      :diagnostic/start-line 0
                                                      :diagnostic/start-char 0
                                                      :diagnostic/end-line 0
                                                      :diagnostic/end-char 5
                                                      :diagnostic/severity 1}]
                                       :height 200
                                       :on-resize (fn [_] nil)}])]
       (log/debug "Verifying logs content render")
       (is (some? (.querySelector container ".logs-content")) "Logs content component rendered")
       (is (some? (.querySelector container ".text-danger")) "Diagnostic message rendered")
       (act-unmount root))
     (js/document.body.removeChild container))))

(deftest logs-hook-compatibility
  (rf-test/run-test-sync
   (let [ref-called (atom false)
         effect-called (atom false)
         cleanup-called (atom false)]
     (with-redefs [react/useRef (fn []
                                  (reset! ref-called true)
                                  #js {:current nil})
                   react/useEffect (fn [f deps]
                                     (reset! effect-called true)
                                     (let [cleanup (f)]
                                       (when (fn? cleanup)
                                         (reset! cleanup-called true)))
                                     #js [])]
       (let [container (js/document.createElement "div")]
         (js/document.body.appendChild container)
         (let [root (act-mount container [logs/component])]
           (act-flush #(rf/dispatch [::e/toggle-logs]))
           (log/debug "Verifying React hooks compatibility")
           (is @ref-called "useRef hook was called successfully")
           (is @effect-called "useEffect hook was called successfully")
           (is @cleanup-called "useEffect cleanup function was called successfully")
           (act-unmount root))
         (js/document.body.removeChild container))))))

(deftest logs-resize-integration
  (rf-test/run-test-sync
   (let [observer-callback (atom nil)]
     (with-redefs [js/ResizeObserver (fn [cb]
                                       (reset! observer-callback cb)
                                       #js {:observe (fn [_] nil)
                                            :disconnect (fn [] nil)})]
       (let [container (js/document.createElement "div")]
         (js/document.body.appendChild container)
         (let [root (act-mount container [logs/component])]
           (act-flush #(rf/dispatch [::e/toggle-logs]))
           (let [content (.querySelector container ".logs-content")]
             (log/debug "Full resize integration: simulating height change")
             (is (some? content) "Logs content found")
             (set! (.-offsetHeight content) 150)
             (when @observer-callback
               (act-flush #(@observer-callback [#js {:target content :offsetHeight 150}]))))
           (is (= 150 @(rf/subscribe [:logs-height])) "Height updated asynchronously on resize")
           (act-flush #(rf/dispatch [::e/toggle-logs]))
           (is (nil? (.querySelector container ".logs-content")) "Content hidden after toggle")
           (act-unmount root))
         (js/document.body.removeChild container))))))

(deftest logs-full-cycle-integration
  (rf-test/run-test-sync
   (act-flush #(rf/dispatch [::e/lsp-diagnostics-update [{:uri "inmemory://test.rho"
                                               :message "Test diagnostic"
                                               :startLine 0
                                               :startChar 0
                                               :endLine 0
                                               :endChar 5
                                               :severity 1}]]))
   (let [container (js/document.createElement "div")]
     (js/document.body.appendChild container)
     (let [root (act-mount container [logs/component])]
       (log/debug "Starting full cycle: toggle, hover, click, resize")
       (is (some? (.querySelector container ".logs-panel")) "Initial render")
       (act-flush #(rf/dispatch [::e/toggle-logs]))
       (let [label (.querySelector container ".text-danger")]
         (act-flush #(.dispatchEvent label (js/MouseEvent. "mouseenter")))
         (is (some? @(rf/subscribe [:editor/highlights])) "Highlighted on hover")
         (act-flush #(.dispatchEvent label (js/MouseEvent. "click")))
         (is (some? @(rf/subscribe [:editor/cursor])) "Cursor set on click")
         (act-flush #(.dispatchEvent label (js/MouseEvent. "mouseleave")))
         (is (nil? @(rf/subscribe [:editor/highlights])) "Highlight cleared on leave"))
       (let [content (.querySelector container ".logs-content")]
         (set! (.-offsetHeight content) 300)
         (is (= 300 @(rf/subscribe [:logs-height])) "Height updated on resize"))
       (act-unmount root))
     (js/document.body.removeChild container))))
