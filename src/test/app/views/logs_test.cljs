(ns test.app.views.logs-test
  (:require
   ["react" :as react]
   [app.events :as e]
   [re-com.core :as rc]
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

(deftest logs-renders
  (async done
    (go
      (let [container (js/document.createElement "div")]
        (js/document.body.appendChild container)
        (let [root (rdclient/createRoot container)]
          (react/act #(.render root (r/as-element [logs/component])))
          (r/flush)
          (<! (timeout 50)))
        (is (some? (.querySelector container "button")) "Heading button rendered")
        (is (some? (.querySelector container ".fa-chevron-up")) "Up arrow when collapsed")
        (is (some? (.querySelector container ".text-muted")) "No diagnostics message shown")
        (js/document.body.removeChild container)
        (done)))))

(deftest logs-toggle
  (async done
    (go
      (rf/dispatch-sync [::e/lsp-diagnostics-update [{:message "Test diagnostic"
                                                       :range {:start {:line 0 :character 0} :end {:line 0 :character 5}}
                                                       :severity 1}]])
      (r/flush)
      (<! (timeout 50))
      (let [container (js/document.createElement "div")]
        (js/document.body.appendChild container)
        (let [root (rdclient/createRoot container)]
          (react/act #(.render root (r/as-element [logs/component])))
          (r/flush)
          (<! (timeout 50)))
        (is (nil? (.querySelector container ".logs-content")) "Content hidden initially")
        (react/act #(rf/dispatch-sync [::e/toggle-logs]))
        (r/flush)
        (<! (timeout 50))
        (is (some? (.querySelector container ".logs-content")) "Content visible after toggle")
        (is (some? (.querySelector container ".fa-chevron-down")) "Down arrow when expanded")
        (js/document.body.removeChild container)
        (done)))))

(deftest logs-clear-diagnostics
  (async done
    (go
      (rf/dispatch-sync [::e/lsp-diagnostics-update [{:message "Test diagnostic 1"
                                                       :range {:start {:line 0 :character 0} :end {:line 0 :character 5}}
                                                       :severity 1}]])
      (r/flush)
      (<! (timeout 50))
      (let [container (js/document.createElement "div")]
        (js/document.body.appendChild container)
        (let [root (rdclient/createRoot container)]
          (react/act #(.render root (r/as-element [logs/component])))
          (r/flush)
          (<! (timeout 50)))
        (react/act #(rf/dispatch-sync [::e/toggle-logs]))
        (r/flush)
        (<! (timeout 50))
        (is (= 1 (.-length (.querySelectorAll container ".text-danger"))) "One diagnostic shown")
        (rf/dispatch-sync [::e/lsp-diagnostics-update []])
        (r/flush)
        (<! (timeout 50))
        (is (some? (.querySelector container ".text-muted")) "No diagnostics message shown after clear")
        (js/document.body.removeChild container)
        (done)))))

(deftest logs-navigate-on-click
  (async done
         (go
           (rf/dispatch-sync [::e/lsp-diagnostics-update [{:message "Test diagnostic"
                                                           :range {:start {:line 0 :character 0} :end {:line 0 :character 5}}
                                                           :severity 1}]])
           (r/flush)
           (<! (timeout 50))
           (let [container (js/document.createElement "div")]
             (js/document.body.appendChild container)
             (let [root (rdclient/createRoot container)]
               (react/act #(.render root (r/as-element [logs/component])))
               (r/flush)
               (<! (timeout 50)))
             (react/act #(rf/dispatch-sync [::e/toggle-logs]))
             (r/flush)
             (<! (timeout 50))
             (let [label (.querySelector container ".text-danger")]
               (is (some? label) "Diagnostic label found")
               (react/act #(.dispatchEvent label (js/MouseEvent. "click")))
               (r/flush)
               (<! (timeout 50))
               (binding [ratom/*ratom-context* (ratom/make-reaction (fn []))]
                 (is (= {:line 1 :column 1} @(rf/subscribe [:editor-cursor-pos])) "Cursor set to diagnostic position")))
             (js/document.body.removeChild container)
             (done)))))

(deftest logs-highlight-on-hover
  (async done
         (go
           (rf/dispatch-sync [::e/lsp-diagnostics-update [{:message "Test diagnostic"
                                                           :range {:start {:line 0 :character 0} :end {:line 0 :character 5}}
                                                           :severity 1}]])
           (r/flush)
           (<! (timeout 50))
           (let [container (js/document.createElement "div")]
             (js/document.body.appendChild container)
             (let [root (rdclient/createRoot container)]
               (react/act #(.render root (r/as-element [logs/component])))
               (r/flush)
               (<! (timeout 50)))
             (react/act #(rf/dispatch-sync [::e/toggle-logs]))
             (r/flush)
             (<! (timeout 50))
             (let [label (.querySelector container ".text-danger")]
               (is (some? label) "Diagnostic label found")
               (react/act #(.dispatchEvent label (js/MouseEvent. "mouseenter")))
               (r/flush)
               (<! (timeout 50))
               (binding [ratom/*ratom-context* (ratom/make-reaction (fn []))]
                 (is (= {:from {:line 1 :column 1} :to {:line 1 :column 6}} @(rf/subscribe [:highlight-range])) "Range highlighted on hover")))
             (js/document.body.removeChild container)
             (done)))))

(deftest logs-clear-highlight-on-leave
  (async done
         (go
           (rf/dispatch-sync [::e/lsp-diagnostics-update [{:message "Test diagnostic"
                                                           :range {:start {:line 0 :character 0} :end {:line 0 :character 5}}
                                                           :severity 1}]])
           (r/flush)
           (<! (timeout 50))
           (let [container (js/document.createElement "div")]
             (js/document.body.appendChild container)
             (let [root (rdclient/createRoot container)]
               (react/act #(.render root (r/as-element [logs/component])))
               (r/flush)
               (<! (timeout 50)))
             (react/act #(rf/dispatch-sync [::e/toggle-logs]))
             (r/flush)
             (<! (timeout 50))
             (let [label (.querySelector container ".text-danger")]
               (is (some? label) "Diagnostic label found")
               (react/act #(.dispatchEvent label (js/MouseEvent. "mouseenter")))
               (r/flush)
               (<! (timeout 50))
               (react/act #(.dispatchEvent label (js/MouseEvent. "mouseleave")))
               (r/flush)
               (<! (timeout 50))
               (binding [ratom/*ratom-context* (ratom/make-reaction (fn []))]
                 (is (nil? @(rf/subscribe [:highlight-range])) "Highlight cleared on mouse leave")))
             (js/document.body.removeChild container)
             (done)))))

(deftest logs-resize-height
  (async done
         (go
           (let [container (js/document.createElement "div")]
             (js/document.body.appendChild container)
             (let [root (rdclient/createRoot container)]
               (react/act #(.render root (r/as-element [logs/component])))
               (r/flush)
               (<! (timeout 50)))
             (react/act #(rf/dispatch-sync [::e/toggle-logs]))
             (r/flush)
             (<! (timeout 50))
             (let [content (.querySelector container ".logs-content")]
               (is (some? content) "Logs content found")
               (set! (.-height (.-style content)) "300px")
               (.dispatchEvent content (js/Event. "resize"))
               (<! (timeout 200))
               (binding [ratom/*ratom-context* (ratom/make-reaction (fn []))]
                 (is (= 300 @(rf/subscribe [:logs-height])) "Height updated on resize")))
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
          (let [root (rdclient/createRoot container)]
            (react/act #(.render root (r/as-element [logs/component])))
            (r/flush)
            (<! (timeout 50)))
          (react/act #(rf/dispatch-sync [::e/toggle-logs]))
          (r/flush)
          (<! (timeout 50))
          (is @observer-called "ResizeObserver was instantiated"))
        (js/document.body.removeChild container)
        (done)))))

(deftest logs-content-component-renders
  (async done
    (go
      (rf/dispatch-sync [::e/lsp-diagnostics-update [{:message "Test diagnostic"
                                                       :range {:start {:line 0 :character 0} :end {:line 0 :character 5}}
                                                       :severity 1}]])
      (r/flush)
      (<! (timeout 50))
      (let [container (js/document.createElement "div")]
        (js/document.body.appendChild container)
        (let [root (rdclient/createRoot container)]
          (react/act #(.render root (r/as-element [:f> logs/logs-content
                                                   {:visible? true
                                                    :diagnostics [{:message "Test diagnostic"
                                                                   :range {:start {:line 0 :character 0} :end {:line 0 :character 5}}
                                                                   :severity 1}]
                                                    :height 200
                                                    :on-resize (fn [_])}])))
          (r/flush)
          (<! (timeout 50)))
        (is (some? (.querySelector container ".logs-content")) "Logs content component rendered")
        (is (some? (.querySelector container ".text-danger")) "Diagnostic message rendered")
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
          (let [root (rdclient/createRoot container)]
            (react/act #(.render root (r/as-element [logs/component])))
            (r/flush)
            (<! (timeout 50)))
          (react/act #(rf/dispatch-sync [::e/toggle-logs]))
          (r/flush)
          (<! (timeout 50))
          (is @ref-called "useRef hook was called successfully")
          (is @effect-called "useEffect hook was called successfully")
          (is @cleanup-called "useEffect cleanup function was called"))
        (js/document.body.removeChild container)
        (done)))))
