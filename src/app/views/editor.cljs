(ns app.views.editor
  (:require
   ["react" :as react]
   [re-frame.core :as rf]
   [re-com.core :as rc]
   [re-posh.core :as rp]
   [taoensso.timbre :as log]
   [app.events :as e]
   [app.utils :as u]
   [lib.core :refer [Editor]]))

;; Main editor view component
(defn component
  []
  (let [content @(rf/subscribe [:active-content])
        lang @(rf/subscribe [:active-lang])
        languages @(rf/subscribe [:languages])
        active @(rf/subscribe [:active-file])
        ext (u/get-extension {:languages languages} lang)
        uri (str "inmemory://" active ext)
        editor-ref (react/useRef)]
    (log/debug "lang" lang)
    (if (not active)
      [:div.d-flex.justify-content-center.align-items-center.h-100 "No file open"]
      (do
        (react/useEffect
         (fn []
           (let [sub (.subscribe
                      (.getEvents (.-current editor-ref))
                      (fn [evt-js]
                        (let [evt (js->clj evt-js :keywordize-keys true)
                              type (:type evt)
                              data (:data evt)]
                          (case type
                            "diagnostics" (rp/dispatch [::e/lsp-diagnostics-update data])
                            "symbols-update" (rp/dispatch [::e/lsp-symbols-update data])
                            "log" (rf/dispatch [::e/log-append data])
                            "connect" (rf/dispatch [::e/lsp-set-connection true])
                            "disconnect" (rf/dispatch [::e/lsp-set-connection false])
                            "selection-change" (rf/dispatch [::e/update-cursor (:cursor data)])
                            nil))))]
             (fn [] (.unsubscribe sub))))
         #js [])
        (react/useEffect
         (fn []
           (when-let [er (.-current editor-ref)]
             (.closeDocument er)
             (when active
               (.openDocument er uri content lang)))
           js/undefined)
         #js [active])
        [:> Editor {:ref editor-ref
                    :content content
                    :language lang
                    :languages languages
                    :onContentChange #(rf/dispatch [::editor-update-content %])}]))))
