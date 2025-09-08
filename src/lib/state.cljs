(ns lib.state
  (:require [clojure.string :as str]
            [clojure.spec.alpha :as s]
            [taoensso.timbre :as log]))

;; Editor configuration specs
(s/def ::tree-sitter-wasm (s/or :string string? :fn fn?))
(s/def ::extra-extensions any?)
(s/def ::default-protocol string?)
(s/def ::on-content-change fn?)
(s/def ::editor-config (s/keys :opt-un [::tree-sitter-wasm ::extra-extensions ::default-protocol ::on-content-change]))

;; Language configuration specs
(s/def ::grammar-wasm (s/or :string string? :fn fn?))
(s/def ::parser (s/or :fn fn? :instance any?))
(s/def ::highlights-query-path (s/or :string string? :fn fn?))
(s/def ::indents-query-path (s/or :string string? :fn fn?))
(s/def ::lsp-url string?)
(s/def ::extensions (s/coll-of string? :min-count 1))
(s/def ::file-icon string?)
(s/def ::fallback-highlighter string?)
(s/def ::indent-size pos-int?)

(s/def ::language-config (s/keys :req-un [::extensions]
                                 :opt-un [::grammar-wasm
                                          ::parser
                                          ::highlights-query-path
                                          ::indents-query-path
                                          ::lsp-url
                                          ::file-icon
                                          ::fallback-highlighter
                                          ::indent-size]))

(s/def ::languages
  (s/map-of (s/or :string string? :keyword keyword?) ::language-config))

;; LSP state specs
(s/def ::ws any?)  ;; js/WebSocket
(s/def ::initialized? boolean?)
(s/def ::connected? boolean?)
(s/def ::reachable? boolean?)
(s/def ::connecting? boolean?)
(s/def ::warned-unreachable? boolean?)
(s/def ::url string?)
(s/def ::next-id pos-int?)
(s/def ::pending (s/map-of any? (s/keys :opt-un [::response-type ::uri])))
(s/def ::response-type keyword?)
(s/def ::uri string?)
(s/def ::promise-res-fn fn?)
(s/def ::promise-rej-fn fn?)

(s/def ::lsp-entry
  (s/keys :opt-un [::ws
                   ::initialized?
                   ::connected?
                   ::reachable?
                   ::connecting?
                   ::warned-unreachable?
                   ::url
                   ::next-id
                   ::pending
                   ::promise-res-fn
                   ::promise-rej-fn]))

(s/def ::lsp (s/map-of string? ::lsp-entry))

(defn kebab-keyword
  "Converts a camelCase keyword to kebab-case keyword.
  e.g., :grammarWasm -> :grammar-wasm"
  [k]
  (let [n (name k)
        kebab (str/replace n #"([a-z])([A-Z])" (fn [[_ a b]] (str a "-" (str/lower-case b))))]
    (keyword kebab)))

(defn convert-config-keys
  "Recursively converts all camelCase keys in a config map to kebab-case keywords."
  [config]
  (into {} (map (fn [[k v]] [(kebab-keyword k) (if (map? v) (convert-config-keys v) v)]) config)))

(defn normalize-editor-config
  "Normalizes editor config by converting camelCase keys to kebab-case.
  Validates the config with spec and throws on invalid."
  [config]
  (let [normalized (convert-config-keys config)]
    (when-not (s/valid? ::editor-config normalized)
      (log/error "Invalid editor config" (s/explain-str ::editor-config normalized))
      (throw (ex-info "Invalid editor config" {:explain (s/explain-str ::editor-config normalized)})))
    normalized))

(defn normalize-languages
  "Ensures all keys in the languages map are strings, converting keywords if necessary.
  Also normalizes inner config keys from camelCase to kebab-case.
  Validates each config with spec and throws on invalid."
  [langs]
  (reduce-kv
   (fn [m k v]
     (let [key-str (if (keyword? k) (name k) (str k))
           config (convert-config-keys v)]
       (when-not (s/valid? ::language-config config)
         (log/error "Invalid language config for" key-str (s/explain-str ::language-config config))
         (throw (ex-info "Invalid language config" {:lang key-str
                                                    :explain (s/explain-str ::language-config config)})))
       (assoc m key-str config)))
   {}
   langs))
