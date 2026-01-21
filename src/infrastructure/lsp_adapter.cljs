(ns infrastructure.lsp-adapter
  "LSP client adapter implementing the ILspClient protocol.

   This adapter wraps the existing lib.lsp.client functionality,
   providing a clean interface that conforms to the domain protocols."
  (:require [domain.protocols :as p]
            [lib.lsp.client :as lsp]
            [clojure.core.async :refer [go <!]]))

;; =============================================================================
;; LSP Client Implementation
;; =============================================================================

(defrecord LspClientAdapter [state-atom events]
  p/ILspClient

  ;; === Connection Management ===

  (connect! [_this language config]
    (lsp/connect language config state-atom events))

  (disconnect! [_this language]
    (lsp/request-shutdown language state-atom))

  (connected? [_this language]
    (get-in @state-atom [:lsp language :connected?] false))

  (initialized? [_this language]
    (get-in @state-atom [:lsp language :initialized?] false))

  ;; === Document Lifecycle Notifications ===

  (notify-did-open! [_this language uri text version]
    (lsp/notify-did-open language uri text version state-atom))

  (notify-did-change! [_this language uri text version]
    (lsp/notify-did-change language uri text version state-atom))

  (notify-did-close! [_this language uri]
    (lsp/notify-did-close language uri state-atom))

  (notify-did-save! [_this language uri text]
    (lsp/notify-did-save language uri text state-atom))

  (notify-did-rename! [_this language old-uri new-uri]
    (lsp/notify-did-rename-files language old-uri new-uri state-atom))

  ;; === Request Operations ===

  (request-symbols! [_this language uri]
    (lsp/request-document-symbol language uri state-atom))

  (request-shutdown! [_this language]
    (lsp/request-shutdown language state-atom)))

;; =============================================================================
;; Factory Function
;; =============================================================================

(defn make-lsp-client
  "Creates an LSP client adapter.

   Parameters:
   - state-atom: Atom holding editor state including LSP state
   - events: RxJS Subject for event emission"
  [state-atom events]
  (->LspClientAdapter state-atom events))
