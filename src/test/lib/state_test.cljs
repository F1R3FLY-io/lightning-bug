(ns test.lib.state-test
  "Tests for the lib.state module.

   This module manages editor configuration, language settings, and shared
   resources with async loading and validation."
  (:require
   [clojure.test :refer [deftest is testing use-fixtures async]]
   [clojure.core.async :refer [go <! timeout chan put! promise-chan]]
   [lib.state :as state]))

;; =============================================================================
;; Fixtures
;; =============================================================================

(use-fixtures :each
  {:before (fn []
             (reset! state/resources {:lsp {} :tree-sitter {}}))
   :after (fn []
            (reset! state/resources {:lsp {} :tree-sitter {}}))})

;; =============================================================================
;; kebab-keyword Tests
;; =============================================================================

(deftest kebab-keyword-converts-correctly
  (testing "Converts camelCase to kebab-case"
    (is (= :grammar-wasm (state/kebab-keyword :grammarWasm)))
    (is (= :highlights-query-path (state/kebab-keyword :highlightsQueryPath)))
    (is (= :lsp-url (state/kebab-keyword :lspUrl)))
    (is (= :tree-sitter-wasm (state/kebab-keyword :treeSitterWasm)))
    (is (= :on-content-change (state/kebab-keyword :onContentChange)))))

(deftest kebab-keyword-handles-simple-keywords
  (testing "Already kebab-case keywords remain unchanged"
    (is (= :simple (state/kebab-keyword :simple)))
    (is (= :already-kebab (state/kebab-keyword :already-kebab)))
    (is (= :extensions (state/kebab-keyword :extensions)))))

(deftest kebab-keyword-handles-multiple-capitals
  (testing "Multiple consecutive capitals handled correctly"
    ;; All-caps keywords have no lowercase-to-uppercase transitions, so they stay unchanged
    (is (= :LSP (state/kebab-keyword :LSP)))
    ;; Leading uppercase sequence without preceding lowercase stays unchanged
    (is (= :XMLParser (state/kebab-keyword :XMLParser)))
    ;; Regex only matches lowercase-followed-by-uppercase transitions
    ;; "myXML" has one transition: y->X, so becomes "my-xML"
    (is (= :my-xML (state/kebab-keyword :myXML)))
    ;; "getHTTPResponse" has one transition: t->H, so becomes "get-hTTPResponse"
    (is (= :get-hTTPResponse (state/kebab-keyword :getHTTPResponse)))))

(deftest kebab-keyword-handles-single-char
  (testing "Single character keywords"
    ;; Single lowercase stays lowercase
    (is (= :a (state/kebab-keyword :a)))
    ;; Single uppercase stays uppercase (no transitions to convert)
    (is (= :X (state/kebab-keyword :X)))))

;; =============================================================================
;; convert-config-keys Tests
;; =============================================================================

(deftest convert-config-keys-recursive
  (testing "Recursively converts all keys in nested maps"
    (let [input {:grammarWasm "path"
                 :innerConfig {:highlightsQueryPath "query.scm"
                               :deepNested {:someValue 42}}}
          expected {:grammar-wasm "path"
                    :inner-config {:highlights-query-path "query.scm"
                                   :deep-nested {:some-value 42}}}]
      (is (= expected (state/convert-config-keys input))))))

(deftest convert-config-keys-preserves-values
  (testing "Values are preserved during conversion"
    (let [input {:testKey [1 2 3]
                 :anotherKey {:nested "value"}}
          result (state/convert-config-keys input)]
      (is (= [1 2 3] (:test-key result)))
      (is (= {:nested "value"} (:another-key result))))))

(deftest convert-config-keys-handles-empty-map
  (testing "Empty map returns empty map"
    (is (= {} (state/convert-config-keys {})))))

;; =============================================================================
;; validate-editor-config! Tests
;; =============================================================================

(deftest validate-editor-config!-valid-config-passes
  (testing "Valid editor config passes validation"
    (let [config {:tree-sitter-wasm "path/to/tree-sitter.wasm"
                  :languages {"rholang" {:extensions [".rho"]}}}]
      ;; Should not throw
      (state/validate-editor-config! config)
      (is true "Validation passed"))))

(deftest validate-editor-config!-minimal-config-passes
  (testing "Minimal valid config passes"
    (let [config {}]
      ;; Empty config is valid (all keys are optional)
      (state/validate-editor-config! config)
      (is true "Validation passed"))))

(deftest validate-editor-config!-with-all-optional-keys
  (testing "Config with all optional keys passes"
    (let [config {:tree-sitter-wasm "path.wasm"
                  :extra-extensions []
                  :default-protocol "file"
                  :on-content-change (fn [_] nil)
                  :languages {"test" {:extensions [".test"]}}}]
      (state/validate-editor-config! config)
      (is true "Validation passed"))))

(deftest validate-editor-config!-invalid-config-throws
  (testing "Invalid editor config throws exception"
    (let [config {:tree-sitter-wasm 123}] ; Should be string or fn
      (is (thrown? js/Error (state/validate-editor-config! config))))))

;; =============================================================================
;; normalize-languages Tests
;; =============================================================================

(deftest normalize-languages-converts-keyword-keys-to-strings
  (testing "Keyword language keys are converted to strings"
    (let [input {:rholang {:extensions [".rho"]}
                 :javascript {:extensions [".js"]}}
          result (state/normalize-languages input)]
      (is (contains? result "rholang"))
      (is (contains? result "javascript"))
      (is (not (contains? result :rholang)))
      (is (not (contains? result :javascript))))))

(deftest normalize-languages-string-keys-preserved
  (testing "String language keys are preserved"
    (let [input {"already-string" {:extensions [".txt"]}}
          result (state/normalize-languages input)]
      (is (contains? result "already-string"))
      (is (= [".txt"] (:extensions (get result "already-string")))))))

(deftest normalize-languages-converts-camelCase-to-kebab-case
  (testing "camelCase config keys are converted to kebab-case"
    (let [input {"test" {:grammarWasm "path.wasm"
                         :highlightsQueryPath "query.scm"
                         :extensions [".test"]}}
          result (state/normalize-languages input)]
      (is (contains? (get result "test") :grammar-wasm))
      (is (contains? (get result "test") :highlights-query-path))
      (is (not (contains? (get result "test") :grammarWasm)))
      (is (not (contains? (get result "test") :highlightsQueryPath))))))

(deftest normalize-languages-validates-config
  (testing "Invalid language config throws"
    (let [input {"test" {}}] ; Missing required :extensions
      (is (thrown? js/Error (state/normalize-languages input))))))

(deftest normalize-languages-rejects-unrecognized-keys
  (testing "Unrecognized keys in language config throw"
    (let [input {"test" {:extensions [".test"]
                         :unknownKey "value"}}]
      (is (thrown? js/Error (state/normalize-languages input))))))

(deftest normalize-languages-accepts-all-valid-keys
  (testing "All valid language config keys are accepted"
    (let [input {"test" {:extensions [".test"]
                         :grammar-wasm "grammar.wasm"
                         :parser (fn [] nil)
                         :highlights-query-path "highlights.scm"
                         :highlights-query "(source) @keyword"
                         :indents-query-path "indents.scm"
                         :indents-query "(block) @indent"
                         :lsp-url "ws://localhost:3000"
                         :file-icon "test-icon"
                         :fallback-highlighter "text"
                         :indent-size 2}}
          result (state/normalize-languages input)]
      (is (some? (get result "test")))
      (is (= [".test"] (:extensions (get result "test"))))
      (is (= 2 (:indent-size (get result "test")))))))

;; =============================================================================
;; Resource Management Tests
;; =============================================================================

(deftest get-resource-returns-nil-when-absent
  (testing "get-resource returns nil for non-existent resources"
    (is (nil? (state/get-resource :lsp "non-existent")))
    (is (nil? (state/get-resource :tree-sitter "non-existent")))))

(deftest set-resource!-stores-resource
  (testing "set-resource! stores a resource that can be retrieved"
    (let [test-resource {:socket "mock-socket"}]
      (state/set-resource! :lsp "test-lang" test-resource)
      (is (= test-resource (state/get-resource :lsp "test-lang"))))))

(deftest set-resource!-different-types
  (testing "Resources are stored per type and language"
    (state/set-resource! :lsp "lang1" {:lsp-data "lsp1"})
    (state/set-resource! :tree-sitter "lang1" {:ts-data "ts1"})
    (state/set-resource! :lsp "lang2" {:lsp-data "lsp2"})
    (is (= {:lsp-data "lsp1"} (state/get-resource :lsp "lang1")))
    (is (= {:ts-data "ts1"} (state/get-resource :tree-sitter "lang1")))
    (is (= {:lsp-data "lsp2"} (state/get-resource :lsp "lang2")))))

;; =============================================================================
;; load-resource Tests
;; =============================================================================

(deftest load-resource-returns-existing-immediately
  (async done
         (go
           (state/set-resource! :test-type "test-lang" {:existing "resource"})
           (let [result (<! (state/load-resource :test-type "test-lang" (fn [] {:new "resource"})))]
             (is (= [:ok {:existing "resource"}] result))
             (is (= {:existing "resource"} (state/get-resource :test-type "test-lang"))))
           (done))))

(deftest load-resource-handles-promise-supplier
  (async done
         (go
           (let [result (<! (state/load-resource
                             :promise-type "promise-lang"
                             (fn [] (js/Promise.resolve {:promised "data"}))))]
             (is (= [:ok {:promised "data"}] result))
             (is (= {:promised "data"} (state/get-resource :promise-type "promise-lang"))))
           (done))))

(deftest load-resource-handles-channel-supplier
  (async done
         (go
           (let [result (<! (state/load-resource
                             :channel-type "channel-lang"
                             (fn []
                               (let [ch (promise-chan)]
                                 (put! ch [:ok {:channel "data"}])
                                 ch))))]
             (is (= [:ok {:channel "data"}] result))
             (is (= {:channel "data"} (state/get-resource :channel-type "channel-lang"))))
           (done))))

(deftest load-resource-handles-direct-value-supplier
  (async done
         (go
           (let [result (<! (state/load-resource
                             :direct-type "direct-lang"
                             (fn [] {:direct "value"})))]
             (is (= [:ok {:direct "value"}] result))
             (is (= {:direct "value"} (state/get-resource :direct-type "direct-lang"))))
           (done))))

(deftest load-resource-handles-ok-tuple-supplier
  (async done
         (go
           (let [result (<! (state/load-resource
                             :tuple-type "tuple-lang"
                             (fn [] [:ok {:tuple "data"}])))]
             (is (= [:ok {:tuple "data"}] result))
             (is (= {:tuple "data"} (state/get-resource :tuple-type "tuple-lang"))))
           (done))))

(deftest load-resource-handles-error-tuple-supplier
  (async done
         (go
           (let [result (<! (state/load-resource
                             :error-type "error-lang"
                             (fn [] [:error "failed"])))]
             (is (= [:error "failed"] result))
             (is (nil? (state/get-resource :error-type "error-lang"))))
           (done))))

(deftest load-resource-deduplicates-concurrent-loads
  (async done
         (go
           (let [call-count (atom 0)
                 supplier (fn []
                            (swap! call-count inc)
                            (go
                              (<! (timeout 50))
                              [:ok {:loaded "resource"}]))
                 ;; Start multiple concurrent loads for the same resource
                 ch1 (state/load-resource :dedup-type "dedup-lang" supplier)
                 ch2 (state/load-resource :dedup-type "dedup-lang" supplier)
                 ch3 (state/load-resource :dedup-type "dedup-lang" supplier)
                 ;; Wait for all results
                 [r1 r2 r3] [(<! ch1) (<! ch2) (<! ch3)]]
             ;; All should get the same result
             (is (= [:ok {:loaded "resource"}] r1))
             (is (= [:ok {:loaded "resource"}] r2))
             (is (= [:ok {:loaded "resource"}] r3))
             ;; Supplier should only be called once
             (is (= 1 @call-count) "Supplier should only be called once for concurrent loads"))
           (done))))

;; =============================================================================
;; close-resource! Tests
;; =============================================================================

(deftest close-resource!-calls-closer-and-removes
  (testing "close-resource! calls closer function and removes resource"
    (let [closed (atom false)
          closer (fn [resource]
                   (is (= {:test "resource"} resource))
                   (reset! closed true))]
      (state/set-resource! :close-type "close-lang" {:test "resource"})
      (is (some? (state/get-resource :close-type "close-lang")))
      (state/close-resource! :close-type "close-lang" closer)
      (is (true? @closed) "Closer should have been called")
      (is (nil? (state/get-resource :close-type "close-lang"))))))

(deftest close-resource!-handles-absent-resource
  (testing "close-resource! is safe when resource doesn't exist"
    (let [closer-called (atom false)]
      ;; Should not throw, closer should not be called
      (state/close-resource! :absent "absent-lang" (fn [_] (reset! closer-called true)))
      (is (false? @closer-called) "Closer should not be called for absent resource"))))

;; =============================================================================
;; Resource Promise Management Tests
;; =============================================================================

(deftest get-resource-promise-returns-nil-when-no-promise
  (testing "get-resource-promise returns nil when no loading promise exists"
    (is (nil? (state/get-resource-promise :some-type "some-lang")))))

(deftest set-resource-promise!-stores-promise
  (testing "set-resource-promise! stores a loading promise"
    (let [promise (promise-chan)]
      (state/set-resource-promise! :promise-test "lang" promise)
      (is (= promise (state/get-resource-promise :promise-test "lang"))))))

(deftest clear-resource-promise!-removes-promise
  (testing "clear-resource-promise! removes the loading promise"
    (let [promise (promise-chan)]
      (state/set-resource-promise! :clear-test "lang" promise)
      (is (some? (state/get-resource-promise :clear-test "lang")))
      (state/clear-resource-promise! :clear-test "lang")
      (is (nil? (state/get-resource-promise :clear-test "lang"))))))

;; =============================================================================
;; normalize-editor-config Tests
;; =============================================================================

(deftest normalize-editor-config-converts-keys
  (testing "normalize-editor-config converts camelCase keys"
    (let [input {:treeSitterWasm "path.wasm"
                 :defaultProtocol "file"}
          result (state/normalize-editor-config input)]
      (is (contains? result :tree-sitter-wasm))
      (is (contains? result :default-protocol))
      (is (not (contains? result :treeSitterWasm))))))

(deftest normalize-editor-config-rejects-unrecognized-keys
  (testing "normalize-editor-config throws on unrecognized keys"
    (let [input {:tree-sitter-wasm "path.wasm"
                 :unknown-key "value"}]
      (is (thrown? js/Error (state/normalize-editor-config input))))))

(deftest normalize-editor-config-validates-spec
  (testing "normalize-editor-config validates against spec"
    (let [invalid-config {:tree-sitter-wasm 123}] ; Should be string or fn
      (is (thrown? js/Error (state/normalize-editor-config invalid-config))))))

;; =============================================================================
;; Edge Cases
;; =============================================================================

(deftest normalize-languages-handles-mixed-keys
  (testing "normalize-languages handles mix of keyword and string keys"
    (let [input {:keyword-lang {:extensions [".kw"]}
                 "string-lang" {:extensions [".str"]}}
          result (state/normalize-languages input)]
      (is (contains? result "keyword-lang"))
      (is (contains? result "string-lang")))))

(deftest resources-isolation
  (testing "Resources of different types are isolated"
    (state/set-resource! :type-a "lang" {:a 1})
    (state/set-resource! :type-b "lang" {:b 2})
    (is (= {:a 1} (state/get-resource :type-a "lang")))
    (is (= {:b 2} (state/get-resource :type-b "lang")))
    (state/close-resource! :type-a "lang" identity)
    (is (nil? (state/get-resource :type-a "lang")))
    (is (= {:b 2} (state/get-resource :type-b "lang")))))

(deftest load-resource-handles-supplier-exception
  (async done
         (go
           (let [result (<! (state/load-resource
                             :exception-type "exception-lang"
                             (fn [] (throw (js/Error. "supplier error")))))]
             ;; Should return error tuple
             (is (= :error (first result)))
             (is (nil? (state/get-resource :exception-type "exception-lang"))))
           (done))))
