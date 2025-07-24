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
   [app.db :as db :refer [default-db ds-conn]]
   [app.events :as e]
   [app.subs]
   [app.views.editor :as editor]
   [app.views.main :as main]))

(deftest root-component-renders
  (async done
    (reset! app-db default-db)
    (set! db/ds-conn (d/create-conn db/schema))
    (rf/dispatch-sync [::e/initialize])
    (let [container (js/document.createElement "div")]
      (js/document.body.appendChild container)
      (let [root (rdclient/createRoot container)]
        (react/act #(.render root (r/as-element [main/root-component])))
        (r/flush))
      (is (some? (.querySelector container ".vh-100")) "Root container rendered")
      (is (some? (.querySelector container ".code-editor")) "Editor component rendered")
      (is (some? (.querySelector container ".status-bar")) "Status bar rendered")
      (is (some? (.querySelector container ".logs-panel")) "Logs panel rendered")
      (js/document.body.removeChild container)
      (done))))

(deftest error-boundary-catches-error
  (async done
    (reset! app-db default-db)
    (set! db/ds-conn (d/create-conn db/schema))
    (rf/dispatch-sync [::e/initialize])
    (let [container (js/document.createElement "div")
          captured-logs (atom nil)]
      (log/with-config (assoc log/*config* :appenders {:capture {:enabled? true
                                                                 :fn (fn [data] (swap! captured-logs conj data))}})
        (with-redefs [editor/component (fn [] (throw (js/Error. "Test error")))]
          (js/document.body.appendChild container)
          (let [root (rdclient/createRoot container)]
            (react/act #(.render root (r/as-element [main/root-component])))
            (r/flush))
          (is (some? (.querySelector container ".text-danger")) "Error boundary fallback UI rendered")
          (is (some #(str/includes? (str/join " " (:vargs %)) "Error boundary caught") @captured-logs) "Error boundary logged the error")
          (let [retry-button (.querySelector container ".btn-primary")]
            (react/act #(.dispatchEvent retry-button (js/MouseEvent. "click")))
            (r/flush)
            (is (nil? (.querySelector container ".text-danger")) "Retry clears error state")
            (is (some? (.querySelector container ".code-editor")) "Editor re-rendered after retry")
            (js/document.body.removeChild container)
            (done)))))))

(deftest editor-unmount-remount
  (async done
    (reset! app-db default-db)
    (set! db/ds-conn (d/create-conn db/schema))
    (rf/dispatch-sync [::e/initialize])
    (let [container (js/document.createElement "div")]
      (js/document.body.appendChild container)
      (let [root (rdclient/createRoot container)]
        (react/act #(.render root (r/as-element [main/root-component])))
        (r/flush)
        (is (some? (.querySelector container ".code-editor")) "Editor rendered initially")
        (.unmount root))
      (r/flush)
      (is (nil? (.querySelector container ".code-editor")) "Editor unmounted")
      (let [root (rdclient/createRoot container)]
        (react/act #(.render root (r/as-element [main/root-component])))
        (r/flush))
      (is (some? (.querySelector container ".code-editor")) "Editor re-mounted successfully")
      (js/document.body.removeChild container)
      (done))))
