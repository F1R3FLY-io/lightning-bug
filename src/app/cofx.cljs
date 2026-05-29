(ns app.cofx
  "Re-Frame coeffects for injecting dependencies into event handlers.

   Coeffects provide read-only access to the outside world during event handling.
   They delegate to the injected repositories (see app.system), which is the
   dependency-injection seam that enables testability via mock implementations."
  (:require [re-frame.core :as rf]
            [domain.protocols :as p]
            [app.system :as sys]
            [app.shared :refer [editor-ref-atom]]))

;; =============================================================================
;; Document Repository Coeffects
;; =============================================================================

(rf/reg-cofx
 :document-repo/active-uri
 (fn [coeffects _]
   (assoc coeffects :active-uri (p/get-active-uri (sys/document-repo)))))

(rf/reg-cofx
 :document-repo/active-document
 (fn [coeffects _]
   ;; EXP-007: coalesced single-query read, via the document repository.
   (assoc coeffects :active-document (p/get-active-document (sys/document-repo)))))

(rf/reg-cofx
 :document-repo/documents
 (fn [coeffects _]
   (assoc coeffects :documents (p/list-documents (sys/document-repo)))))

(rf/reg-cofx
 :document-repo/document
 (fn [coeffects uri]
   ;; EXP-007: coalesced single-query read, via the document repository.
   (assoc coeffects :document (p/get-document-summary (sys/document-repo) uri))))

;; =============================================================================
;; LSP Data Coeffects
;; =============================================================================

(rf/reg-cofx
 :lsp/diagnostics
 (fn [coeffects _]
   (assoc coeffects :diagnostics (p/get-diagnostics (sys/diagnostics-repo)))))

(rf/reg-cofx
 :lsp/diagnostics-by-uri
 (fn [coeffects uri]
   (assoc coeffects :diagnostics (p/get-diagnostics-by-uri (sys/diagnostics-repo) uri))))

(rf/reg-cofx
 :lsp/symbols
 (fn [coeffects _]
   (assoc coeffects :symbols (p/get-symbols (sys/symbols-repo)))))

(rf/reg-cofx
 :lsp/symbols-by-uri
 (fn [coeffects uri]
   (assoc coeffects :symbols (p/get-symbols-by-uri (sys/symbols-repo) uri))))

;; =============================================================================
;; Log Coeffects
;; =============================================================================

(rf/reg-cofx
 :logs/all
 (fn [coeffects _]
   (assoc coeffects :logs (p/get-logs (sys/log-repo)))))

;; =============================================================================
;; Editor Reference Coeffect (not storage — no repository equivalent)
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
   (let [^js editor (some-> @editor-ref-atom .-current)]
     (assoc coeffects :editor-ready?
            (and editor (.isReady editor))))))

;; =============================================================================
;; Time Coeffect
;; =============================================================================

(rf/reg-cofx
 :now
 (fn [coeffects _]
   (assoc coeffects :now (js/Date.now))))
