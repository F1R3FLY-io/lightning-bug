(ns app.languages
  (:require
   [clojure.spec.alpha :as s]
   [taoensso.timbre :as log]))

(def registry (atom {}))

;; Atom to hold the configured default language key (string).
;; If set to a key not in the registry at default-db init, falls back to the first registered or "text".
(defonce default-lang (atom nil))

(s/def ::grammar-wasm string?)
(s/def ::highlights-query-path string?)
(s/def ::indents-query-path string?)
(s/def ::lsp-url string?)
(s/def ::extensions (s/coll-of string? :min-count 1))
(s/def ::file-icon string?)
(s/def ::fallback-highlighter string?)
(s/def ::indent-size pos-int?)

(s/def ::language-config (s/keys :req-un [::extensions]
                                 :opt-un [::grammar-wasm
                                          ::highlights-query-path
                                          ::indents-query-path
                                          ::lsp-url
                                          ::file-icon
                                          ::fallback-highlighter
                                          ::indent-size]))

(s/def ::lang-key string?)

(defn set-default-lang
  "Sets the default language key. Should be called before app initialization.
  Ensures the key is a string."
  [lang-key]
  (when-not (string? lang-key)
    (log/warn "Attempted to set default-lang with non-string key:" lang-key))
  (let [lang-key-str (if (keyword? lang-key) (name lang-key) lang-key)]
    (reset! default-lang lang-key-str)
    (log/info "Set default-lang to:" lang-key-str)))

(defn register-language
  "Registers a language with the given key and configuration.
  Ensures the key is a string to prevent keyword-based lookups."
  [lang-key config]
  (when-not (string? lang-key)
    (log/warn "Attempted to register language with non-string key:" lang-key))
  (let [lang-key-str (if (keyword? lang-key) (name lang-key) lang-key)]
    (when-not (s/valid? ::language-config config)
      (throw (ex-info "Invalid language config" (s/explain-data ::language-config config))))
    (swap! registry assoc lang-key-str config)
    (log/debug "Registered language:" lang-key-str)))
