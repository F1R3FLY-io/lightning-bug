(ns test.cljs-test-runner
  (:require [shadow.test.karma :as k]
            ;; Existing tests
            [test.lib.core-test]
            [test.lib.editor.diagnostics-test]
            [test.lib.editor.highlight-test]
            [test.lib.editor.syntax-test]
            [test.lib.lsp.client-test]
            [test.lib.embedded-test]
            [test.ext.embedded-test]
            ;; Phase 2: Database layer tests
            [test.lib.db-test]
            ;; Phase 3: Application layer tests
            [test.app.events-test]
            [test.app.subs-test]
            ;; Phase 4: Infrastructure tests
            [test.lib.lifecycle-test]
            [test.lib.lsp.connection-manager-test]
            [test.domain.entities-test]
            ;; Phase 5: Utility tests
            [test.lib.utils-test]
            [test.lib.debounce-test]
            ;; Phase 6: Integration tests
            [test.integration.document-flow-test]
            [test.integration.lsp-integration-test]
            ;; Phase 7: New coverage tests
            [test.lib.query-cache-test]
            [test.lib.state-test]
            [test.lib.position-property-test]
            [test.integration.coordination-test]))

(defn ^:export main []
  (k/init))
