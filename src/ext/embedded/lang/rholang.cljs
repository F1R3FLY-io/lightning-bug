(ns ext.embedded.lang.rholang
  (:require-macros [lib.embedded.macros :refer [embed-base64]]))

(def base64 (embed-base64 "resources/public/extensions/lang/rholang/tree-sitter/tree-sitter-rholang.wasm"))

(defn tree-sitter-rholang-wasm-url []
  (let [binary (js/Uint8Array.from (js/atob base64) (fn [c] (.charCodeAt c 0)))
        blob (js/Blob. #js [binary] #js {:type "application/wasm"})]
    (js/URL.createObjectURL blob)))

#_{:splint/disable [naming/lisp-case]}
(def ^:export treeSitterRholangWasmUrl tree-sitter-rholang-wasm-url)
