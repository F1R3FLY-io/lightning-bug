(ns test.app.views.editor-test
  (:require
   ["react" :as react]
   ["react-dom/client" :as rdclient]
   [reagent.core :as r]
   [app.events :as e]
   [app.subs]
   [clojure.test :refer [deftest is async use-fixtures]]
   [clojure.core.async :refer [<! go timeout]]
   [datascript.core :as d]
   [lib.core :refer [Editor]]
   [re-frame.core :as rf]
   [re-frame.db :refer [app-db]]
   [re-posh.core :as rp]
   [app.db :refer [default-db ds-conn]]
   [app.views.editor :as editor]
   [reagent.ratom :as ratom]))

(set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) true)

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

(deftest editor-renders
  (async done
    (go
      (let [container (js/document.createElement "div")]
        (js/document.body.appendChild container)
        (let [root (rdclient/createRoot container)]
          (react/act #(.render root (r/as-element [:f> editor/component])))
          (r/flush)
          (<! (timeout 50)))
        (is (some? (.querySelector container ".code-editor")))
        (js/document.body.removeChild container)
        (done)))))

(deftest editor-no-file
  (async done
    (go
      (swap! app-db assoc-in [:workspace :active-file] nil)
      (r/flush)
      (<! (timeout 50))
      (let [container (js/document.createElement "div")]
        (js/document.body.appendChild container)
        (let [root (rdclient/createRoot container)]
          (react/act #(.render root (r/as-element [:f> editor/component])))
          (r/flush)
          (<! (timeout 50)))
        (is (some? (.querySelector container "div")) "Renders message for no active file")
        (js/document.body.removeChild container)
        (done)))))

(deftest editor-cursor-update
  (async done
    (go
      (let [container (js/document.createElement "div")
            ref (react/createRef)]
        (js/document.body.appendChild container)
        (with-redefs [Editor (react/forwardRef
                              (fn [_ forwarded-ref]
                                (react/useImperativeHandle
                                 forwarded-ref
                                 (fn []
                                   #js {:setCursor (fn [pos-js]
                                                     (let [pos (js->clj pos-js :keywordize-keys true)]
                                                       (rf/dispatch [::e/update-cursor pos])))
                                        :isReady (fn [] true)}))
                                (react/createElement "div" #js {:className "code-editor"})))]
          (let [root (rdclient/createRoot container)]
            (react/act #(.render root (r/as-element [:> Editor {:content "test"
                                                                :language "text"
                                                                :languages {"text" {:extensions [".txt"]}}}
                                                     {:ref ref}])))
          (r/flush)
          (<! (timeout 50)))
        (react/act #(rf/dispatch-sync [::e/set-cursor-pos {:line 2 :column 3}]))
        (r/flush)
        (<! (timeout 50))
        (binding [ratom/*ratom-context* (ratom/make-reaction (fn []))]
          (is (= {:line 2 :column 3} @(rf/subscribe [:editor/cursor])) "Cursor updated")
          (is (nil? @(rf/subscribe [:editor-cursor-pos])) "Cursor pos cleared after update"))
        (js/document.body.removeChild container)
        (done))))))

(deftest editor-highlight-range
  (async done
    (go
      (let [container (js/document.createElement "div")
            ref (react/createRef)
            highlight-called (atom false)
            clear-called (atom false)]
        (js/document.body.appendChild container)
        (with-redefs [Editor (react/forwardRef
                              (fn [_ forwarded-ref]
                                (react/useImperativeHandle
                                forwarded-ref
                                 (fn []
                                   #js {:highlightRange (fn [_ _] (reset! highlight-called true))
                                        :clearHighlight (fn [] (reset! clear-called true))
                                        :isReady (fn [] true)}))
                                (react/createElement "div" #js {:className "code-editor"})))]
          (let [root (rdclient/createRoot container)]
            (react/act #(.render root (r/as-element [:> Editor {:content "test"
                                                                :language "text"
                                                                :languages {"text" {:extensions [".txt"]}}}
                                                     {:ref ref}])))
          (r/flush)
          (<! (timeout 50)))
        (react/act #(rf/dispatch-sync [::e/set-highlight-range {:from {:line 1 :column 1} :to {:line 1 :column 6}}]))
        (r/flush)
        (<! (timeout 50))
        (is @highlight-called "highlightRange called")
        (react/act #(rf/dispatch-sync [::e/set-highlight-range nil]))
        (r/flush)
        (<! (timeout 50))
        (is @clear-called "clearHighlight called")
        (js/document.body.removeChild container)
        (done))))))
