(ns app.system
  "Application dependency container (hexagonal composition root).

   Holds the instantiated infrastructure adapters that the re-frame coeffects
   (app.cofx) and effects (app.fx) depend on. Production wiring uses the
   DataScript-backed repositories; tests inject mocks via set-system!/reset-system!,
   which is the dependency-injection seam the cofx/fx docstrings have always promised.

   The repositories are stateless wrappers over the global lib.db/conn, so a plain
   atom is the right representation (no async lifecycle to manage)."
  (:require [infrastructure.datascript-adapter :as ds]))

(defonce ^:private system (atom nil))

(defn init!
  "Instantiates the production system (DataScript-backed repositories).
   Idempotent: safe to call from app.core/init and the hot-reload hook."
  []
  (reset! system
          {:document-repo    (ds/make-document-repository)
           :diagnostics-repo (ds/make-diagnostics-repository)
           :symbols-repo     (ds/make-symbols-repository)
           :log-repo         (ds/make-log-repository)}))

(defn set-system!
  "Replaces the entire system map (tests inject mock repositories)."
  [m]
  (reset! system m))

(defn reset-system!
  "Clears any injected system; the next accessor call lazily restores the
   production repositories (tests :after, to avoid leaking mocks)."
  []
  (reset! system nil))

(defn- current
  "Returns the current system, lazily instantiating the production repositories
   if none has been set. This makes the accessors robust when init! was not called
   explicitly (e.g. in tests that dispatch real events without injecting mocks)."
  []
  (or @system (do (init!) @system)))

(defn document-repo    [] (:document-repo (current)))
(defn diagnostics-repo [] (:diagnostics-repo (current)))
(defn symbols-repo     [] (:symbols-repo (current)))
(defn log-repo         [] (:log-repo (current)))
