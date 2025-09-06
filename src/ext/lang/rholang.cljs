(ns ext.lang.rholang
  (:require [clojure.string :as str]))

(def language-config
  {:grammar-wasm "/extensions/lang/rholang/tree-sitter/tree-sitter-rholang.wasm"
   :highlights-query-path "/extensions/lang/rholang/tree-sitter/queries/highlights.scm"
   :indents-query-path "/extensions/lang/rholang/tree-sitter/queries/indents.scm"
   :lsp-url "ws://localhost:41551"
   :extensions [".rho"]
   :file-icon "fas fa-file-code text-primary"
   :fallback-highlighter "none"})

(defn kebab-to-camel [k]
  (let [parts (str/split (name k) #"-")]
    (str/join "" (cons (first parts) (map str/capitalize (rest parts))))))

(def ^:export RholangExtension
  (clj->js (reduce-kv (fn [m k v]
                        (assoc m (kebab-to-camel k) v))
                      {} language-config)))
