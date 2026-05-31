(ns test.lib.position-property-test
  "Property-based tests for position/offset conversions.

   These tests verify that position and offset conversion functions
   maintain their invariants across a wide range of inputs including
   edge cases like Unicode, tabs, and different line endings."
  (:require
   [clojure.test :refer [deftest is testing]]
   [clojure.string :as str]
   [clojure.test.check :as tc]
   [clojure.test.check.generators :as gen]
   [clojure.test.check.properties :as prop]
   [lib.utils :as utils]
   ["@codemirror/state" :refer [Text]]))

;; =============================================================================
;; Generators
;; =============================================================================

(def gen-simple-line
  "Generator for simple text lines without special characters."
  (gen/fmap str/join
            (gen/vector gen/char-alphanumeric 0 80)))

(def gen-line-with-spaces
  "Generator for lines with mixed spaces and characters."
  (gen/fmap str/join
            (gen/vector (gen/one-of [gen/char-alphanumeric
                                     (gen/return \space)
                                     (gen/return \tab)])
                        0 80)))

(def gen-unicode-line
  "Generator for lines with Unicode characters."
  (gen/fmap str/join
            (gen/vector (gen/one-of [gen/char-alphanumeric
                                     gen/char-ascii
                                     ;; Add some common Unicode chars
                                     (gen/elements [\u00e9 \u00f1 \u00fc  ; accented chars
                                                    \u4e2d \u6587         ; Chinese chars
                                                    \u03b1 \u03b2 \u03b3  ; Greek letters
                                                    \u2192 \u2190 \u2191  ; arrows
                                                    \u221e \u2200 \u2203  ; math symbols
                                                    ])])
                        0 40)))

(def gen-multiline-document
  "Generator for multi-line documents."
  (gen/fmap (fn [lines] (str/join "\n" lines))
            (gen/vector gen-simple-line 1 20)))

(def gen-unicode-document
  "Generator for documents with Unicode content."
  (gen/fmap (fn [lines] (str/join "\n" lines))
            (gen/vector gen-unicode-line 1 10)))

(def gen-tabbed-document
  "Generator for documents with tabs."
  (gen/fmap (fn [lines] (str/join "\n" lines))
            (gen/vector gen-line-with-spaces 1 10)))

(defn gen-valid-offset-for-doc
  "Generator for valid offsets within a document."
  [doc-str]
  (let [max-offset (count doc-str)]
    (gen/choose 0 max-offset)))

(defn gen-valid-position-for-doc
  "Generator for valid positions within a document."
  [doc-str]
  (let [lines (str/split doc-str #"\n" -1)
        num-lines (count lines)]
    (gen/bind (gen/choose 0 (dec num-lines))
              (fn [line]
                (let [line-len (count (nth lines line))]
                  (gen/fmap (fn [col] {:line line :column col})
                            (gen/choose 0 line-len)))))))

;; =============================================================================
;; Helper Functions
;; =============================================================================

(defn make-text-doc
  "Creates a CodeMirror Text document from a string.
   CodeMirror's Text.of expects an array of lines, so we split by newlines."
  [s]
  (.of Text (clj->js (str/split (or s "") #"\n" -1))))

(defn roundtrip-offset->pos->offset
  "Tests roundtrip conversion: offset -> position -> offset."
  [doc-str offset one-based?]
  (when (and (>= offset 0) (<= offset (count doc-str)))
    (let [doc (make-text-doc doc-str)
          pos (utils/offset->pos doc offset one-based?)
          recovered (utils/pos->offset doc pos one-based?)]
      recovered)))

(defn roundtrip-pos->offset->pos
  "Tests roundtrip conversion: position -> offset -> position."
  [doc-str pos one-based?]
  (let [doc (make-text-doc doc-str)
        offset (utils/pos->offset doc pos one-based?)]
    (when offset
      (utils/offset->pos doc offset one-based?))))

;; =============================================================================
;; Property-Based Tests
;; =============================================================================

(deftest position-offset-roundtrip-property
  (testing "offset->pos->offset roundtrip preserves offset (0-based)"
    (let [prop (prop/for-all
                [doc-str gen-multiline-document]
                (let [max-offset (count doc-str)]
                  (every?
                   (fn
                    [offset]
                    (let [recovered (roundtrip-offset->pos->offset
                                     doc-str
                                     offset
                                     false)]
                      (= offset recovered)))
                   (range 0 (inc (min max-offset 100))))))
          result (tc/quick-check 50 prop)]
      (is (:pass? result) (str "Roundtrip failed: " (:shrunk result)))))

  (testing "offset->pos->offset roundtrip preserves offset (1-based)"
    (let [prop (prop/for-all
                [doc-str gen-multiline-document]
                (let [max-offset (count doc-str)]
                  (every?
                   (fn
                    [offset]
                    (let [recovered (roundtrip-offset->pos->offset doc-str offset true)]
                      (= offset recovered)))
                   (range 0 (inc (min max-offset 100))))))
          result (tc/quick-check 50 prop)]
      (is (:pass? result) (str "Roundtrip failed: " (:shrunk result))))))

(deftest position-offset-unicode-property
  (testing "Unicode characters are handled correctly in position conversion"
    (let [prop (prop/for-all
                [doc-str gen-unicode-document]
                (let [max-offset (count doc-str)]
                  (every?
                   (fn
                    [offset]
                    (let [recovered (roundtrip-offset->pos->offset
                                     doc-str
                                     offset
                                     false)]
                      (= offset recovered)))
                   (range 0 (inc (min max-offset 50))))))
          result (tc/quick-check 30 prop)]
      (is (:pass? result) (str "Unicode roundtrip failed: " (:shrunk result))))))

(deftest position-offset-tabs-property
  (testing "Tab characters are handled correctly in position conversion"
    (let [prop (prop/for-all
                [doc-str gen-tabbed-document]
                (let [max-offset (count doc-str)]
                  (every?
                   (fn
                    [offset]
                    (let [recovered (roundtrip-offset->pos->offset
                                     doc-str
                                     offset
                                     false)]
                      (= offset recovered)))
                   (range 0 (inc (min max-offset 50))))))
          result (tc/quick-check 30 prop)]
      (is (:pass? result) (str "Tab roundtrip failed: " (:shrunk result))))))

(deftest position-conversion-determinism-property
  (testing "Same input always produces same output"
    (let [prop (prop/for-all
                [doc-str gen-multiline-document offset (gen/choose 0 100)]
                (let [doc (make-text-doc doc-str)
                      effective-offset (min offset (count doc-str))
                      pos1 (utils/offset->pos doc effective-offset false)
                      pos2 (utils/offset->pos doc effective-offset false)]
                  (= pos1 pos2)))
          result (tc/quick-check 100 prop)]
      (is (:pass? result) (str "Determinism failed: " (:shrunk result))))))

(deftest position-bounds-property
  (testing "Position line and column are within valid bounds"
    (let [prop (prop/for-all [doc-str gen-multiline-document]
                 (let [doc (make-text-doc doc-str)
                       lines (str/split doc-str #"\n" -1)
                       num-lines (count lines)]
                   (every? (fn [offset]
                             (let [pos (utils/offset->pos doc offset false)]
                               (and (>= (:line pos) 0)
                                    (< (:line pos) num-lines)
                                    (>= (:column pos) 0)
                                    (<= (:column pos) (count (nth lines (:line pos)))))))
                           (range 0 (inc (min (count doc-str) 50))))))
          result (tc/quick-check 50 prop)]
      (is (:pass? result)
          (str "Bounds check failed: " (:shrunk result))))))

(deftest offset-bounds-property
  (testing "Offset is within valid document bounds"
    (let [prop (prop/for-all [doc-str gen-multiline-document]
                 (let [doc (make-text-doc doc-str)
                       lines (str/split doc-str #"\n" -1)]
                   (every? (fn [line-idx]
                             (every? (fn [col]
                                       (let [pos {:line line-idx :column col}
                                             offset (utils/pos->offset doc pos false)]
                                         (or (nil? offset)  ; Invalid position returns nil
                                             (and (>= offset 0)
                                                  (<= offset (count doc-str))))))
                                     (range 0 (inc (count (nth lines line-idx))))))
                           (range 0 (count lines)))))
          result (tc/quick-check 50 prop)]
      (is (:pass? result)
          (str "Offset bounds failed: " (:shrunk result))))))

;; =============================================================================
;; Edge Case Unit Tests
;; =============================================================================

(deftest empty-document-position-test
  (testing "Position conversion on empty document"
    (let [doc (make-text-doc "")]
      (is (= {:line 0 :column 0} (utils/offset->pos doc 0 false)))
      (is (zero? (utils/pos->offset doc {:line 0, :column 0} false))))))

(deftest single-line-document-position-test
  (testing "Position conversion on single-line document"
    (let [doc (make-text-doc "hello")]
      ;; Beginning
      (is (= {:line 0 :column 0} (utils/offset->pos doc 0 false)))
      (is (zero? (utils/pos->offset doc {:line 0, :column 0} false)))
      ;; Middle
      (is (= {:line 0 :column 2} (utils/offset->pos doc 2 false)))
      (is (= 2 (utils/pos->offset doc {:line 0 :column 2} false)))
      ;; End
      (is (= {:line 0 :column 5} (utils/offset->pos doc 5 false)))
      (is (= 5 (utils/pos->offset doc {:line 0 :column 5} false))))))

(deftest multiline-document-position-test
  (testing "Position conversion on multi-line document"
    (let [doc (make-text-doc "line1\nline2\nline3")]
      ;; First line
      (is (= {:line 0 :column 0} (utils/offset->pos doc 0 false)))
      ;; After first newline (start of line2)
      (is (= {:line 1 :column 0} (utils/offset->pos doc 6 false)))
      ;; Middle of line2
      (is (= {:line 1 :column 2} (utils/offset->pos doc 8 false)))
      ;; Start of line3
      (is (= {:line 2 :column 0} (utils/offset->pos doc 12 false))))))

(deftest one-based-vs-zero-based-test
  (testing "1-based vs 0-based indexing"
    (let [doc (make-text-doc "line1\nline2")]
      ;; 0-based
      (is (= {:line 0 :column 0} (utils/offset->pos doc 0 false)))
      (is (= {:line 1 :column 0} (utils/offset->pos doc 6 false)))
      ;; 1-based
      (is (= {:line 1 :column 1} (utils/offset->pos doc 0 true)))
      (is (= {:line 2 :column 1} (utils/offset->pos doc 6 true))))))

(deftest unicode-character-position-test
  (testing "Position conversion with Unicode characters"
    ;; Note: CodeMirror's Text uses UTF-16 code units
    (let [doc (make-text-doc "hello\u4e2d\u6587world")]
      ;; 'hello' is 5 chars
      (is (= {:line 0 :column 5} (utils/offset->pos doc 5 false)))
      ;; Unicode chars count as their UTF-16 length
      (is (= {:line 0 :column 7} (utils/offset->pos doc 7 false))))))

(deftest empty-lines-position-test
  (testing "Position conversion with empty lines"
    (let [doc (make-text-doc "line1\n\nline3")]
      ;; Empty line (line index 1)
      (is (= {:line 1 :column 0} (utils/offset->pos doc 6 false)))
      ;; After empty line
      (is (= {:line 2 :column 0} (utils/offset->pos doc 7 false))))))

(deftest invalid-position-returns-nil-test
  (testing "Invalid positions return nil"
    (let [doc (make-text-doc "line1\nline2")]
      ;; Line out of bounds
      (is (nil? (utils/pos->offset doc {:line 5 :column 0} false)))
      ;; Column out of bounds
      (is (nil? (utils/pos->offset doc {:line 0 :column 100} false)))
      ;; Negative line (treated as 1 in 1-based)
      (is (nil? (utils/pos->offset doc {:line -1 :column 0} false))))))

;; =============================================================================
;; Stress Tests
;; =============================================================================

(deftest large-document-position-test
  (testing "Position conversion on large document"
    (let [large-doc (str/join (repeat 1000 "This is a line of text.\n"))
          doc (make-text-doc large-doc)]
      ;; Test several positions throughout the document
      (doseq [offset [0 100 500 1000 5000 10000]]
        (when (<= offset (count large-doc))
          (let [pos (utils/offset->pos doc offset false)
                recovered (utils/pos->offset doc pos false)]
            (is (= offset recovered)
                (str "Large doc roundtrip failed at offset " offset))))))))

(deftest many-lines-position-test
  (testing "Position conversion on document with many lines"
    (let [many-lines (clojure.string/join "\n" (map #(str "line " %) (range 500)))
          doc (make-text-doc many-lines)]
      ;; Test positions on various lines
      (doseq [line-idx [0 10 50 100 250 499]]
        (let [pos {:line line-idx :column 0}
              offset (utils/pos->offset doc pos false)]
          (when offset
            (let [recovered (utils/offset->pos doc offset false)]
              (is (= line-idx (:line recovered))
                  (str "Many lines roundtrip failed at line " line-idx)))))))))
