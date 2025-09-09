(ns lib.embedded.tree-sitter
  (:require-macros [lib.embedded.macros :refer [embed-base64]]))

(def base64 (embed-base64 "resources/public/js/tree-sitter.wasm"))

(defn tree-sitter-wasm-url []
  (let [binary (js/Uint8Array.from (js/atob base64) (fn [c] (.charCodeAt c 0)))
        blob (js/Blob. #js [binary] #js {:type "application/wasm"})]
    (js/URL.createObjectURL blob)))

#_{:splint/disable [naming/lisp-case]}
(def ^:export treeSitterWasmUrl tree-sitter-wasm-url)
