(ns app.views.error-boundary
  (:require
   [reagent.core :as r]
   [taoensso.timbre :as log]))

(defn component
  "React error boundary component to catch and handle rendering errors.
   Displays an error message when an error occurs, otherwise renders children."
  [{:keys [_children]}]
  (let [err-state (r/atom nil)]
    (r/create-class
     {:display-name "ErrorBoundary"
      :get-derived-state-from-error (fn [error]
                                      (reset! err-state error)
                                      (log/warn "ErrorBoundary: Caught error" (.-message error))
                                      #js {:error error})
      :component-did-catch (fn [_this error info]
                             (log/error "ErrorBoundary: Caught error:" (.-message error) "component stack:" (.-componentStack info)))
      :reagent-render (fn [{:keys [children]}]
                        (if @err-state
                          [:div.text-danger.p-3
                           "An error occurred in the editor. Please try refreshing or contact support."
                           [:div.mt-2
                            [:button.btn.btn-primary
                             {:on-click #(reset! err-state nil)}
                             "Retry"]]]
                          children))})))
