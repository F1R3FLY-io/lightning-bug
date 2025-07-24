(ns app.views.editor
  (:require
   ["react" :as react]
   [app.events :as e]
   [app.shared :refer [editor-ref-atom]]
   [app.utils :as u]
   [lib.core :refer [Editor]]
   [re-frame.core :as rf]
   [re-posh.core :as rp]
   [taoensso.timbre :as log]))

(def debounced-update-content (u/debounce #(rf/dispatch [::e/editor-update-content %]) 300))

(defn component
  "Renders the editor component with content, language, and LSP handling."
  []
  (let [content @(rf/subscribe [:active-content])
        lang @(rf/subscribe [:active-lang])
        languages @(rf/subscribe [:languages])
        active @(rf/subscribe [:workspace/active-file])
        name @(rf/subscribe [:active-name])
        uri (when name (str "inmemory://" name))
        editor-ref (react/useRef)
        cursor-pos @(rf/subscribe [:editor-cursor-pos])
        highlight-range @(rf/subscribe [:highlight-range])]
    ;; Store editor ref globally for external access
    (react/useEffect
     (fn []
       (log/debug "Storing editor ref in atom")
       (reset! editor-ref-atom editor-ref)
       (fn [] (reset! editor-ref-atom nil)))
     #js [editor-ref])
    ;; Update cursor position when cursor-pos changes
    (react/useEffect
     (fn []
       (when-let [er (.-current editor-ref)]
         (when (.isReady er)
           (log/trace "Setting cursor to" cursor-pos)
           (.setCursor er (clj->js cursor-pos))
           (rf/dispatch [::e/clear-cursor-pos])))
       js/undefined)
     #js [cursor-pos editor-ref])
    ;; Highlight or clear range when highlight-range changes
    (react/useEffect
     (fn []
       (when-let [er (.-current editor-ref)]
         (when (.isReady er)
           (log/trace "Processing highlight range:" highlight-range)
           (if highlight-range
             (let [{:keys [from to]} highlight-range]
               (.highlightRange er (clj->js from) (clj->js to)))
             (.clearHighlight er))))
       js/undefined)
     #js [highlight-range editor-ref])
    ;; Subscribe to editor events
    (react/useEffect
     (fn []
       (when-let [er (.-current editor-ref)]
         (log/debug "Subscribing to editor events")
         (let [sub (.subscribe
                    (.getEvents er)
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
           (fn [] (log/debug "Unsubscribing from editor events") (.unsubscribe sub))))
       js/undefined)
     #js [editor-ref])
    ;; Handle document open/close when active file changes
    (react/useEffect
     (fn []
       (when-let [er (.-current editor-ref)]
         (log/debug "Handling document open/close for active file:" active)
         (.closeDocument er)
         (when (and active name)
           (.openDocument er uri content lang)))
       js/undefined)
     #js [active uri content lang editor-ref])
    (log/debug "Rendering editor for lang:" lang)
    (if (not active)
      [:div.d-flex.justify-content-center.align-items-center.h-100 "No file open"]
      [:> Editor {:ref editor-ref
                  :content content
                  :language lang
                  :languages languages
                  :onContentChange debounced-update-content}])))
