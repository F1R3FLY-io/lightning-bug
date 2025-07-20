(ns app.views.editor
  (:require
   ["@codemirror/commands" :refer [defaultKeymap]]
   ["@codemirror/language" :refer [indentOnInput bracketMatching]]
   ["@codemirror/state" :refer [EditorState]]
   ["@codemirror/view" :refer [lineNumbers keymap EditorView]]
   ["react" :as react]
   [app.editor.state :as es]
   [app.editor.syntax :as syntax]
   [app.lsp.client :as lsp]
   [re-com.core :as rc]
   [re-frame.core :as rf]
   [re-frame.db :refer [app-db]]
   [taoensso.timbre :as log]))

;; Returns the base extensions for CodeMirror, filtering out any null values to prevent configuration errors.
(defn get-extensions []
  (let [ln (lineNumbers)
        io (indentOnInput)
        bm (bracketMatching)
        sc (.of syntax/syntax-compartment #js []) ;; Use empty array to avoid null extensions error in compartment resolution.
        km (.of keymap defaultKeymap)
        dark-theme (.theme EditorView #js {} #js {:dark true}) ;; Enables dark mode.
        all [ln io bm sc km dark-theme]
        filtered (filter #(not (or (nil? %) (identical? % js/undefined))) all)]
    (when (< (count filtered) (count all))
      (log/warn "Some CodeMirror extensions were null or undefined and have been removed to prevent errors. Check imports and package versions."))
    (into-array filtered)))

;; Updates the cursor position based on the editor state.
(defn update-cursor-pos [^js/cm-state.EditorState state]
  (let [sel (.-main (.-selection state))
        head (.-head sel)
        doc ^js/cm-state.Text (.-doc state)
        line-obj ^js/cm-state.Line (.lineAt doc head)
        line (.-number line-obj)
        col (inc (- head (.-from line-obj)))]
    (reset! es/cursor-pos {:line line :col col})))

;; Reagent functional component for the CodeMirror editor.
(defn editor-component [content]
  (let [editor-ref (react/useRef nil)]
    (react/useEffect
     (fn []
       (when-let [container (.-current editor-ref)]
         (log/info "Mounting editor component")
         (let [exts (get-extensions)
               _ (set! (.-exts js/window) exts)
               timeout (atom nil)
               update-ext (.. EditorView -updateListener
                              (of (fn [^js u]
                                    (when (or (.-docChanged u) (.-selectionSet u))
                                      (update-cursor-pos (.-state u)))
                                    (when (.-docChanged u)
                                      (when @timeout (js/clearTimeout @timeout))
                                      (reset! timeout (js/setTimeout #(rf/dispatch [:app.events/editor-update-content (.toString (.-doc (.-state u)))]) 300))))))
               _ (when (some? update-ext) (.push exts update-ext))
               _ (log/debug "Using" (.-length exts) "extensions")
               state (.create EditorState #js {:doc content :extensions exts})
               view (EditorView. #js {:state state :parent container})]
           (reset! es/editor-view view)
           ;; Set initial cursor position.
           (update-cursor-pos (.-state view))
           (syntax/init-syntax view)
           (when (:connection (:lsp @app-db))
             (lsp/send {:method "textDocument/didOpen" :params {:textDocument {:uri "untitled" :text content}}})
             (lsp/request-symbols "untitled"))
           ;; Return cleanup function for unmount.
           (fn []
             (when-let [view @es/editor-view]
               (.destroy view))
             (reset! es/editor-view nil)))))
     #js []) ;; Run once on mount.
    (react/useEffect
     (fn []
       (when-let [view @es/editor-view]
         (let [current-doc (.toString (.-doc (.-state view)))]
           (when (not= content current-doc)
             (.dispatch view #js {:changes #js {:from 0 :to (.-length (.-doc (.-state view))) :insert content}}))))
       js/undefined)
     #js [content]) ;; Update when content changes.
    [:div {:ref editor-ref :class "code-editor flex-grow-1"}]))

;; Renders the status bar with cursor position.
(defn status-bar []
  (let [{:keys [line col]} @es/cursor-pos]
    [:div.status-bar (str "Line " line ":" col)]))

;; Main editor view component with status bar.
(defn component []
  [rc/v-box
   :children [[:f> editor-component @(rf/subscribe [:active-content])]
              [status-bar]]
   :height "100%"
   :size "1"
   :class "flex-grow-1 d-flex flex-column"])
