(ns lib.workspace
  "Instantiable workspace: owns the per-workspace instances of what were formerly
  module-global singletons — the DataScript `conn` (documents/projects/diagnostics/
  symbols/logs) and the loaded language `resources` (grammars/parsers/LSP sockets).

  Multiple Editor instances can SHARE one workspace (so they see the same open
  files and reuse loaded grammars) or use ISOLATED workspaces. This eliminates the
  implicit module globals while preserving cross-editor resource sharing.

  IMPORTANT: `lib.db` must NEVER require `lib.workspace` (it would create a cycle).
  `lib.db` functions receive a bare `conn`, never a `Workspace`."
  (:require [datascript.core :as d]
            ["rxjs" :refer [Subject]]
            [lib.db :as db]))

;; A Workspace bundles the shared, per-instance state. Constructed via
;; `map->Workspace` so additional fields (LSP state, etc.) can be added without
;; breaking existing construction sites.
;;   :conn        - DataScript connection (documents/projects/diagnostics/...)
;;   :resources   - loaded language resources (tree-sitter grammars/parsers, and the
;;                  LSP connect-dedup bookkeeping); see lib.state resource fns.
;;   :doc-streams - atom {uri -> {:subject rxjs.Subject :seq int :ref-count int}}
;;                  the per-(workspace,file) reactive change channel that keeps
;;                  multiple editors over the SAME file in sync (lib.workspace.doc-sync).
;;   :lsp         - atom holding the per-workspace LSP connection state, shape
;;                  {:lsp {lang {:state ... :pending ... :next-id ... :ws socket ...}}
;;                   :pending-lsp-changes {uri [content-change ...]}}.
;;                  ONE LSP connection per language per workspace is shared by all the
;;                  workspace's editor panes (so a file open in two split panes yields a
;;                  single didOpen and one monotonic didChange stream). The inner shape
;;                  mirrors what the per-editor state-atom held under :lsp, so every
;;                  lib.lsp.client `(get-in @atom [:lsp lang ...])` body is unchanged.
;;   :lsp-events  - rxjs Subject onto which the (single, per-workspace) LSP connection emits
;;                  INBOUND server events (diagnostics/symbols/log/lsp-error/lsp-message).
;;                  Every editor pane subscribes and forwards to its OWN per-pane events
;;                  subject, so diagnostics/symbols reach ALL panes viewing the file — not
;;                  just the one whose ConnectionManager happened to open the socket.
(defrecord Workspace [conn resources doc-streams lsp lsp-events])

(defn make-workspace
  "Creates an ISOLATED workspace: a fresh DataScript conn + empty resources + an
  empty reactive doc-stream registry. Two editors given distinct workspaces share
  no documents, resources, or change streams."
  []
  (let [resources (atom {:lsp {} :tree-sitter {}})]
    (map->Workspace {:conn (d/create-conn db/schema)
                     :resources resources
                     :doc-streams (atom {})
                     ;; The lsp atom carries a reference to THIS workspace's resources atom
                     ;; under :res-atom, so lib.lsp.client (which only ever has the lsp atom
                     ;; in hand, including in async WebSocket callbacks) can reach the right,
                     ;; per-workspace socket store without threading a separate parameter.
                     :lsp (atom {:lsp {} :res-atom resources})
                     :lsp-events (Subject.)})))

;; Lazily-created process-default workspace. `defonce` + `delay` is what makes the
;; SAME conn survive React re-renders AND dev hot-reloads (`:dev/after-load`): the
;; delay object is created once and `defonce` preserves it across reloads, so once
;; forced it caches one workspace/conn forever (identical persistence to the old
;; `(defonce conn ...)`). Editors with no :workspace prop resolve here (via
;; ensure-workspace), so their documents persist across reloads.
(defonce default-workspace
  (delay (make-workspace)))

(defn ensure-workspace
  "Returns `ws` if non-nil, else the lazily-created shared default workspace.
  This is the single resolution point used by the Editor component."
  [ws]
  (or ws @default-workspace))

(defn default-conn
  "The default workspace's DataScript conn. Convenience for the demo app and for
  tests that operate on the shared default workspace. Production editors thread
  their OWN workspace conn (via ctx) and do not use this."
  []
  (:conn @default-workspace))

(defn workspace?
  "True if x is a Workspace instance."
  [x]
  (instance? Workspace x))

(defn reset-workspace!
  "Test seam: empties a workspace's conn + resources + LSP state in place, preserving
  the conn identity (mirrors the existing `(d/reset-conn! db/conn ...)` fixture idiom)."
  [{:keys [conn resources lsp]}]
  (d/reset-conn! conn (d/empty-db db/schema))
  (reset! resources {:lsp {} :tree-sitter {}})
  (when lsp
    ;; Clear any running per-language auto-cleanup intervals (CM/start-auto-cleanup!) before
    ;; wiping the LSP state, so they don't outlive the reset as orphaned timers.
    (doseq [[_lang entry] (:lsp @lsp)]
      (when-let [id (:cleanup-interval-id entry)]
        (js/clearInterval id)))
    (reset! lsp {:lsp {} :res-atom resources})))
