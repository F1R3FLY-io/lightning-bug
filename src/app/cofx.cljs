(ns app.cofx
  "Re-Frame coeffects for injecting dependencies into event handlers.

   Coeffects provide read-only access to the outside world during event handling.
   They enable testability by allowing injection of mock implementations."
  (:require [re-frame.core :as rf]
            [lib.db :as lib-db]
            [app.shared :refer [editor-ref-atom]]))

;; =============================================================================
;; Document Repository Coeffects
;; =============================================================================

(rf/reg-cofx
 :document-repo/active-uri
 (fn [coeffects _]
   (assoc coeffects :active-uri (lib-db/active-uri))))

(rf/reg-cofx
 :document-repo/active-document
 (fn [coeffects _]
   ;; EXP-007: Coalesced query - single query instead of 3 separate queries
   (let [[uri text lang version] (lib-db/active-uri-text-lang-version)]
     (if uri
       (assoc coeffects :active-document
              {:uri uri
               :text text
               :language lang
               :version version})
       (assoc coeffects :active-document nil)))))

(rf/reg-cofx
 :document-repo/documents
 (fn [coeffects _]
   (assoc coeffects :documents (lib-db/documents))))

(rf/reg-cofx
 :document-repo/document
 (fn [coeffects uri]
   ;; EXP-007: Coalesced query - single query instead of 2 separate queries
   (let [[text lang version] (lib-db/doc-text-lang-version-by-uri uri)]
     (assoc coeffects :document
            (when text
              {:uri uri
               :text text
               :language lang
               :version version})))))

;; =============================================================================
;; LSP Data Coeffects
;; =============================================================================

(rf/reg-cofx
 :lsp/diagnostics
 (fn [coeffects _]
   (assoc coeffects :diagnostics (lib-db/diagnostics))))

(rf/reg-cofx
 :lsp/diagnostics-by-uri
 (fn [coeffects uri]
   (assoc coeffects :diagnostics (lib-db/diagnostics-by-uri uri))))

(rf/reg-cofx
 :lsp/symbols
 (fn [coeffects _]
   (assoc coeffects :symbols (lib-db/symbols))))

(rf/reg-cofx
 :lsp/symbols-by-uri
 (fn [coeffects uri]
   (assoc coeffects :symbols (lib-db/symbols-by-uri uri))))

;; =============================================================================
;; Log Coeffects
;; =============================================================================

(rf/reg-cofx
 :logs/all
 (fn [coeffects _]
   (assoc coeffects :logs (lib-db/logs))))

;; =============================================================================
;; Editor Reference Coeffect
;; =============================================================================

(rf/reg-cofx
 :editor/ref
 (fn [coeffects _]
   (assoc coeffects :editor-ref @editor-ref-atom)))

(rf/reg-cofx
 :editor/current
 (fn [coeffects _]
   (assoc coeffects :editor (some-> @editor-ref-atom .-current))))

(rf/reg-cofx
 :editor/ready?
 (fn [coeffects _]
   (let [editor (some-> @editor-ref-atom .-current)]
     (assoc coeffects :editor-ready?
            (and editor (.isReady editor))))))

;; =============================================================================
;; Time Coeffect
;; =============================================================================

(rf/reg-cofx
 :now
 (fn [coeffects _]
   (assoc coeffects :now (js/Date.now))))
