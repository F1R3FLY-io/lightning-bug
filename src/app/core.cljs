(ns app.core
  (:require
   ["react-dom/client" :as rd]
   [app.db :refer [ds-conn]]
   [app.events :as e]
   [app.subs] ;; Registers subscriptions on load.
   [app.views.main :refer [root-component]]
   [day8.re-frame-10x.preload.react-18]
   [devtools.preload]
   [re-frame.core :as rf]
   [re-frisk.core :as rfsk]
   [re-frisk.preload]
   [re-posh.core :as rp]
   [reagent.core :as r] ;; Use reagent.core/as-element for rendering.
   [taoensso.timbre :as log]))

(defonce app-root nil) ;; Persist React root across hot-reloads (React 18 requires single root per container).

(defn mount-root
  "Mounts the root Reagent component to the DOM element with id 'app'.
  Creates React root only once; reuses for updates/hot-reloads.
  Clears subscription cache before rendering."
  []
  (log/info "Mounting/updating root component with React 18")
  (rf/clear-subscription-cache!)
  (let [container (js/document.getElementById "app")]
    (when container
      (when (nil? app-root)
        (set! app-root (rd/createRoot container))
        (log/debug "Created new React root for container"))
      (log/debug "Rendering root component to DOM")
      (.render app-root (r/as-element [root-component]))
      (log/info "Root component rendered/updated successfully"))
    (when-not container
      (log/warn "No target container for mount; skipping"))))

(defn ^:export init
  "Initializes the application: sets up error handling, initializes state,
  connects datascript, mounts UI, and enables debugging tools."
  []
  ;; Global uncaught exception handler.
  (js/window.addEventListener "error"
                              (fn [event]
                                (log/error "Uncaught error:"
                                           (.-message event)
                                           (.-filename event)
                                           (.-lineno event)
                                           (.-error event))))
  (log/info "Initializing app")
  (rf/dispatch-sync [::e/initialize])
  (rp/connect! ds-conn) ;; Connect before mount to ensure subs work.
  (mount-root) ;; Mount after connect.
  (rfsk/enable)) ;; Enable re-frisk for app-db inspection.

(defn ^:dev/after-load ^:export reload
  "Reload hook for hot-reloading: re-connects Posh and re-renders the root (reuses existing React root)."
  []
  (log/debug "Hot reload triggered: re-connecting Posh and updating UI")
  (rp/connect! ds-conn)
  (mount-root))
