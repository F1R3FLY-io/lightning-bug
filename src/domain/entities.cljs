(ns domain.entities
  "Domain entity definitions with specs for validation.

   These entities represent the core domain concepts independent of
   their storage mechanism or presentation layer."
  (:require [clojure.spec.alpha :as s]))

;; =============================================================================
;; Document Entity
;; =============================================================================

(s/def ::uri (s/and string? #(pos? (count %))))
(s/def ::text string?)
(s/def ::language (s/and string? #(pos? (count %))))
(s/def ::version nat-int?)
(s/def ::dirty boolean?)
(s/def ::opened boolean?)

(s/def ::document
  (s/keys :req-un [::uri ::text ::language ::version ::dirty ::opened]))

(defn make-document
  "Creates a document entity with defaults."
  [{:keys [uri text language version dirty opened]
    :or {text ""
         language "text"
         version 0
         dirty false
         opened false}}]
  {:uri uri
   :text text
   :language language
   :version version
   :dirty dirty
   :opened opened})

(defn valid-document?
  "Returns true if the document is valid according to spec."
  [doc]
  (s/valid? ::document doc))

;; =============================================================================
;; Position Entity
;; =============================================================================

(s/def ::line nat-int?)
(s/def ::char nat-int?)
(s/def ::column nat-int?)

(s/def ::position
  (s/keys :req-un [::line]
          :opt-un [::char ::column]))

(defn make-position
  "Creates a position entity. Accepts either :char or :column for the character offset."
  [{:keys [line char column]}]
  {:line line
   :char (or char column 0)})

;; =============================================================================
;; Range Entity
;; =============================================================================

(s/def ::start ::position)
(s/def ::end ::position)

(s/def ::range
  (s/keys :req-un [::start ::end]))

(defn make-range
  "Creates a range entity from start and end positions."
  [start end]
  {:start (make-position start)
   :end (make-position end)})

;; =============================================================================
;; Diagnostic Entity
;; =============================================================================

(s/def ::message (s/and string? #(pos? (count %))))
(s/def ::severity (s/and pos-int? #(<= % 4))) ; 1=error, 2=warning, 3=info, 4=hint

(s/def :diagnostic/startLine nat-int?)
(s/def :diagnostic/startChar nat-int?)
(s/def :diagnostic/endLine nat-int?)
(s/def :diagnostic/endChar nat-int?)

(s/def ::diagnostic
  (s/keys :req-un [::uri ::message ::severity
                   :diagnostic/startLine :diagnostic/startChar
                   :diagnostic/endLine :diagnostic/endChar]
          :opt-un [::version]))

(defn make-diagnostic
  "Creates a diagnostic entity from LSP diagnostic data."
  [{:keys [uri message severity range version]
    :or {severity 1}}]
  (let [start (get range :start {})
        end (get range :end {})]
    (cond-> {:uri uri
             :message message
             :severity severity
             :startLine (get start :line 0)
             :startChar (get start :character 0)
             :endLine (get end :line 0)
             :endChar (get end :character 0)}
      version (assoc :version version))))

(defn valid-diagnostic?
  "Returns true if the diagnostic is valid according to spec."
  [diag]
  (s/valid? ::diagnostic diag))

;; =============================================================================
;; Symbol Kind Enumeration
;; =============================================================================

(def symbol-kinds
  "LSP Symbol Kinds as defined in the protocol."
  {1  :file
   2  :module
   3  :namespace
   4  :package
   5  :class
   6  :method
   7  :property
   8  :field
   9  :constructor
   10 :enum
   11 :interface
   12 :function
   13 :variable
   14 :constant
   15 :string
   16 :number
   17 :boolean
   18 :array
   19 :object
   20 :key
   21 :null
   22 :enum-member
   23 :struct
   24 :event
   25 :operator
   26 :type-parameter})

(defn symbol-kind-name
  "Returns the keyword name for a symbol kind number."
  [kind]
  (get symbol-kinds kind :unknown))

;; =============================================================================
;; Symbol Entity
;; =============================================================================

(s/def ::name (s/and string? #(pos? (count %))))
(s/def ::kind pos-int?)
(s/def ::parent (s/nilable integer?))

(s/def :symbol/startLine nat-int?)
(s/def :symbol/startChar nat-int?)
(s/def :symbol/endLine nat-int?)
(s/def :symbol/endChar nat-int?)
(s/def :symbol/selectionStartLine nat-int?)
(s/def :symbol/selectionStartChar nat-int?)
(s/def :symbol/selectionEndLine nat-int?)
(s/def :symbol/selectionEndChar nat-int?)

(s/def ::symbol
  (s/keys :req-un [::uri ::name ::kind
                   :symbol/startLine :symbol/startChar
                   :symbol/endLine :symbol/endChar
                   :symbol/selectionStartLine :symbol/selectionStartChar
                   :symbol/selectionEndLine :symbol/selectionEndChar]
          :opt-un [::parent]))

(defn make-symbol
  "Creates a symbol entity from LSP symbol data."
  [{:keys [uri name kind range selectionRange parent]}]
  (let [r-start (get range :start {})
        r-end (get range :end {})
        sel-start (get selectionRange :start {})
        sel-end (get selectionRange :end {})]
    (cond-> {:uri uri
             :name name
             :kind kind
             :startLine (get r-start :line 0)
             :startChar (get r-start :character 0)
             :endLine (get r-end :line 0)
             :endChar (get r-end :character 0)
             :selectionStartLine (get sel-start :line 0)
             :selectionStartChar (get sel-start :character 0)
             :selectionEndLine (get sel-end :line 0)
             :selectionEndChar (get sel-end :character 0)}
      parent (assoc :parent parent))))

(defn valid-symbol?
  "Returns true if the symbol is valid according to spec."
  [sym]
  (s/valid? ::symbol sym))

;; =============================================================================
;; Log Entry Entity
;; =============================================================================

(s/def :log/message string?)
(s/def :log/lang string?)

(s/def ::log-entry
  (s/keys :req-un [:log/message :log/lang]))

(defn make-log-entry
  "Creates a log entry."
  [{:keys [message lang]}]
  {:message message
   :lang lang})

;; =============================================================================
;; Language Configuration Entity
;; =============================================================================

(s/def ::extensions (s/coll-of string? :min-count 1))
(s/def ::grammar-wasm (s/or :string string? :fn fn?))
(s/def ::parser (s/or :fn fn? :instance any?))
(s/def ::highlights-query-path (s/or :string string? :fn fn?))
(s/def ::highlights-query string?)
(s/def ::indents-query-path (s/or :string string? :fn fn?))
(s/def ::indents-query string?)
(s/def ::lsp-url string?)
(s/def ::file-icon string?)
(s/def ::fallback-highlighter string?)
(s/def ::indent-size pos-int?)

(s/def ::language-config
  (s/keys :req-un [::extensions]
          :opt-un [::grammar-wasm
                   ::parser
                   ::highlights-query-path
                   ::highlights-query
                   ::indents-query-path
                   ::indents-query
                   ::lsp-url
                   ::file-icon
                   ::fallback-highlighter
                   ::indent-size]))

(defn valid-language-config?
  "Returns true if the language config is valid according to spec."
  [config]
  (s/valid? ::language-config config))

;; =============================================================================
;; LSP Connection State
;; =============================================================================

(s/def ::ws any?) ; WebSocket instance
(s/def ::initialized? boolean?)
(s/def ::connected? boolean?)
(s/def ::reachable? boolean?)
(s/def ::connecting? boolean?)
(s/def ::url string?)
(s/def ::next-id pos-int?)
(s/def ::pending map?)

(s/def ::lsp-state
  (s/keys :opt-un [::ws
                   ::initialized?
                   ::connected?
                   ::reachable?
                   ::connecting?
                   ::url
                   ::next-id
                   ::pending]))

(defn make-lsp-state
  "Creates an initial LSP connection state."
  [{:keys [url]}]
  {:ws nil
   :initialized? false
   :connected? false
   :reachable? false
   :connecting? false
   :url url
   :next-id 1
   :pending {}})

;; =============================================================================
;; Event Entity
;; =============================================================================

(s/def ::type string?)
(s/def ::data map?)
(s/def :event/uri (s/nilable string?))
(s/def :event/lang (s/nilable string?))
(s/def :event/version (s/nilable nat-int?))

(s/def ::event
  (s/keys :req-un [::type]
          :opt-un [::data :event/uri :event/lang :event/version]))

(defn make-event
  "Creates an event entity."
  [{:keys [type data uri lang version]}]
  (cond-> {:type type}
    data (assoc :data data)
    uri (assoc :uri uri)
    lang (assoc :lang lang)
    version (assoc :version version)))
