(ns app.core
  (:require
   ["react-dom/client" :as rd]
   [reagent.core :as r]
   [re-frame.core :as rf]
   [re-frisk.core :as rfsk]
   [re-posh.core :as rp]
   [taoensso.timbre :as log]
   [app.db :refer [ds-conn]]
   [app.events :as e]
   [app.subs] ;; Registers subscriptions on load.
   [app.views.main :refer [root-component]]))

(defonce app-root (atom nil))

;; Mounts the root Reagent component to the DOM element with id "app".
(defn mount-root []
  (log/info "Mounting root component")
  (rf/clear-subscription-cache!)
  (let [container (js/document.getElementById "app")]
    (if container
      (do
        (when (nil? @app-root)
          (reset! app-root (rd/createRoot container)))
        (.render @app-root (r/as-element [root-component])))
      (log/warn "No target container for mount; skipping"))))

;; Initializes the application: sets up error handling, initializes state, connects datascript, mounts UI, and enables debugging tools.
(defn ^:export init []
  ;; Global uncaught exception handler.
  (js/window.addEventListener "error"
                              (fn [event]
                                (log/error "Uncaught error:"
                                           (.-message event)
                                           (.-filename event)
                                           (.-lineno event)
                                           (.-error event))))
  (log/info "Initializing app")
  (rp/dispatch-sync [::e/initialize])
  (rp/connect! ds-conn) ;; Connect before mount to ensure subs work.
  (mount-root) ;; Mount after connect.
  (rfsk/enable)) ;; Enable re-frisk for app-db inspection.

;; Reload hook for hot-reloading: re-connects Posh and re-mounts the root.
(defn ^:dev/after-load ^:export reload []
  (log/debug "Re-connecting Posh on hot reload")
  (rp/connect! ds-conn)
  (mount-root))
