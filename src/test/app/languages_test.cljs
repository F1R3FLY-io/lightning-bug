(ns test.app.languages-test
  "Tests for app.languages — the demo app's language registry, key coercion,
   and config validation (previously untested)."
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [app.languages :as languages]))

(use-fixtures :each
  {:before (fn []
             (reset! languages/registry {})
             (reset! languages/default-lang nil))})

;; =============================================================================
;; set-default-lang
;; =============================================================================

(deftest set-default-lang-stores-string
  (testing "stores a string key verbatim"
    (languages/set-default-lang "rholang")
    (is (= "rholang" @languages/default-lang))))

(deftest set-default-lang-coerces-keyword
  (testing "coerces a keyword key to its name string"
    (languages/set-default-lang :rholang)
    (is (= "rholang" @languages/default-lang))))

;; =============================================================================
;; register-language
;; =============================================================================

(deftest register-language-stores-valid-config
  (testing "registers a valid config under a string key"
    (languages/register-language "rholang" {:extensions [".rho"]})
    (is (= {:extensions [".rho"]} (get @languages/registry "rholang")))))

(deftest register-language-coerces-keyword-key
  (testing "a keyword key is coerced to its name string (no keyword lookups)"
    (languages/register-language :rholang {:extensions [".rho"]})
    (is (contains? @languages/registry "rholang"))
    (is (not (contains? @languages/registry :rholang)))))

(deftest register-language-accepts-optional-fields
  (testing "optional config fields validate"
    (languages/register-language "rholang"
                                 {:extensions [".rho"]
                                  :lsp-url "ws://localhost:41551"
                                  :indent-size 2
                                  :fallback-highlighter "none"})
    (is (= 2 (:indent-size (get @languages/registry "rholang"))))))

(deftest register-language-rejects-invalid-config
  (testing "missing required :extensions throws"
    (is (thrown? js/Error (languages/register-language "bad" {}))))
  (testing "empty :extensions throws (min-count 1)"
    (is (thrown? js/Error (languages/register-language "bad" {:extensions []}))))
  (testing "non-positive :indent-size throws"
    (is (thrown? js/Error (languages/register-language "bad" {:extensions [".x"] :indent-size 0})))))
