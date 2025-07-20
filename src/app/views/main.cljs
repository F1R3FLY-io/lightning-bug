(ns app.views.main
  (:require
   [re-frame.core :as rf]
   [re-com.core :as rc]
   [app.views.diagnostics :refer [component] :rename {component diagnostics-comp}]
   [app.views.editor :refer [component] :rename {component editor-comp}]
   [app.views.explorer :refer [component] :rename {component explorer-comp}]
   [app.views.output :refer [component] :rename {component output-comp}]
   [app.views.search :refer [component] :rename {component search-comp}]
   [app.views.symbols :refer [component] :rename {component symbols-comp}]))

;; Renders the top toolbar with action buttons.
(defn toolbar []
  (let [connected @(rf/subscribe [:lsp/connected?])]
    [:div.toolbar.d-flex
     [:button.btn.btn-sm.btn-primary.me-2 {:on-click #(rf/dispatch [:app.events/file-add])} [:i.fas.fa-plus] " Add File"]
     [:button.btn.btn-sm.btn-danger.me-2 {:on-click #(rf/dispatch [:app.events/file-remove @(rf/subscribe [:active-file])])} [:i.fas.fa-trash] " Remove"]
     [:button.btn.btn-sm.btn-secondary.me-2 {:on-click #(rf/dispatch [:open-rename-modal])} [:i.fas.fa-edit] " Rename"]
     [:button.btn.btn-sm.btn-info.me-2 {:on-click #(rf/dispatch [:app.events/lsp-connect])} [:i.fas.fa-plug] " Connect LSP"]
     [:span.me-2 {:class (if connected "text-success" "text-danger")} (if connected "LSP Connected" "LSP Disconnected")]
     [:button.btn.btn-sm.btn-success.me-2 {:on-click #(rf/dispatch [:app.events/run-agent])} [:i.fas.fa-play] " Run Agent"]
     [:button.btn.btn-sm.btn-warning.me-2 {:on-click #(rf/dispatch [:app.events/validate-agent])} [:i.fas.fa-check] " Validate"]
     [:button.btn.btn-sm.btn-secondary.me-2 {:on-click #(rf/dispatch [:app.events/toggle-search])} [:i.fas.fa-search] " Search"]]))

;; Renders overlay for current status (running, success, error).
(defn status-overlay []
  (let [status @(rf/subscribe [:status])]
    (when status
      [:div.overlay
       (case status
         :running "Agent is Running"
         :success [:span.diagnostic-success "Validation Success"]
         :error "Validation Failed")])))

(defn rename-modal []
  (let [visible? @(rf/subscribe [:rename/visible?])
        new-name @(rf/subscribe [:rename/new-name])]
    (when visible?
      [rc/modal-panel
       :backdrop-on-click #(rf/dispatch [:close-rename-modal])
       :child [rc/v-box
               :children [[:h5 "Rename File"]
                          [rc/input-text :model new-name :on-change #(rf/dispatch [:set-rename-name %])]
                          [rc/h-box
                           :children [[rc/button :label "Cancel" :on-click #(rf/dispatch [:close-rename-modal])]
                                      [rc/button :label "Rename" :class "btn-primary" :on-click #(rf/dispatch [:confirm-rename])]]]]]])))

;; Main app component; handles layout, search visibility, and rename modal.
(defn root-component []
  [rc/h-box
   :width "100%"
   :height "100%"
   :children [[editor-comp]]])
