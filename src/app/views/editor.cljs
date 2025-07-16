(ns app.views.editor
  (:require ["@codemirror/state" :as cm-state]
            ["@codemirror/view" :as cm-view]
            ["@codemirror/commands" :as cm-cmds]
            ["web-tree-sitter" :as ts]
            [reagent.core :as r]
            [re-frame.core :as rf]
            [re-com.core :as rc]))

(defonce editor-view (r/atom nil))
(defonce parser (r/atom nil))

(defn init-tree-sitter []
  (ts/init)
  (-> (ts/Language.load "/wasm/tree-sitter-rholang.wasm")
      (.then #(reset! parser (.newParser %)))))

(defn highlight-decorations [tree state]
  ;; Query tree for scopes, create CM decorations
  (cm-view/Decoration.set [])) ; Placeholder

(def extensions
  #js [(cm-view/lineNumbers.)
       (cm-cmds/indentOnInput.)
       (cm-cmds/bracketMatching.)
       (cm-view/keymap.of (cm-cmds/defaultKeymap))
       ;; Add tree-sitter extension
       (cm-state/StateField.define
         {:create (fn [state] {:tree (if @parser (.parse @parser (.text state)) :tree nil)})
          :update (fn [val tr]
                    (if (.-changed tr)
                      (let [tree (:tree val)]
                        (.edit tree #js {:startIndex 0 :oldEndIndex 0 :newEndIndex (.length (.doc tr))}) ; Update tree
                        {:tree (.parse @parser tree (.doc tr))})
                      val))
          :provide (fn [cm-view/EditorDecoration (highlight-decorations (:tree val) state))]})])

(defn editor []
  (r/create-class
   {:component-did-mount
    (fn [this]
      (init-tree-sitter)
      (let [content @(rf/subscribe [:active-content])
            state (cm-state/EditorState.create #js {:doc content :extensions extensions})
            view (cm-view/EditorView. #js {:state :parent (r/dom-node this)})]
        (reset! editor-view view)
        ;; On change listener
        (.addEventListener "change" (fn [] (rf/dispatch [:editor/update-content (.doc (.state view))])))
        (when lsp-connected (lsp/send {:method "textDocument/didOpen" :params {:textDocument {:uri "untitled" :text content}}}))) 
    :reagent-render (fn [] [:div.code-editor {:class "flex-grow-1"}])}))

(defn status-bar []
  (let [pos (when-let [v @editor-view]
             (let [sel (.state v).selection.main]
               (str "Line " (inc (.lineAt (.doc (.state v)) (.head sel))) ": " (.column sel))))]
    [:div.status-bar pos]))

(defn component []
  [rc/v-box :children [[editor] [status-bar]] :size "1"])
