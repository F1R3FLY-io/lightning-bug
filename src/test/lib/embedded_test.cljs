(ns test.lib.embedded-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.string :as str]
            [lib.embedded.tree-sitter :refer [treeSitterWasmUrl]]))

(deftest embedded-urls-valid
  (is (str/starts-with? (treeSitterWasmUrl) "blob:") "tree-sitter WASM URL is blob"))
