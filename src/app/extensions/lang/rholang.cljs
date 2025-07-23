(ns app.extensions.lang.rholang
  (:require
   [app.languages :as langs]))

;; Register Rholang language with string key for consistency.
(langs/register-language "rholang"
                         {:extensions [".rho" ".rholang"]
                          :grammar-wasm "/extensions/lang/rholang/tree-sitter/tree-sitter-rholang.wasm"
                          :highlight-query-path "/extensions/lang/rholang/tree-sitter/queries/highlights.scm"
                          :indents-query-path "/extensions/lang/rholang/tree-sitter/queries/indents.scm"
                          :lsp-url "ws://localhost:41551"
                          :file-icon "fas fa-code text-primary"
                          :fallback-highlighter "none"
                          :indent-size 2})
