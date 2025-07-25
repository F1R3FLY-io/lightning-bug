(ns test.app.views.logs-test
  (:require
   ["react" :as react]
   [app.events :as e]
   [re-frame.core :as rf]
   [reagent.core :as r]
   [reagent.ratom :as ratom]
   [clojure.test :refer [deftest is async use-fixtures]]
   [clojure.core.async :refer [go <! timeout]]
   [datascript.core :as d]
   [re-frame.db :refer [app-db]]
   [re-posh.core :as rp]
   [app.db :refer [default-db ds-conn]]
   [app.views.logs :as logs]
   ["react-dom/client" :as rdclient]))

(use-fixtures :each
  {:before (fn []
             (d/reset-conn! ds-conn (d/empty-db (:schema default-db)))
             (reset! app-db {})
             (rf/dispatch-sync [::e/initialize])
             (let [active (get-in @app-db [:workspace :active-file])]
               (swap! app-db assoc :languages {"text" {:extensions [".txt"]
                                                       :fallback-highlighter "none"
                                                       :file-icon "fas fa-file text-secondary"}}
                                   :default-language "text")
               (swap! app-db assoc-in [:workspace :files active :language] "text")
               (swap! app-db assoc-in [:workspace :files active :name] "untitled.txt"))
             (rf/clear-subscription-cache!)
             (rp/connect! ds-conn))
   :after (fn [])})

(defn act-flush
  "Helper to wrap code in react/act and flush Reagent renders."
  [f]
  (react/act (fn [] (f) (r/flush))))

(defn act-mount
  "Mount component in act, returning root."
  [container comp]
  (let [root-atom (atom nil)]
    (act-flush
     (fn []
       (let [root (rdclient/createRoot container)]
         (.render root (r/as-element comp))
         (reset! root-atom root))))
    @root-atom))

(deftest logs-renders
  (async done
         (go
           (let [container (js/document.createElement "div")]
             (js/document.body.appendChild container)
             (let [root (act-mount container [logs/component])]
               (<! (timeout 10))
               (act-flush #(r/flush))
               (is (some? (.querySelector container "button")) "Heading button rendered")
               (is (some? (.querySelector container ".fa-chevron-up")) "Up arrow when collapsed")
               (is (some? (.querySelector container ".text-muted")) "No diagnostics message shown")
               (act-flush #(.unmount root)))
             (js/document.body.removeChild container)
             (done)))))

(deftest logs-toggle
  (async done
         (go
           (rf/dispatch-sync [::e/lsp-diagnostics-update [{:message "Test diagnostic"
                                                           :range {:start {:line 0 :character 0} :end {:line 0 :character 5}}
                                                           :severity 1}]])
           (let [container (js/document.createElement "div")]
             (js/document.body.appendChild container)
             (let [root (act-mount container [logs/component])]
               (<! (timeout 10))
               (act-flush #(r/flush))
               (is (nil? (.querySelector container ".logs-content")) "Content hidden initially")
               (act-flush #(rf/dispatch-sync [::e/toggle-logs]))
               (<! (timeout 10))
               (act-flush #(r/flush))
               (is (some? (.querySelector container ".logs-content")) "Content visible after toggle")
               (is (some? (.querySelector container ".fa-chevron-down")) "Down arrow when expanded")
               (act-flush #(.unmount root)))
             (js/document.body.removeChild container)
             (done)))))

(deftest logs-clear-diagnostics
  (async done
         (go
           (rf/dispatch-sync [::e/lsp-diagnostics-update [{:message "Test diagnostic 1"
                                                           :range {:start {:line 0 :character 0} :end {:line 0 :character 5}}
                                                           :severity 1}]])
           (let [container (js/document.createElement "div")]
             (js/document.body.appendChild container)
             (let [root (act-mount container [logs/component])]
               (<! (timeout 10))
               (act-flush #(r/flush))
               (act-flush #(rf/dispatch-sync [::e/toggle-logs]))
               (<! (timeout 10))
               (act-flush #(r/flush))
               (is (= 1 (.-length (.querySelectorAll container ".text-danger"))) "One diagnostic shown")
               (act-flush #(rf/dispatch-sync [::e/lsp-diagnostics-update []]))
               (<! (timeout 10))
               (act-flush #(r/flush))
               (is (some? (.querySelector container ".text-muted")) "No diagnostics message shown after clear")
               (act-flush #(.unmount root)))
             (js/document.body.removeChild container)
             (done)))))

(deftest logs-navigate-on-click
  (async done
         (go
           (rf/dispatch-sync [::e/lsp-diagnostics-update [{:message "Test diagnostic"
                                                           :range {:start {:line 0 :character 0} :end {:line 0 :character 5}}
                                                           :severity 1}]])
           (let [container (js/document.createElement "div")]
             (js/document.body.appendChild container)
             (let [root (act-mount container [logs/component])]
               (<! (timeout 10))
               (act-flush #(r/flush))
               (act-flush #(rf/dispatch-sync [::e/toggle-logs]))
               (<! (timeout 10))
               (act-flush #(r/flush))
               (let [label (.querySelector container ".text-danger")]
                 (is (some? label) "Diagnostic label found")
                 (act-flush #(.dispatchEvent label (js/MouseEvent. "click")))
                 (<! (timeout 10))
                 (act-flush #(r/flush))
                 (binding [ratom/*ratom-context* (ratom/make-reaction (fn []))]
                   (is (= {:line 1 :column 1} @(rf/subscribe [:editor-cursor-pos])) "Cursor set to diagnostic position")))
               (act-flush #(.unmount root)))
             (js/document.body.removeChild container)
             (done)))))

(deftest logs-highlight-on-hover
  (async done
         (go
           (rf/dispatch-sync [::e/lsp-diagnostics-update [{:message "Test diagnostic"
                                                           :range {:start {:line 0 :character 0} :end {:line 0 :character 5}}
                                                           :severity 1}]])
           (let [container (js/document.createElement "div")]
             (js/document.body.appendChild container)
             (let [root (act-mount container [logs/component])]
               (<! (timeout 10))
               (act-flush #(r/flush))
               (act-flush #(rf/dispatch-sync [::e/toggle-logs]))
               (<! (timeout 10))
               (act-flush #(r/flush))
               (let [label (.querySelector container ".text-danger")]
                 (is (some? label) "Diagnostic label found")
                 (act-flush #(.dispatchEvent label (js/MouseEvent. "mouseenter")))
                 (<! (timeout 10))
                 (act-flush #(r/flush))
                 (binding [ratom/*ratom-context* (ratom/make-reaction (fn []))]
                   (is (= {:from {:line 1 :column 1} :to {:line 1 :column 6}} @(rf/subscribe [:highlight-range])) "Range highlighted on hover")))
               (act-flush #(.unmount root)))
             (js/document.body.removeChild container)
             (done)))))

(deftest logs-clear-highlight-on-leave
  (async done
         (go
           (rf/dispatch-sync [::e/lsp-diagnostics-update [{:message "Test diagnostic"
                                                           :range {:start {:line 0 :character 0} :end {:line 0 :character 5}}
                                                           :severity 1}]])
           (let [container (js/document.createElement "div")]
             (js/document.body.appendChild container)
             (let [root (act-mount container [logs/component])]
               (<! (timeout 10))
               (act-flush #(r/flush))
               (act-flush #(rf/dispatch-sync [::e/toggle-logs]))
               (<! (timeout 10))
               (act-flush #(r/flush))
               (let [label (.querySelector container ".text-danger")]
                 (is (some? label) "Diagnostic label found")
                 (act-flush #(.dispatchEvent label (js/MouseEvent. "mouseenter")))
                 (<! (timeout 10))
                 (act-flush #(r/flush))
                 (act-flush #(.dispatchEvent label (js/MouseEvent. "mouseleave")))
                 (<! (timeout 10))
                 (act-flush #(r/flush))
                 (binding [ratom/*ratom-context* (ratom/make-reaction (fn []))]
                   (is (nil? @(rf/subscribe [:highlight-range])) "Highlight cleared on mouse leave")))
               (act-flush #(.unmount root)))
             (js/document.body.removeChild container)
             (done)))))

(deftest logs-resize-height
  (async done
         (go
           (let [observer-callback (atom nil)
                 container (js/document.createElement "div")]
             (with-redefs [js/ResizeObserver (fn [cb]
                                               (reset! observer-callback cb)
                                               #js {:observe (fn [_])
                                                    :disconnect (fn [])})]
               (js/document.body.appendChild container)
               (let [root (act-mount container [logs/component])]
                 (<! (timeout 10))
                 (act-flush #(r/flush))
                 (act-flush #(rf/dispatch-sync [::e/toggle-logs]))
                 (<! (timeout 10))
                 (act-flush #(r/flush))
                 (let [content (.querySelector container ".logs-content")]
                   (is (some? content) "Logs content found")
                   (act-flush #(do (set! (.-style.height content) "150px")
                                   (when @observer-callback
                                     (@observer-callback [#js {:target content :contentRect #js {:height 150}}]))))
                   (<! (timeout 10))
                   (act-flush #(r/flush))
                   (binding [ratom/*ratom-context* (ratom/make-reaction (fn []))]
                     (is (= 150 @(rf/subscribe [:logs-height])) "Height updated on resize")))
                 (act-flush #(.unmount root))))
             (js/document.body.removeChild container)
             (done)))))

(deftest logs-resize-observer-setup
  (async done
         (go
           (let [container (js/document.createElement "div")
                 observer-called (atom false)]
             (js/document.body.appendChild container)
             (with-redefs [js/ResizeObserver (fn [callback]
                                               (reset! observer-called true)
                                               #js {:observe (fn [_]), :disconnect (fn [])})]
               (let [root (act-mount container [logs/component])]
                 (<! (timeout 10))
                 (act-flush #(r/flush))
                 (act-flush #(rf/dispatch-sync [::e/toggle-logs]))
                 (<! (timeout 10))
                 (act-flush #(r/flush))
                 (is @observer-called "ResizeObserver was instantiated")
                 (act-flush #(.unmount root))))
             (js/document.body.removeChild container)
             (done)))))

(deftest logs-content-component-renders
  (async done
         (go
           (rf/dispatch-sync [::e/lsp-diagnostics-update [{:message "Test diagnostic"
                                                           :range {:start {:line 0 :character 0} :end {:line 0 :character 5}}
                                                           :severity 1}]])
           (let [container (js/document.createElement "div")]
             (js/document.body.appendChild container)
             (let [root (act-mount container [:f> logs/logs-content
                                              {:visible? true
                                               :diagnostics [{:message "Test diagnostic"
                                                              :range {:start {:line 0 :character 0} :end {:line 0 :character 5}}
                                                              :severity 1}]
                                               :height 200
                                               :on-resize (fn [_])}])]
               (<! (timeout 10))
               (act-flush #(r/flush))
               (is (some? (.querySelector container ".logs-content")) "Logs content component rendered")
               (is (some? (.querySelector container ".text-danger")) "Diagnostic message rendered")
               (act-flush #(.unmount root)))
             (js/document.body.removeChild container)
             (done)))))

(deftest logs-hook-compatibility
  (async done
         (go
           (let [container (js/document.createElement "div")
                 ref-called (atom false)
                 effect-called (atom false)
                 cleanup-called (atom false)]
             (js/document.body.appendChild container)
             (with-redefs [react/useRef (fn []
                                          (reset! ref-called true)
                                          #js {:current nil})
                           react/useEffect (fn [f deps]
                                             (reset! effect-called true)
                                             (let [cleanup (f)]
                                               (when (fn? cleanup)
                                                 (reset! cleanup-called true)))
                                             #js [])]
               (let [root (act-mount container [logs/component])]
                 (<! (timeout 10))
                 (act-flush #(r/flush))
                 (act-flush #(rf/dispatch-sync [::e/toggle-logs]))
                 (<! (timeout 10))
                 (act-flush #(r/flush))
                 (is @ref-called "useRef hook was called successfully")
                 (is @effect-called "useEffect hook was called successfully")
                 (is @cleanup-called "useEffect cleanup function was called")
                 (act-flush #(.unmount root))))
             (js/document.body.removeChild container)
             (done)))))

(deftest logs-resize-integration
  (async done
         (go
           (let [observer-callback (atom nil)
                 container (js/document.createElement "div")]
             (with-redefs [js/ResizeObserver (fn [cb]
                                               (reset! observer-callback cb)
                                               #js {:observe (fn [_])
                                                    :disconnect (fn [])})]
               (js/document.body.appendChild container)
               (let [root (act-mount container [logs/component])]
                 (<! (timeout 10))
                 (act-flush #(r/flush))
                 (act-flush #(rf/dispatch-sync [::e/toggle-logs]))
                 (<! (timeout 10))
                 (act-flush #(r/flush))
                 (let [content (.querySelector container ".logs-content")]
                   (is (some? content) "Logs content found")
                   (act-flush #(do (set! (.-style.height content) "150px")
                                   (when @observer-callback
                                     (@observer-callback [#js {:target content :contentRect #js {:height 150}}]))))
                   (<! (timeout 10))
                   (act-flush #(r/flush))
                   (binding [ratom/*ratom-context* (ratom/make-reaction (fn []))]
                     (is (= 150 @(rf/subscribe [:logs-height])) "Height updated asynchronously on resize")))
                 (act-flush #(rf/dispatch-sync [::e/toggle-logs]))
                 (<! (timeout 10))
                 (act-flush #(r/flush))
                 (is (nil? (.querySelector container ".logs-content")) "Content hidden after toggle")
                 (act-flush #(.unmount root))))
             (js/document.body.removeChild container)
             (done)))))
