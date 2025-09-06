(ns cljs-test-runner
  (:require [shadow.test.karma :as k]
            [test.lib.core-test]
            [test.lib.editor.diagnostics-test]
            [test.lib.editor.highlight-test]
            [test.lib.editor.syntax-test]
            [test.lib.lsp.client-test]
            [test.lib.embedded-test]
            [test.ext.embedded-test]))
(defn ^:export main []
  (k/init))
