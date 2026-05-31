(ns app.fx
  "Re-Frame effects for side effects in event handlers.

   Effects handle write operations to the outside world. Storage effects delegate
   to the injected repositories (see app.system) to centralize side effects and
   enable testability via mock injection."
  (:require [re-frame.core :as rf]
            [domain.protocols :as p]
            [app.system :as sys]
            [app.shared :refer [editor-ref-atom]]
            [taoensso.timbre :as log]))

;; =============================================================================
;; Document Effects
;; =============================================================================

(rf/reg-fx
 :document/create
 (fn [doc]
   (p/create-document! (sys/document-repo) doc)))

(rf/reg-fx
 :document/update-text
 (fn [{:keys [uri text]}]
   (p/update-document-text! (sys/document-repo) uri text)))

(rf/reg-fx
 :document/set-active
 (fn [uri]
   (p/set-active-document! (sys/document-repo) uri)))

(rf/reg-fx
 :document/increment-version
 (fn [uri]
   (p/increment-version! (sys/document-repo) uri)))

(rf/reg-fx
 :document/mark-opened
 (fn [uri]
   (p/mark-document-opened! (sys/document-repo) uri)))

(rf/reg-fx
 :document/mark-closed
 (fn [uri]
   (p/mark-document-closed! (sys/document-repo) uri)))

(rf/reg-fx
 :document/delete
 (fn [uri]
   (p/delete-document! (sys/document-repo) uri)))

(rf/reg-fx
 :document/rename
 (fn [{:keys [old-uri new-uri]}]
   (p/rename-document! (sys/document-repo) old-uri new-uri)))

;; =============================================================================
;; Diagnostics Effects
;; =============================================================================

(rf/reg-fx
 :diagnostics/replace
 (fn [{:keys [uri version diagnostics]}]
   (p/replace-diagnostics! (sys/diagnostics-repo) uri version diagnostics)))

;; =============================================================================
;; Symbols Effects
;; =============================================================================

(rf/reg-fx
 :symbols/replace
 (fn [{:keys [uri symbols]}]
   (p/replace-symbols! (sys/symbols-repo) uri symbols)))

;; =============================================================================
;; Log Effects
;; =============================================================================

(rf/reg-fx
 :log/add
 (fn [log]
   (p/add-log! (sys/log-repo) log)))

;; =============================================================================
;; Editor Effects (JS imperative handle — not storage)
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

(defn- log-info-effect [message]
  (log/info message))

(defn- log-warn-effect [message]
  (log/warn message))

(defn- log-error-effect [message]
  (log/error message))

(rf/reg-fx
 :console/log
 log-info-effect)

(rf/reg-fx
 :console/warn
 log-warn-effect)

(rf/reg-fx
 :console/error
 log-error-effect)
