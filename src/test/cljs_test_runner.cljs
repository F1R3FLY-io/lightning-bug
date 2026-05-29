(ns test.cljs-test-runner
  (:require [shadow.test.karma :as k]
            ;; Existing tests
            [test.lib.core-test]
            [test.lib.core-api-test]
            [test.lib.editor.diagnostics-test]
            [test.lib.editor.highlight-test]
            [test.lib.editor.syntax-test]
            [test.lib.editor.syntax-parser-config-test]
            [test.lib.lsp.client-test]
            [test.lib.embedded-test]
            [test.ext.embedded-test]
            ;; Phase 2: Database layer tests
            [test.lib.db-test]
            [test.lib.db-diagnostics-symbols-test]
            [test.lib.db-query-test]
            ;; Phase 3: Application layer tests
            [test.app.events-test]
            [test.app.subs-test]
            [test.app.languages-test]
            [test.app.system-test]
            ;; Phase 4: Infrastructure tests
            [test.lib.lifecycle-test]
            [test.lib.lsp.connection-manager-test]
            [test.infrastructure.datascript-adapter-test]
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
