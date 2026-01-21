(ns lib.editor.diagnostics
  "Diagnostic display for LSP diagnostics in CodeMirror.

   Provides:
   - StateField for holding diagnostics
   - Memoized diagnostic transformation for performance
   - Linter integration with CodeMirror lint gutter"
  (:require
   ["@codemirror/lint" :refer [lintGutter linter]]
   ["@codemirror/state" :refer [StateField StateEffect]]
   [lib.utils :as lib-utils]
   [taoensso.timbre :as log]))

;; =============================================================================
;; StateEffect and StateField
;; =============================================================================

;; StateEffect to set new diagnostics in the field.
(def set-diagnostic-effect (.define StateEffect))

;; StateField to hold the current list of LSP diagnostics (array).
(def diagnostic-field
  (.define StateField
           #js {:create (fn [_] #js [])
                :update (fn [^js value
                             ^js tr]
                          (reduce (fn [v ^js e]
                                    (if (.is e set-diagnostic-effect)
                                      (.-value e)
                                      v))
                                  value (.-effects tr)))}))

;; =============================================================================
;; Memoization for Diagnostic Transformation
;; =============================================================================

;; Cache for transformed diagnostics to avoid redundant transformations.
;; Structure: {:input-hash hash :doc-length length :output transformed-diags}
(defonce ^:private diagnostic-cache
  (atom {:input-hash nil :doc-length nil :output nil}))

(defn- compute-diagnostic-hash
  "Computes a hash of diagnostics array for cache key comparison."
  [^js diags]
  (if (or (nil? diags) (zero? (.-length diags)))
    0
    ;; Hash based on diagnostics count and first/last diagnostic details
    (let [^js first-diag (aget diags 0)
          ^js last-diag (aget diags (dec (.-length diags)))]
      (hash [(.-length diags)
             (when first-diag
               [(.-startLine first-diag) (.-startChar first-diag)
                (.-message first-diag)])
             (when last-diag
               [(.-startLine last-diag) (.-startChar last-diag)
                (.-message last-diag)])]))))

(defn severity-class
  "Maps LSP diagnostic severity to a CSS class for underlining.
  - 1: Error (red wavy)
  - 2: Warning (orange wavy)
  - 3: Information (blue dotted)
  - 4: Hint (gray dotted)"
  [severity]
  (case severity
    1 "error"
    2 "warning"
    3 "info"
    4 "hint"
    ""))

(defn- transform-diagnostic
  "Transforms a single diagnostic to CodeMirror lint format."
  [^js doc ^js diag]
  (let [from-pos {:line (inc (.-startLine diag))
                  :column (inc (.-startChar diag))}
        to-pos {:line (inc (.-endLine diag))
                :column (inc (.-endChar diag))}
        from-offset (lib-utils/pos->offset doc from-pos true)
        to-offset (lib-utils/pos->offset doc to-pos true)]
    ;; Only return valid diagnostics with valid offsets
    (when (and from-offset to-offset)
      #js {:from from-offset
           :to to-offset
           :severity (severity-class (.-severity diag))
           :message (.-message diag)})))

(defn- transform-diagnostics
  "Transforms LSP diagnostics to CodeMirror lint format."
  [^js doc ^js diags]
  (let [result #js []]
    (dotimes [i (.-length diags)]
      (when-let [transformed (transform-diagnostic doc (aget diags i))]
        (.push result transformed)))
    result))

(defn- memoized-transform-diagnostics
  "Memoized version of transform-diagnostics.
   Returns cached result if input hasn't changed."
  [^js doc ^js diags]
  (let [input-hash (compute-diagnostic-hash diags)
        doc-length (.-length doc)
        cached @diagnostic-cache]
    (if (and (= input-hash (:input-hash cached))
             (= doc-length (:doc-length cached)))
      (do
        (log/trace "Diagnostic cache hit")
        (:output cached))
      (let [output (transform-diagnostics doc diags)]
        (log/trace "Diagnostic cache miss, transforming" (.-length diags) "diagnostics")
        (reset! diagnostic-cache {:input-hash input-hash
                                  :doc-length doc-length
                                  :output output})
        output))))

;; =============================================================================
;; Linter Integration
;; =============================================================================

(def diagnostic-lint
  (linter (fn [view]
            (let [diags (.field ^js (.-state view) diagnostic-field false)
                  doc (.-doc ^js (.-state view))]
              (memoized-transform-diagnostics doc diags)))))

;; =============================================================================
;; Exported Extensions
;; =============================================================================

(def extensions #js [diagnostic-field diagnostic-lint (lintGutter)])

;; =============================================================================
;; Cache Management
;; =============================================================================

(defn clear-cache!
  "Clears the diagnostic transformation cache."
  []
  (reset! diagnostic-cache {:input-hash nil :doc-length nil :output nil}))
