(ns app.views.logs
  (:require
   ["react" :as react]
   [app.events :as e]
   [app.utils :as u]
   [re-com.core :as rc]
   [re-frame.core :as rf]
   [reagent.core :as r]
   [taoensso.timbre :as log]))

(defn logs-content
  "Inner React functional component for rendering the logs content.
   Uses React hooks for resize observation."
  [{:keys [visible? diagnostics height on-resize]}]
  (let [content-ref (react/useRef nil)]
    (react/useEffect
     (fn []
       (log/trace "useEffect in logs-content triggered, visible?:" visible?)
       (if (and visible? (.-current content-ref))
         (let [observer (js/ResizeObserver.
                         (fn [entries]
                           (let [new-height (.-offsetHeight (.-target (first entries)))]
                             (log/trace "ResizeObserver detected new height:" new-height)
                             (on-resize new-height))))]
           (.observe observer (.-current content-ref))
           (fn []
             (log/trace "Cleaning up ResizeObserver in logs-content")
             (.disconnect observer)))
         (do
           (log/trace "useEffect skipped: not visible or no ref")
           js/undefined)))
     #js [visible?])
    [:div.logs-content
     {:ref content-ref
      :style {:overflow-y "auto"
              :height (str height "px")
              :min-height "100px"
              :max-height "80vh"
              :resize "vertical"
              :padding "10px"}}
     [rc/v-box
      :children (if (empty? diagnostics)
                  [[:label.text-muted "No diagnostics available"]]
                  (for [d diagnostics]
                    (let [range (:range d)
                          start (:start range)
                          end (:end range)
                          line (inc (:line start))
                          col (inc (:character start))
                          msg (:message d)
                          sev (:severity d)
                          cls (case sev
                                1 "text-danger"
                                2 "text-warning"
                                3 "text-info"
                                4 "text-muted"
                                "")]
                      ^{:key (str line "-" col "-" msg)}
                      [:label {:class cls
                               :style {:cursor "pointer"
                                       :display "block"}
                               :on-click #(rf/dispatch [::e/set-cursor-pos {:line line :column col}])
                               :on-mouse-enter #(rf/dispatch [::e/set-highlight-range
                                                             {:from {:line (inc (:line start)) :column (inc (:character start))}
                                                              :to {:line (inc (:line end)) :column (inc (:character end))}}])
                               :on-mouse-leave #(rf/dispatch [::e/set-highlight-range nil])}
                       msg])))]]))

(defn component
  "Renders the collapsible logs panel displaying LSP diagnostics.
   Wraps the logs-content React component, integrating with Reagent."
  []
  (let [visible? @(rf/subscribe [:logs-visible?])
        diags @(rf/subscribe [:lsp/diagnostics])
        height @(rf/subscribe [:logs-height])]
    (log/debug "Rendering logs panel, visible?:" visible? ", diagnostics count:" (count diags))
    [:div.logs-panel
     {:style {:display "flex"
              :flex-direction "column-reverse"
              :border-top "1px solid #dee2e6"}}
     (when visible?
       [r/as-element
        [:f> logs-content
         {:visible? visible?
          :diagnostics diags
          :height height
          :on-resize #(rf/dispatch [::e/set-logs-height %])}]])
     [rc/button
      :label [:span "Logs " (if visible?
                              [:i.fas.fa-chevron-down]
                              [:i.fas.fa-chevron-up])]
      :on-click #(rf/dispatch [::e/toggle-logs])
      :class "logs-header"
      :style {:width "100%"
              :border-radius 0
              :text-align "left"}]]))
