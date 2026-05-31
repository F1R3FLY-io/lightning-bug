(ns test.lib.utils-test
  "Tests for the lib.utils utility module."
  (:require
   [clojure.test :refer [deftest is testing async]]
   [clojure.string :as str]
   [clojure.test.check.generators :as gen]
   [clojure.core.async :refer [go <!]]
   [lib.utils :as utils]))

;; =============================================================================
;; Mock CodeMirror Text Object
;; =============================================================================

(defn make-mock-doc
  "Creates a mock CodeMirror Text object from a string."
  [text]
  (let [lines (clojure.string/split text #"\n" -1)
        line-count (count lines)
        ;; Build line info: each line has from, to, number, length, text
        line-info (loop [idx 0
                         offset 0
                         result []]
                    (if (>= idx line-count)
                      result
                      (let [line-text (nth lines idx)
                            line-length (count line-text)
                            from offset
                            ;; Add 1 for newline unless it's the last line
                            to (+ offset line-length (if (< idx (dec line-count)) 1 0))]
                        (recur (inc idx)
                               to
                               (conj result {:number (inc idx)
                                             :from from
                                             :to to
                                             :length line-length
                                             :text line-text})))))]
    (js-obj
     "lines" line-count
     "length" (count text)
     "lineAt" (fn [offset]
                (let [line (or (first (filter #(and (>= offset (:from %))
                                                    (< offset (:to %)))
                                              line-info))
                               (last line-info))]
                  (clj->js line)))
     "line" (fn [n]
              (when-let [line (nth line-info (dec n) nil)]
                (clj->js line))))))

;; =============================================================================
;; Position Conversion Tests
;; =============================================================================

(deftest offset->pos-zero-based
  (testing "offset->pos with zero-based positions"
    (let [doc (make-mock-doc "hello\nworld\ntest")]
      ;; First line, offset 0 -> line 0, col 0
      (is (= {:line 0 :column 0} (utils/offset->pos doc 0 false)))
      ;; First line, offset 3 -> line 0, col 3
      (is (= {:line 0 :column 3} (utils/offset->pos doc 3 false)))
      ;; Second line start (after newline), offset 6 -> line 1, col 0
      (is (= {:line 1 :column 0} (utils/offset->pos doc 6 false)))
      ;; Second line middle, offset 8 -> line 1, col 2
      (is (= {:line 1 :column 2} (utils/offset->pos doc 8 false)))
      ;; Third line, offset 12 -> line 2, col 0
      (is (= {:line 2 :column 0} (utils/offset->pos doc 12 false))))))

(deftest offset->pos-one-based
  (testing "offset->pos with one-based positions"
    (let [doc (make-mock-doc "hello\nworld")]
      ;; First line, offset 0 -> line 1, col 1
      (is (= {:line 1 :column 1} (utils/offset->pos doc 0 true)))
      ;; First line, offset 3 -> line 1, col 4
      (is (= {:line 1 :column 4} (utils/offset->pos doc 3 true)))
      ;; Second line, offset 6 -> line 2, col 1
      (is (= {:line 2 :column 1} (utils/offset->pos doc 6 true))))))

(deftest pos->offset-zero-based
  (testing "pos->offset with zero-based positions"
    (let [doc (make-mock-doc "hello\nworld\ntest")]
      ;; Line 0, col 0 -> offset 0
      (is (zero? (utils/pos->offset doc {:line 0, :column 0} false)))
      ;; Line 0, col 3 -> offset 3
      (is (= 3 (utils/pos->offset doc {:line 0 :column 3} false)))
      ;; Line 1, col 0 -> offset 6
      (is (= 6 (utils/pos->offset doc {:line 1 :column 0} false)))
      ;; Line 1, col 2 -> offset 8
      (is (= 8 (utils/pos->offset doc {:line 1 :column 2} false)))
      ;; Line 2, col 0 -> offset 12
      (is (= 12 (utils/pos->offset doc {:line 2 :column 0} false))))))

(deftest pos->offset-one-based
  (testing "pos->offset with one-based positions"
    (let [doc (make-mock-doc "hello\nworld")]
      ;; Line 1, col 1 -> offset 0
      (is (zero? (utils/pos->offset doc {:line 1, :column 1} true)))
      ;; Line 1, col 4 -> offset 3
      (is (= 3 (utils/pos->offset doc {:line 1 :column 4} true)))
      ;; Line 2, col 1 -> offset 6
      (is (= 6 (utils/pos->offset doc {:line 2 :column 1} true))))))

(deftest pos->offset-returns-nil-for-invalid
  (testing "pos->offset returns nil for invalid positions"
    (let [doc (make-mock-doc "hello\nworld")]
      ;; Negative line
      (is (nil? (utils/pos->offset doc {:line -1 :column 0} false)))
      ;; Line beyond document
      (is (nil? (utils/pos->offset doc {:line 10 :column 0} false)))
      ;; Column beyond line length
      (is (nil? (utils/pos->offset doc {:line 0 :column 100} false))))))

;; =============================================================================
;; URI Parsing Tests
;; =============================================================================

(deftest split-uri-parses-file-protocol
  (testing "split-uri parses file:// URIs"
    (is (= ["file://" "/path/to/file.txt"]
           (utils/split-uri "file:///path/to/file.txt")))
    (is (= ["file://" "/home/user/code.rho"]
           (utils/split-uri "file:///home/user/code.rho")))))

(deftest split-uri-parses-inmemory-protocol
  (testing "split-uri parses inmemory: URIs"
    (is (= ["inmemory:" "/untitled-1.rho"]
           (utils/split-uri "inmemory:/untitled-1.rho")))
    (is (= ["inmemory:" "untitled.txt"]
           (utils/split-uri "inmemory:untitled.txt")))))

(deftest split-uri-handles-no-protocol
  (testing "split-uri handles URIs without protocol"
    (is (= [nil "/absolute/path.txt"]
           (utils/split-uri "/absolute/path.txt")))
    (is (= [nil "relative/path.txt"]
           (utils/split-uri "relative/path.txt")))))

(deftest split-uri-handles-https-protocol
  (testing "split-uri parses https:// URIs"
    (is (= ["https://" "example.com/file.txt"]
           (utils/split-uri "https://example.com/file.txt")))))

;; =============================================================================
;; Extension and Language Tests
;; =============================================================================

(deftest get-extension-returns-first-extension
  (testing "get-extension returns the first extension for a language"
    (let [db {:languages {"rholang" {:extensions [".rho" ".rhol"]}
                          "text" {:extensions [".txt"]}}}]
      (is (= ".rho" (utils/get-extension db "rholang")))
      (is (= ".txt" (utils/get-extension db "text"))))))

(deftest get-extension-returns-text-for-unknown
  (testing "get-extension returns 'text' for unknown language"
    (let [db {:languages {}}]
      (is (= "text" (utils/get-extension db "unknown"))))))

(deftest get-lang-from-ext-finds-language
  (testing "get-lang-from-ext finds language by extension"
    (let [db {:languages {"rholang" {:extensions [".rho" ".rhol"]}
                          "javascript" {:extensions [".js" ".mjs"]}}
              :default-language "text"}]
      (is (= "rholang" (utils/get-lang-from-ext (:languages db)".rho")))
      (is (= "rholang" (utils/get-lang-from-ext (:languages db)".rhol")))
      (is (= "javascript" (utils/get-lang-from-ext (:languages db)".js"))))))

(deftest get-lang-from-ext-returns-default-for-unknown
  (testing "get-lang-from-ext returns default for unknown extension"
    (let [db {:languages {"rholang" {:extensions [".rho"]}}
              :default-language "text"}]
      (is (= "text" (utils/get-lang-from-ext (:languages db)".xyz"))))))

;; =============================================================================
;; Untitled Name Generation Tests
;; =============================================================================

(deftest new-untitled-name-generates-base-name
  (testing "new-untitled-name generates base name for n=0"
    (let [db {:default-language "rholang"
              :languages {"rholang" {:extensions [".rho"]}}}]
      (is (= "untitled.rho" (utils/new-untitled-name db 0))))))

(deftest new-untitled-name-generates-indexed-name
  (testing "new-untitled-name generates indexed name for n>0"
    (let [db {:default-language "rholang"
              :languages {"rholang" {:extensions [".rho"]}}}]
      (is (= "untitled-1.rho" (utils/new-untitled-name db 1)))
      (is (= "untitled-5.rho" (utils/new-untitled-name db 5)))
      (is (= "untitled-99.rho" (utils/new-untitled-name db 99))))))

;; =============================================================================
;; Promise->Chan Tests
;; =============================================================================

(deftest promise->chan-resolves-to-ok
  (async done
         (go
           (let [p (js/Promise.resolve "success")
                 ch (utils/promise->chan p)
                 result (<! ch)]
             (is (= [:ok "success"] result)))
           (done))))

(deftest promise->chan-rejects-to-error
  (async done
         (go
           (let [p (js/Promise.reject (js/Error. "failure"))
                 ch (utils/promise->chan p)
                 [status _] (<! ch)]
             (is (= :error status)))
           (done))))

(deftest promise->chan-handles-nil-input
  (async done
         (go
           (let [ch (utils/promise->chan nil)
                 [status _] (<! ch)]
             (is (= :error status)))
           (done))))

;; =============================================================================
;; Generate UUID Tests
;; =============================================================================

(deftest generate-uuid-returns-uuid
  (testing "generate-uuid returns a valid UUID"
    (let [uuid (utils/generate-uuid)]
      (is (uuid? uuid)))))

(deftest generate-uuid-returns-unique-values
  (testing "generate-uuid returns unique values"
    (let [uuids (repeatedly 100 utils/generate-uuid)]
      (is (= 100 (count (set uuids)))))))

;; =============================================================================
;; Property-Based Tests
;; =============================================================================

(def gen-simple-text
  "Generator for simple multi-line text (no newline characters in content)."
  (gen/fmap (fn [lines] (clojure.string/join "\n" lines))
            (gen/vector (gen/fmap str/join (gen/vector gen/char-alpha 1 20)) 1 10)))

;; Note: Property tests for offset<->pos roundtrip are complex due to the
;; mock object requirements. The unit tests above provide sufficient coverage.
