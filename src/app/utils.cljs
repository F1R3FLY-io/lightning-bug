(ns app.utils
  (:require
   [clojure.string :as str]
   [taoensso.timbre :as log]
   [app.editor.state :as es]))

;; Generates a unique UUID using native function.
(defn generate-uuid []
  (random-uuid))

;; Converts LSP position (0-based line/character) to CodeMirror offset.
(defn pos-to-offset [doc pos]
  (let [line (inc (:line pos))]
    (+ (.-from (.line doc line)) (:character pos))))

;; Navigates the CodeMirror editor view to the given LSP range.
(defn navigate-to [location]
  (when-let [view @es/editor-view]
    (let [doc (.-doc (.-state view))
          from (pos-to-offset doc (:start location))
          to (pos-to-offset doc (:end location))]
      (.dispatch view #js {:selection #js {:anchor from :head to}})
      (.focus view)
      (log/debug "Navigated to location:" location))))

;; Returns Hiccup for an icon based on type (diag, file, symbol); uses standard LSP kinds for symbols.
(defn icon [{:keys [type severity lang kind style]}]
  (let [icon-class (case type
                     :diag (case severity
                             :error "fas fa-exclamation-triangle text-danger"
                             :warning "fas fa-exclamation-circle text-warning"
                             :info "fas fa-info-circle text-info"
                             "fas fa-question-circle")
                     :file (case lang
                             "rholang" "fas fa-code text-primary"
                             "fas fa-file")
                     :symbol (case kind  ; Based on standard LSP SymbolKind (1=File, 2=Module, etc.)
                               1 "fas fa-file"
                               2 "fas fa-cube"
                               3 "fas fa-sitemap"  ; Namespace
                               4 "fas fa-archive"  ; Package
                               5 "fas fa-layer-group"
                               6 "fas fa-tools"  ; Method
                               7 "fas fa-cog"  ; Property
                               8 "fas fa-stream"  ; Field
                               9 "fas fa-hammer"  ; Constructor
                               10 "fas fa-list"  ; Enum
                               11 "fas fa-plug"  ; Interface
                               12 "fas fa-cogs"  ; Function
                               13 "fas fa-tag"  ; Variable
                               14 "fas fa-hashtag"  ; Constant
                               15 "fas fa-font"  ; String
                               16 "fas fa-sort-numeric-up"  ; Number
                               17 "fas fa-check-square"  ; Boolean
                               18 "fas fa-list-ol"  ; Array
                               19 "fas fa-box" ; Object
                               20 "fas fa-key"  ; Key
                               21 "fas fa-ban" ; Null
                               22 "fas fa-tags" ; EnumMember
                               23 "fas fa-door-open" ; Struct
                               24 "fas fa-bell" ; Event
                               25 "fas fa-calculator" ; Operator
                               26 "fas fa-wrench" ; TypeParameter
                               "fas fa-question")
                     "fas fa-question")]  ; Default
    [:i {:class icon-class :style (merge {:margin-right "5px"} style)}]))

;; Returns CSS class for styling log messages based on level.
(defn log-class [level]
  (case level
    :error "log-error text-danger"
    :info "log-info text-info"
    "text-secondary"))

;; Highlights occurrences of term in text; returns a sequence of Hiccup children (strings or spans) for Reagent rendering. Handles case-insensitivity and multiple matches.
(defn highlight-text [text term]
  (if (or (empty? term) (not (str/includes? (str/lower-case text) (str/lower-case term))))
    (list text)  ; Seq of single string for consistency.
    (let [re (js/RegExp. (js/RegExp.escape term) "ig")
          result (transient [])]
      (loop [last 0]
        (if-let [match (.exec re text)]
          (do
            (when (> (.-index match) last)
              (conj! result (subs text last (.-index match))))
            (conj! result [:span {:style {:background "yellow" :color "black"}} (aget match 0)])
            (recur (+ (.-index match) (.-length (aget match 0)))))
          (persistent! (if (< last (.-length text))
                         (doto result (conj! (subs text last)))
                         result)))))))
