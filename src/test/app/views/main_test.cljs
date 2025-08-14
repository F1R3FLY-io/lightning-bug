(ns test.app.views.main-test
  (:require
   ["react-dom/client" :as rdclient]
   [reagent.core :as r]
   [clojure.string :as str]
   [clojure.test :refer [deftest is async]]
   [datascript.core :as d]
   [re-frame.core :as rf]
   [re-frame.db :refer [app-db]]
   ["react" :as react]
   [taoensso.timbre :as log]
   [lib.db :as lib-db]
   [app.db :as db :refer [default-db]]
   [app.events :as e]
   [app.subs]
   [app.views.editor :as editor]
   [app.views.main :as main]))

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

(deftest root-component-renders
  (async done
         (reset! app-db default-db)
         (set! db/ds-conn (d/create-conn lib-db/schema))
         (rf/dispatch-sync [::e/initialize])
         (let [container (js/document.createElement "div")]
           (js/document.body.appendChild container)
           (let [root (act-mount container [main/root-component])]
             (is (some? (.querySelector container ".vh-100")) "Root container rendered")
             (is (some? (.querySelector container ".code-editor")) "Editor component rendered")
             (is (some? (.querySelector container ".status-bar")) "Status bar rendered")
             (is (some? (.querySelector container ".logs-panel")) "Logs panel rendered")
             (act-flush #(.unmount root))
             (js/document.body.removeChild container)
             (done)))))

(deftest error-boundary-catches-error
  (async done
         (reset! app-db default-db)
         (set! db/ds-conn (d/create-conn lib-db/schema))
         (rf/dispatch-sync [::e/initialize])
         (let [container (js/document.createElement "div")
               captured-logs (atom nil)
               throw? (atom true)
               original-onerror js/window.onerror]
           (set! js/window.onerror (fn [msg url line col error]
                                     (if (str/includes? msg "Test error")
                                       (do
                                         (log/debug "Ignored intentional test error")
                                         true) ; Prevent default propagation/logging as uncaught.
                                       (when original-onerror (original-onerror msg url line col error)))))
           (log/with-config (assoc log/*config* :appenders {:capture {:enabled? true
                                                                      :fn (fn [data] (swap! captured-logs conj data))}})
             (with-redefs [editor/component (fn [] (when @throw? (throw (js/Error. "Test error"))) [:div.code-editor])]
               (js/document.body.appendChild container)
               (let [root (act-mount container [main/root-component])]
                 (is (some? (.querySelector container ".text-danger")) "Error boundary fallback UI rendered")
                 (is (some #(str/includes? (str/join " " (:vargs %)) "Error boundary caught") @captured-logs) "Error boundary logged the error")
                 (let [retry-button (.querySelector container ".btn-primary")]
                   (reset! throw? false) ;; Disable throwing before retry to allow successful re-render.
                   (act-flush #(.dispatchEvent retry-button (js/MouseEvent. "click")))
                   (is (nil? (.querySelector container ".text-danger")) "Retry clears error state")
                   (is (some? (.querySelector container ".code-editor")) "Editor re-rendered after retry")
                   (act-flush #(.unmount root))
                   (js/document.body.removeChild container)
                   (set! js/window.onerror original-onerror)
                   (done))))))))

(deftest editor-unmount-remount
  (async done
         (reset! app-db default-db)
         (set! db/ds-conn (d/create-conn lib-db/schema))
         (rf/dispatch-sync [::e/initialize])
         (let [container (js/document.createElement "div")]
           (js/document.body.appendChild container)
           (let [root (act-mount container [main/root-component])]
             (is (some? (.querySelector container ".code-editor")) "Editor rendered initially")
             (act-flush #(.unmount root)))
           (act-flush #(r/flush))
           (is (nil? (.querySelector container ".code-editor")) "Editor unmounted")
           (let [root (act-mount container [main/root-component])]
             (is (some? (.querySelector container ".code-editor")) "Editor re-mounted successfully")
             (act-flush #(.unmount root))
             (js/document.body.removeChild container)
             (done)))))
