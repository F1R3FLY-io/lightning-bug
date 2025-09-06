(ns test.ext.embedded-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.string :as str]
            [ext.embedded.lang.rholang :refer [treeSitterRholangWasmUrl]]
            [ext.embedded.lang.rholang-queries :refer [highlightsQueryUrl indentsQueryUrl]]))

(deftest embedded-urls-valid
  (is (str/starts-with? (treeSitterRholangWasmUrl) "blob:") "rholang WASM URL is blob")
  (is (str/starts-with? (highlightsQueryUrl) "blob:") "highlights query URL is blob")
  (is (str/starts-with? (indentsQueryUrl) "blob:") "indents query URL is blob"))
