(ns app.languages
  (:require
   [taoensso.timbre :as log]
   [clojure.spec.alpha :as s]))

(def registry (atom {}))

(s/def ::grammar-wasm string?)
(s/def ::highlight-query-path string?)
(s/def ::lsp-url string?)
(s/def ::lsp-method string?)
(s/def ::extensions (s/coll-of string? :min-count 1))
(s/def ::file-icon string?)
(s/def ::fallback-highlighter string?)
(s/def ::indent-size pos-int?)

(s/def ::config (s/keys :req-un [::extensions]
                        :opt-un [::grammar-wasm
                                 ::highlight-query-path
                                 ::lsp-url
                                 ::lsp-method
                                 ::file-icon
                                 ::fallback-highlighter
                                 ::indent-size]))

(s/def ::lang-key string?)

(defn register-language
  "Registers a language with the given key and configuration.
  Ensures the key is a string to prevent keyword-based lookups."
  [lang-key config]
  (when-not (string? lang-key)
    (log/warn "Attempted to register language with non-string key:" lang-key))
  (let [lang-key-str (if (keyword? lang-key) (name lang-key) lang-key)]
    (when-not (s/valid? ::config config)
      (throw (ex-info "Invalid language config" (s/explain-data ::config config))))
    (swap! registry assoc lang-key-str config)
    (log/debug "Registered language:" lang-key-str)))
