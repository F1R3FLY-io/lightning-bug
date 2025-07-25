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
   [re-frame.db :refer [app-db]]
   [re-posh.core :as rp]
   [app.db :refer [default-db ds-conn]]
   [app.views.editor :as editor]
   [re-frame.core :as rf]
   [lib.core :refer [Editor]]))

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

(deftest editor-renders
  (async done
         (go
           (let [container (js/document.createElement "div")]
             (js/document.body.appendChild container)
             (let [root (act-mount container [:f> editor/component])]
               (<! (timeout 10))
               (is (some? (.querySelector container ".code-editor")) "Editor container rendered")
               (act-flush #(.unmount root)))
             (js/document.body.removeChild container)
             (done)))))

(deftest editor-no-file
  (async done
         (go
           (swap! app-db assoc-in [:workspace :active-file] nil)
           (act-flush #(r/flush))
           (<! (timeout 10))
           (let [container (js/document.createElement "div")]
             (js/document.body.appendChild container)
             (let [root (act-mount container [:f> editor/component])]
               (<! (timeout 10))
               (is (some? (.querySelector container "div")) "Renders message for no active file")
               (act-flush #(.unmount root)))
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
                                                            (rf/dispatch [::e/update-cursor pos])
                                                            (rf/dispatch [::e/clear-cursor-pos])))
                                             :isReady (fn [] true)}))
                                     (react/createElement "div" #js {:className "code-editor"})))]
               (let [root (act-mount container [:> Editor {:content "test"
                                                           :language "text"
                                                           :languages {"text" {:extensions [".txt"]}}}
                                                {:ref ref}])
                     _ (<! (timeout 10))]
                 (rf/dispatch [::e/set-cursor-pos {:line 2 :column 3}])
                 (act-flush #(r/flush))
                 (<! (timeout 200))
                 (act-flush #(r/flush))
                 (is (= {:line 2 :column 3} @(rf/subscribe [:editor/cursor])) "Cursor updated")
                 (is (nil? @(rf/subscribe [:editor-cursor-pos])) "Cursor pos cleared after update")
                 (act-flush #(.unmount root))
                 (js/document.body.removeChild container)
                 (done)))))))

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
               (let [root (act-mount container [:> Editor {:content "test"
                                                           :language "text"
                                                           :languages {"text" {:extensions [".txt"]}}}
                                                {:ref ref}])
                     _ (<! (timeout 10))]
                 (rf/dispatch [::e/set-highlight-range {:from {:line 1 :column 1} :to {:line 1 :column 6}}])
                 (act-flush #(r/flush))
                 (<! (timeout 200))
                 (act-flush #(r/flush))
                 (is @highlight-called "highlightRange called")
                 (rf/dispatch [::e/set-highlight-range nil])
                 (act-flush #(r/flush))
                 (<! (timeout 200))
                 (act-flush #(r/flush))
                 (is @clear-called "clearHighlight called")
                 (act-flush #(.unmount root))
                 (js/document.body.removeChild container)
                 (done)))))))
