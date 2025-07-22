(ns test.lib.core-test
  (:require
   [clojure.core.async :refer [go <! timeout]]
   [clojure.test :refer [deftest is async]]
   [reagent.dom :as rdom]
   ["react" :as react]
   ["react-dom/test-utils" :as test-utils]
   [lib.core :refer [Editor]]))

(deftest editor-renders
  (async done
         (go
           (let [container (js/document.createElement "div")]
             (test-utils/act #(rdom/render [:> Editor {:content "test"}] container))
             (<! (timeout 100))
             (is (some? (.querySelector container ".code-editor")) "Editor container rendered")
             (done)))))

(deftest editor-content-change-callback
  (async done
         (go
           (let [callback-called (atom false)
                 container (js/document.createElement "div")
                 ref (react/createRef)]
             (test-utils/act #(rdom/render [:> Editor {:content "initial"
                                                      :onContentChange (fn [content]
                                                                        (reset! callback-called true)
                                                                        (is (= content "updated") "Callback received updated content"))}
                                                     {:ref ref}]
                                           container))
             (<! (timeout 100))
             (test-utils/act #(.setContent (.-current ref) "updated"))
             (<! (timeout 100))
             (is @callback-called "onContentChange callback triggered")
             (done)))))

(deftest language-config-key-normalization
  (let [props {:languages {"rholang" {:grammarWasm "path.wasm"
                                      :highlightQueryPath "query.scm"
                                      :lspUrl "ws://test"
                                      :lspMethod "websocket"
                                      :fileIcon "icon"
                                      :fallbackHighlighter "none"}}}
        state (#'lib.core/default-state props)]
    (is (= "path.wasm" (get-in state [:languages "rholang" :grammar-wasm])) "grammarWasm normalized to :grammar-wasm")
    (is (= "query.scm" (get-in state [:languages "rholang" :highlight-query-path])) "highlightQueryPath normalized")
    (is (= "ws://test" (get-in state [:languages "rholang" :lsp-url])) "lspUrl normalized")
    (is (= "websocket" (get-in state [:languages "rholang" :lsp-method])) "lspMethod normalized")
    (is (= "icon" (get-in state [:languages "rholang" :file-icon])) "fileIcon normalized")
    (is (= "none" (get-in state [:languages "rholang" :fallback-highlighter])) "fallbackHighlighter normalized")))
