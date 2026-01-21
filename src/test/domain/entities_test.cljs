(ns test.domain.entities-test
  "Tests for domain entity definitions and specs."
  (:require
   [clojure.test :refer [deftest is testing]]
   [clojure.spec.alpha :as s]
   [clojure.test.check :as tc]
   [clojure.test.check.generators :as gen]
   [clojure.test.check.properties :as prop]
   [domain.entities :as entities]))

;; =============================================================================
;; Document Entity Tests
;; =============================================================================

(deftest document-spec-validates-correct
  (testing "Valid document passes spec"
    (let [doc {:uri "file:///test.rho"
               :text "content"
               :language "rholang"
               :version 0
               :dirty false
               :opened false}]
      (is (s/valid? ::entities/document doc)))))

(deftest document-spec-rejects-missing-uri
  (testing "Document without URI fails spec"
    (let [doc {:text "content"
               :language "rholang"
               :version 0
               :dirty false
               :opened false}]
      (is (not (s/valid? ::entities/document doc))))))

(deftest document-spec-rejects-empty-uri
  (testing "Document with empty URI fails spec"
    (let [doc {:uri ""
               :text "content"
               :language "rholang"
               :version 0
               :dirty false
               :opened false}]
      (is (not (s/valid? ::entities/document doc))))))

(deftest document-spec-rejects-empty-language
  (testing "Document with empty language fails spec"
    (let [doc {:uri "file:///test.rho"
               :text "content"
               :language ""
               :version 0
               :dirty false
               :opened false}]
      (is (not (s/valid? ::entities/document doc))))))

(deftest document-spec-rejects-negative-version
  (testing "Document with negative version fails spec"
    (let [doc {:uri "file:///test.rho"
               :text "content"
               :language "rholang"
               :version -1
               :dirty false
               :opened false}]
      (is (not (s/valid? ::entities/document doc))))))

(deftest make-document-creates-with-defaults
  (testing "make-document provides default values"
    (let [doc (entities/make-document {:uri "file:///test.rho"})]
      (is (= "file:///test.rho" (:uri doc)))
      (is (= "" (:text doc)))
      (is (= "text" (:language doc)))
      (is (= 0 (:version doc)))
      (is (false? (:dirty doc)))
      (is (false? (:opened doc))))))

(deftest make-document-uses-provided-values
  (testing "make-document uses provided values over defaults"
    (let [doc (entities/make-document {:uri "file:///test.rho"
                                       :text "custom text"
                                       :language "rholang"
                                       :version 5
                                       :dirty true
                                       :opened true})]
      (is (= "custom text" (:text doc)))
      (is (= "rholang" (:language doc)))
      (is (= 5 (:version doc)))
      (is (true? (:dirty doc)))
      (is (true? (:opened doc))))))

(deftest valid-document?-validates
  (testing "valid-document? validates correctly"
    (is (true? (entities/valid-document?
                {:uri "file:///test.rho"
                 :text "content"
                 :language "rholang"
                 :version 0
                 :dirty false
                 :opened false})))
    (is (false? (entities/valid-document?
                 {:text "missing uri"})))))

;; =============================================================================
;; Position Entity Tests
;; =============================================================================

(deftest position-spec-validates-correct
  (testing "Valid position passes spec"
    (is (s/valid? ::entities/position {:line 0 :char 0}))
    (is (s/valid? ::entities/position {:line 10 :column 5}))))

(deftest position-spec-rejects-negative
  (testing "Position with negative line fails"
    (is (not (s/valid? ::entities/position {:line -1 :char 0})))))

(deftest make-position-creates-position
  (testing "make-position with char"
    (let [pos (entities/make-position {:line 5 :char 10})]
      (is (= 5 (:line pos)))
      (is (= 10 (:char pos)))))

  (testing "make-position with column"
    (let [pos (entities/make-position {:line 5 :column 10})]
      (is (= 5 (:line pos)))
      (is (= 10 (:char pos)))))

  (testing "make-position defaults char to 0"
    (let [pos (entities/make-position {:line 5})]
      (is (= 0 (:char pos))))))

;; =============================================================================
;; Range Entity Tests
;; =============================================================================

(deftest range-spec-validates-correct
  (testing "Valid range passes spec"
    (is (s/valid? ::entities/range
                  {:start {:line 0 :char 0}
                   :end {:line 5 :char 10}}))))

(deftest make-range-creates-range
  (testing "make-range creates range from positions"
    (let [r (entities/make-range {:line 0 :char 0} {:line 5 :char 10})]
      (is (= 0 (get-in r [:start :line])))
      (is (= 0 (get-in r [:start :char])))
      (is (= 5 (get-in r [:end :line])))
      (is (= 10 (get-in r [:end :char]))))))

;; =============================================================================
;; Diagnostic Entity Tests
;; =============================================================================

(deftest diagnostic-spec-validates-correct
  (testing "Valid diagnostic passes spec"
    (let [diag {:uri "file:///test.rho"
                :message "Error message"
                :severity 1
                :startLine 0
                :startChar 0
                :endLine 0
                :endChar 5}]
      (is (s/valid? ::entities/diagnostic diag)))))

(deftest diagnostic-spec-rejects-missing-message
  (testing "Diagnostic without message fails spec"
    (let [diag {:uri "file:///test.rho"
                :severity 1
                :startLine 0
                :startChar 0
                :endLine 0
                :endChar 5}]
      (is (not (s/valid? ::entities/diagnostic diag))))))

(deftest diagnostic-spec-rejects-empty-message
  (testing "Diagnostic with empty message fails spec"
    (let [diag {:uri "file:///test.rho"
                :message ""
                :severity 1
                :startLine 0
                :startChar 0
                :endLine 0
                :endChar 5}]
      (is (not (s/valid? ::entities/diagnostic diag))))))

(deftest diagnostic-spec-rejects-invalid-severity
  (testing "Diagnostic with severity > 4 fails spec"
    (let [diag {:uri "file:///test.rho"
                :message "Error"
                :severity 5
                :startLine 0
                :startChar 0
                :endLine 0
                :endChar 5}]
      (is (not (s/valid? ::entities/diagnostic diag)))))

  (testing "Diagnostic with severity 0 fails spec"
    (let [diag {:uri "file:///test.rho"
                :message "Error"
                :severity 0
                :startLine 0
                :startChar 0
                :endLine 0
                :endChar 5}]
      (is (not (s/valid? ::entities/diagnostic diag))))))

(deftest make-diagnostic-creates-from-lsp
  (testing "make-diagnostic transforms LSP format"
    (let [diag (entities/make-diagnostic
                {:uri "file:///test.rho"
                 :message "Error"
                 :severity 2
                 :range {:start {:line 1 :character 5}
                         :end {:line 1 :character 10}}
                 :version 3})]
      (is (= "file:///test.rho" (:uri diag)))
      (is (= "Error" (:message diag)))
      (is (= 2 (:severity diag)))
      (is (= 1 (:startLine diag)))
      (is (= 5 (:startChar diag)))
      (is (= 1 (:endLine diag)))
      (is (= 10 (:endChar diag)))
      (is (= 3 (:version diag))))))

(deftest make-diagnostic-defaults-severity
  (testing "make-diagnostic defaults severity to 1"
    (let [diag (entities/make-diagnostic
                {:uri "file:///test.rho"
                 :message "Error"
                 :range {:start {:line 0 :character 0}
                         :end {:line 0 :character 5}}})]
      (is (= 1 (:severity diag))))))

(deftest valid-diagnostic?-validates
  (testing "valid-diagnostic? validates correctly"
    (is (true? (entities/valid-diagnostic?
                {:uri "file:///test.rho"
                 :message "Error"
                 :severity 1
                 :startLine 0
                 :startChar 0
                 :endLine 0
                 :endChar 5})))
    (is (false? (entities/valid-diagnostic?
                 {:message "missing fields"})))))

;; =============================================================================
;; Symbol Kind Tests
;; =============================================================================

(deftest symbol-kinds-contains-all-lsp-kinds
  (testing "symbol-kinds map contains standard LSP symbol kinds"
    (is (= :file (entities/symbol-kind-name 1)))
    (is (= :module (entities/symbol-kind-name 2)))
    (is (= :namespace (entities/symbol-kind-name 3)))
    (is (= :class (entities/symbol-kind-name 5)))
    (is (= :method (entities/symbol-kind-name 6)))
    (is (= :function (entities/symbol-kind-name 12)))
    (is (= :variable (entities/symbol-kind-name 13)))
    (is (= :constant (entities/symbol-kind-name 14)))))

(deftest symbol-kind-name-returns-unknown-for-invalid
  (testing "symbol-kind-name returns :unknown for invalid kinds"
    (is (= :unknown (entities/symbol-kind-name 0)))
    (is (= :unknown (entities/symbol-kind-name 99)))
    (is (= :unknown (entities/symbol-kind-name -1)))))

;; =============================================================================
;; Symbol Entity Tests
;; =============================================================================

(deftest symbol-spec-validates-correct
  (testing "Valid symbol passes spec"
    (let [sym {:uri "file:///test.rho"
               :name "myFunction"
               :kind 12
               :startLine 0
               :startChar 0
               :endLine 10
               :endChar 0
               :selectionStartLine 0
               :selectionStartChar 4
               :selectionEndLine 0
               :selectionEndChar 14}]
      (is (s/valid? ::entities/symbol sym)))))

(deftest symbol-spec-rejects-missing-name
  (testing "Symbol without name fails spec"
    (let [sym {:uri "file:///test.rho"
               :kind 12
               :startLine 0
               :startChar 0
               :endLine 10
               :endChar 0
               :selectionStartLine 0
               :selectionStartChar 4
               :selectionEndLine 0
               :selectionEndChar 14}]
      (is (not (s/valid? ::entities/symbol sym))))))

(deftest symbol-spec-rejects-empty-name
  (testing "Symbol with empty name fails spec"
    (let [sym {:uri "file:///test.rho"
               :name ""
               :kind 12
               :startLine 0
               :startChar 0
               :endLine 10
               :endChar 0
               :selectionStartLine 0
               :selectionStartChar 4
               :selectionEndLine 0
               :selectionEndChar 14}]
      (is (not (s/valid? ::entities/symbol sym))))))

(deftest symbol-spec-allows-optional-parent
  (testing "Symbol with parent passes spec"
    (let [sym {:uri "file:///test.rho"
               :name "childFunc"
               :kind 12
               :startLine 5
               :startChar 2
               :endLine 8
               :endChar 2
               :selectionStartLine 5
               :selectionStartChar 6
               :selectionEndLine 5
               :selectionEndChar 15
               :parent 42}]
      (is (s/valid? ::entities/symbol sym)))))

(deftest make-symbol-creates-from-lsp
  (testing "make-symbol transforms LSP format"
    (let [sym (entities/make-symbol
               {:uri "file:///test.rho"
                :name "myFunc"
                :kind 12
                :range {:start {:line 0 :character 0}
                        :end {:line 10 :character 0}}
                :selectionRange {:start {:line 0 :character 4}
                                 :end {:line 0 :character 10}}
                :parent 5})]
      (is (= "file:///test.rho" (:uri sym)))
      (is (= "myFunc" (:name sym)))
      (is (= 12 (:kind sym)))
      (is (= 0 (:startLine sym)))
      (is (= 0 (:startChar sym)))
      (is (= 10 (:endLine sym)))
      (is (= 0 (:endChar sym)))
      (is (= 0 (:selectionStartLine sym)))
      (is (= 4 (:selectionStartChar sym)))
      (is (= 0 (:selectionEndLine sym)))
      (is (= 10 (:selectionEndChar sym)))
      (is (= 5 (:parent sym))))))

(deftest valid-symbol?-validates
  (testing "valid-symbol? validates correctly"
    (is (true? (entities/valid-symbol?
                {:uri "file:///test.rho"
                 :name "func"
                 :kind 12
                 :startLine 0
                 :startChar 0
                 :endLine 5
                 :endChar 0
                 :selectionStartLine 0
                 :selectionStartChar 4
                 :selectionEndLine 0
                 :selectionEndChar 8})))
    (is (false? (entities/valid-symbol?
                 {:name "missing fields"})))))

;; =============================================================================
;; Log Entry Entity Tests
;; =============================================================================

(deftest log-entry-spec-validates-correct
  (testing "Valid log entry passes spec"
    (is (s/valid? ::entities/log-entry
                  {:message "Log message" :lang "rholang"}))))

(deftest make-log-entry-creates-entry
  (testing "make-log-entry creates log entry"
    (let [log (entities/make-log-entry {:message "Test log" :lang "rholang"})]
      (is (= "Test log" (:message log)))
      (is (= "rholang" (:lang log))))))

;; =============================================================================
;; Language Configuration Entity Tests
;; =============================================================================

(deftest language-config-spec-validates-correct
  (testing "Valid language config passes spec"
    (let [config {:extensions [".rho"]}]
      (is (s/valid? ::entities/language-config config)))))

(deftest language-config-spec-rejects-empty-extensions
  (testing "Language config with empty extensions fails spec"
    (let [config {:extensions []}]
      (is (not (s/valid? ::entities/language-config config))))))

(deftest language-config-spec-allows-optional-fields
  (testing "Language config with optional fields passes spec"
    (let [config {:extensions [".rho"]
                  :grammar-wasm "path/to/wasm"
                  :lsp-url "ws://localhost:8080"
                  :file-icon "fas fa-code"
                  :indent-size 2}]
      (is (s/valid? ::entities/language-config config)))))

(deftest valid-language-config?-validates
  (testing "valid-language-config? validates correctly"
    (is (true? (entities/valid-language-config?
                {:extensions [".rho"]})))
    (is (false? (entities/valid-language-config?
                 {:extensions []})))))

;; =============================================================================
;; LSP State Entity Tests
;; =============================================================================

(deftest lsp-state-spec-validates-correct
  (testing "Valid LSP state passes spec"
    (is (s/valid? ::entities/lsp-state
                  {:ws nil
                   :initialized? false
                   :connected? false
                   :url "ws://localhost:8080"}))))

(deftest make-lsp-state-creates-initial-state
  (testing "make-lsp-state creates initial state"
    (let [state (entities/make-lsp-state {:url "ws://localhost:8080"})]
      (is (nil? (:ws state)))
      (is (false? (:initialized? state)))
      (is (false? (:connected? state)))
      (is (false? (:reachable? state)))
      (is (false? (:connecting? state)))
      (is (= "ws://localhost:8080" (:url state)))
      (is (= 1 (:next-id state)))
      (is (empty? (:pending state))))))

;; =============================================================================
;; Event Entity Tests
;; =============================================================================

(deftest event-spec-validates-correct
  (testing "Valid event passes spec"
    (is (s/valid? ::entities/event {:type "documentChanged"}))
    (is (s/valid? ::entities/event {:type "diagnosticsReceived"
                                    :data {:count 5}
                                    :uri "file:///test.rho"
                                    :lang "rholang"
                                    :version 3}))))

(deftest make-event-creates-event
  (testing "make-event creates event with type"
    (let [evt (entities/make-event {:type "test"})]
      (is (= "test" (:type evt)))))

  (testing "make-event includes optional fields when provided"
    (let [evt (entities/make-event {:type "test"
                                    :data {:key "value"}
                                    :uri "file:///test.rho"
                                    :lang "rholang"
                                    :version 5})]
      (is (= {:key "value"} (:data evt)))
      (is (= "file:///test.rho" (:uri evt)))
      (is (= "rholang" (:lang evt)))
      (is (= 5 (:version evt)))))

  (testing "make-event excludes nil optional fields"
    (let [evt (entities/make-event {:type "test"})]
      (is (not (contains? evt :data)))
      (is (not (contains? evt :uri)))
      (is (not (contains? evt :lang)))
      (is (not (contains? evt :version))))))

;; =============================================================================
;; Property-Based Tests
;; =============================================================================

(def gen-valid-uri
  (gen/fmap #(str "file:///" %)
            (gen/such-that #(pos? (count %)) gen/string-alphanumeric)))

(def gen-valid-document
  (gen/hash-map
   :uri gen-valid-uri
   :text gen/string
   :language (gen/such-that #(pos? (count %)) gen/string-alphanumeric)
   :version gen/nat
   :dirty gen/boolean
   :opened gen/boolean))

(deftest document-spec-property
  (testing "Generated valid documents pass spec"
    (let [prop (prop/for-all [doc gen-valid-document]
                             (s/valid? ::entities/document doc))
          result (tc/quick-check 100 prop {:seed 42})]
      (is (:result result)))))

(def gen-valid-diagnostic
  (gen/hash-map
   :uri gen-valid-uri
   :message (gen/such-that #(pos? (count %)) gen/string-alphanumeric)
   :severity (gen/choose 1 4)
   :startLine gen/nat
   :startChar gen/nat
   :endLine gen/nat
   :endChar gen/nat))

(deftest diagnostic-spec-property
  (testing "Generated valid diagnostics pass spec"
    (let [prop (prop/for-all [diag gen-valid-diagnostic]
                             (s/valid? ::entities/diagnostic diag))
          result (tc/quick-check 100 prop {:seed 42})]
      (is (:result result)))))

(def gen-valid-symbol
  (gen/hash-map
   :uri gen-valid-uri
   :name (gen/such-that #(pos? (count %)) gen/string-alphanumeric)
   :kind (gen/choose 1 26)
   :startLine gen/nat
   :startChar gen/nat
   :endLine gen/nat
   :endChar gen/nat
   :selectionStartLine gen/nat
   :selectionStartChar gen/nat
   :selectionEndLine gen/nat
   :selectionEndChar gen/nat))

(deftest symbol-spec-property
  (testing "Generated valid symbols pass spec"
    (let [prop (prop/for-all [sym gen-valid-symbol]
                             (s/valid? ::entities/symbol sym))
          result (tc/quick-check 100 prop {:seed 42})]
      (is (:result result)))))
