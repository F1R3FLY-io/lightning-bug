(ns app.views.editor
  "Editor view component with centralized event handling.
   EXP-008: Removed dual debouncing - core handles all debouncing."
  (:require
   [clojure.core.async :refer [go <! timeout]]
   [lib.core :refer [Editor]]
   [re-frame.core :as rf]
   [app.events :as e]
   [app.shared :refer [editor-ref-atom]]))

(defn component
  "Renders the editor component with content, language, and LSP handling.
   EXP-008: Dispatches events immediately - debouncing handled in lib.core."
  []
  (let [languages @(rf/subscribe [:languages])
        subscription (atom nil)
        set-editor-ref (fn [^js er]
                         (if er
                           (do
                             (reset! editor-ref-atom er)
                             (reset! subscription
                                     (.subscribe
                                      (.getEvents er)
                                      (fn [evt-js]
                                        (let [evt (js->clj evt-js :keywordize-keys true)
                                              type (:type evt)]
                                          (rf/dispatch [::e/handle-editor-event evt])
                                          ;; EXP-008: Events already debounced in core, dispatch immediately
                                          (case type
                                            "selection-change"
                                            (let [{:keys [cursor selection]} (:data evt)]
                                              (rf/dispatch [::e/update-cursor cursor])
                                              (rf/dispatch [::e/update-selection selection]))

                                            "content-change"
                                            nil  ; Content updates handled by DataScript in lib.core (EXP-009)

                                            "highlight-change"
                                            (rf/dispatch [::e/update-highlights (:data evt)])

                                            "ready"
                                            (do
                                              (when (nil? (.getFileUri er))
                                                (go
                                                  (<! (timeout 100)) ;; Brief delay to ensure CM is fully ready.
                                                  (.openDocument er "inmemory://untitled.rho" "" "rholang")))
                                              (rf/dispatch [::e/editor-ready]))

                                            nil))))))
                           (do
                             (when @subscription (.unsubscribe @subscription))
                             (reset! subscription nil)
                             (reset! editor-ref-atom nil))))]
    [:> Editor {:ref set-editor-ref
                :languages languages}]))
