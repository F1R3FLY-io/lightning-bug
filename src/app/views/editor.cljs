(ns app.views.editor
  (:require
   [clojure.core.async :refer [go <! timeout]]
   [lib.core :refer [Editor]]
   [re-frame.core :as rf]
   [app.events :as e]
   [app.shared :refer [editor-ref-atom]]))

(defn component
  "Renders the editor component with content, language, and LSP handling."
  []
  (let [languages @(rf/subscribe [:languages])
        last-dispatch-time (atom {:content-change 0 :selection-change 0})
        dispatch-debounced (fn [evt-type data dispatch-fn debounce-ms]
                             (let [now (js/Date.now)
                                   last (@last-dispatch-time evt-type)]
                               (when (> (- now last) debounce-ms)
                                 (dispatch-fn data)
                                 (swap! last-dispatch-time assoc evt-type now))))
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
                                          (case type
                                            "selection-change" (dispatch-debounced :selection-change (:data evt)
                                                                                   (fn [{:keys [cursor selection]}]
                                                                                     (rf/dispatch [::e/update-cursor cursor])
                                                                                     (rf/dispatch [::e/update-selection selection])) 50)
                                            "content-change" (dispatch-debounced :content-change (:data evt)
                                                                                 (fn [{:keys [content uri]}]
                                                                                   (rf/dispatch [::e/editor-update-content content uri])) 300)
                                            "highlight-change" (rf/dispatch [::e/update-highlights (:data evt)])
                                            "ready" (do
                                                      (when (= "ready" (:type evt))
                                                        (when (nil? (.getFileUri er))
                                                          (go
                                                            (<! (timeout 100)) ;; Brief delay to ensure CM is fully ready.
                                                            (.openDocument er "inmemory://untitled.rho" "" "rholang"))))
                                                      (rf/dispatch [::e/editor-ready]))
                                            nil))))))
                           (do
                             (when @subscription (.unsubscribe @subscription))
                             (reset! subscription nil)
                             (reset! editor-ref-atom nil))))]
    [:> Editor {:ref set-editor-ref
                :languages languages}]))
