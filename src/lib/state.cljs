(ns lib.state
  (:require [clojure.string :as str]
            [clojure.spec.alpha :as s]
            [taoensso.timbre :as log]))

;; Language configuration specs
(s/def ::grammar-wasm string?)
(s/def ::highlight-query-path string?)
(s/def ::indents-query-path string?)
(s/def ::lsp-url string?)
(s/def ::extensions (s/coll-of string? :min-count 1))
(s/def ::file-icon string?)
(s/def ::fallback-highlighter string?)
(s/def ::indent-size pos-int?)

(s/def ::config (s/keys :req-un [::extensions]
                        :opt-un [::grammar-wasm
                                 ::highlight-query-path
                                 ::indents-query-path
                                 ::lsp-url
                                 ::file-icon
                                 ::fallback-highlighter
                                 ::indent-size]))

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

(defn normalize-languages
  "Ensures all keys in the languages map are strings, converting keywords if necessary.
  Also normalizes inner config keys from camelCase to kebab-case.
  Validates each config with spec and throws on invalid."
  [langs]
  (reduce-kv
   (fn [m k v]
     (let [key-str (if (keyword? k) (name k) (str k))
           config (convert-config-keys v)]
       (when-not (s/valid? ::config config)
         (log/error "Invalid language config for" key-str (s/explain-str ::config config))
         (throw (ex-info "Invalid language config" (clj->js {:lang key-str
                                                             :explain (s/explain-data ::config config)}))))
       (assoc m key-str config)))
   {}
   langs))
