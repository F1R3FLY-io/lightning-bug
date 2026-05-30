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
            [lib.db :as db]))

;; A Workspace bundles the shared, per-instance state. Constructed via
;; `map->Workspace` so additional fields (LSP state, reactive doc-streams, etc.)
;; can be added in later phases without breaking existing construction sites.
(defrecord Workspace [conn resources])

(defn make-workspace
  "Creates an ISOLATED workspace: a fresh DataScript conn + empty resources atom.
  Two editors given distinct workspaces share no documents or resources."
  []
  (map->Workspace {:conn (d/create-conn db/schema)
                   :resources (atom {:lsp {} :tree-sitter {}})}))

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
  "Test seam: empties a workspace's conn + resources in place, preserving the conn
  identity (mirrors the existing `(d/reset-conn! db/conn ...)` fixture idiom)."
  [{:keys [conn resources]}]
  (d/reset-conn! conn (d/empty-db db/schema))
  (reset! resources {:lsp {} :tree-sitter {}}))
