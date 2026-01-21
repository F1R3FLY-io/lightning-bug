(ns app.fx
  "Re-Frame effects for side effects in event handlers.

   Effects handle write operations to the outside world.
   They enable testability by centralizing all side effects."
  (:require [re-frame.core :as rf]
            [lib.db :as lib-db]
            [app.shared :refer [editor-ref-atom]]
            [taoensso.timbre :as log]))

;; =============================================================================
;; Document Effects
;; =============================================================================

(rf/reg-fx
 :document/create
 (fn [doc]
   (lib-db/create-documents! [(merge {:version 0 :dirty false :opened false} doc)])))

(rf/reg-fx
 :document/update-text
 (fn [{:keys [uri text]}]
   (lib-db/update-document-text-by-uri! uri text)))

(rf/reg-fx
 :document/set-active
 (fn [uri]
   (lib-db/update-active-uri! uri)))

(rf/reg-fx
 :document/increment-version
 (fn [uri]
   (lib-db/inc-document-version-by-uri! uri)))

(rf/reg-fx
 :document/mark-opened
 (fn [uri]
   (lib-db/document-opened-by-uri! uri)))

(rf/reg-fx
 :document/mark-closed
 (fn [uri]
   (lib-db/document-closed-by-uri! uri)))

(rf/reg-fx
 :document/delete
 (fn [uri]
   (when-let [id (lib-db/document-id-by-uri uri)]
     (lib-db/delete-document-by-id! id))))

(rf/reg-fx
 :document/rename
 (fn [{:keys [old-uri new-uri]}]
   (when-let [id (lib-db/document-id-by-uri old-uri)]
     (lib-db/update-document-uri-by-id! id new-uri))))

;; =============================================================================
;; Diagnostics Effects
;; =============================================================================

(rf/reg-fx
 :diagnostics/replace
 (fn [{:keys [uri version diagnostics]}]
   (let [flat-diags (if (seq diagnostics)
                      (lib-db/flatten-diags diagnostics uri version)
                      [])]
     (lib-db/replace-diagnostics-by-uri! uri version flat-diags))))

;; =============================================================================
;; Symbols Effects
;; =============================================================================

(rf/reg-fx
 :symbols/replace
 (fn [{:keys [uri symbols]}]
   (let [flat-symbols (if (seq symbols)
                        (lib-db/flatten-symbols symbols nil uri)
                        [])]
     (lib-db/replace-symbols! uri flat-symbols))))

;; =============================================================================
;; Log Effects
;; =============================================================================

(rf/reg-fx
 :log/add
 (fn [log]
   (lib-db/create-logs! [log])))

;; =============================================================================
;; Editor Effects
;; =============================================================================

(rf/reg-fx
 :editor/set-cursor
 (fn [pos]
   (when-let [^js editor (some-> @editor-ref-atom .-current)]
     (when (.isReady editor)
       (.setCursor editor (clj->js pos))))))

(rf/reg-fx
 :editor/set-selection
 (fn [{:keys [from to]}]
   (when-let [^js editor (some-> @editor-ref-atom .-current)]
     (when (.isReady editor)
       (.setSelection editor (clj->js from) (clj->js to))))))

(rf/reg-fx
 :editor/highlight-range
 (fn [{:keys [from to]}]
   (when-let [^js editor (some-> @editor-ref-atom .-current)]
     (when (.isReady editor)
       (.highlightRange editor (clj->js from) (clj->js to))))))

(rf/reg-fx
 :editor/clear-highlight
 (fn [_]
   (when-let [^js editor (some-> @editor-ref-atom .-current)]
     (when (.isReady editor)
       (.clearHighlight editor)))))

(rf/reg-fx
 :editor/set-text
 (fn [{:keys [text uri]}]
   (when-let [^js editor (some-> @editor-ref-atom .-current)]
     (when (.isReady editor)
       (.setText editor text uri)))))

(rf/reg-fx
 :editor/focus
 (fn [_]
   (when-let [^js editor (some-> @editor-ref-atom .-current)]
     (when (.isReady editor)
       (.focus editor)))))

(rf/reg-fx
 :editor/open-document
 (fn [{:keys [uri text language]}]
   (when-let [^js editor (some-> @editor-ref-atom .-current)]
     (.openDocument editor uri text language))))

(rf/reg-fx
 :editor/close-document
 (fn [uri]
   (when-let [^js editor (some-> @editor-ref-atom .-current)]
     (.closeDocument editor uri))))

(rf/reg-fx
 :editor/rename-document
 (fn [{:keys [new-uri old-uri]}]
   (when-let [^js editor (some-> @editor-ref-atom .-current)]
     (.renameDocument editor new-uri old-uri))))

;; =============================================================================
;; Console/Logging Effects
;; =============================================================================

(rf/reg-fx
 :console/log
 (fn [message]
   (log/info message)))

(rf/reg-fx
 :console/warn
 (fn [message]
   (log/warn message)))

(rf/reg-fx
 :console/error
 (fn [message]
   (log/error message)))

;; =============================================================================
;; Timer Effects
;; =============================================================================

(rf/reg-fx
 :timer/debounced-dispatch
 (fn [{:keys [id ms dispatch]}]
   ;; This would be managed by debounce coordinator
   ;; For now, simple setTimeout
   (js/setTimeout
    #(rf/dispatch dispatch)
    ms)))

;; =============================================================================
;; Composite Effects for Common Patterns
;; =============================================================================

(rf/reg-fx
 :editor/with-highlight
 (fn [{:keys [from to dispatch-after-ms dispatch]}]
   (when-let [^js editor (some-> @editor-ref-atom .-current)]
     (when (.isReady editor)
       (.highlightRange editor (clj->js from) (clj->js to))
       (when dispatch
         (js/setTimeout
          (fn []
            (.clearHighlight editor)
            (rf/dispatch dispatch))
          (or dispatch-after-ms 2000)))))))
