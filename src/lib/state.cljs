(ns lib.state
  (:require [clojure.core.async :refer [chan promise-chan put! <! go]]
            [cljs.core.async.impl.protocols :as async-impl]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.spec.alpha :as s]
            [taoensso.timbre :as log]
            [lib.utils :refer [promise->chan]]))

;; Language configuration specs
(s/def ::grammar-wasm (s/or :string string? :fn fn?))
(s/def ::parser (s/or :fn fn? :instance any?))
(s/def ::highlights-query-path (s/or :string string? :fn fn?))
(s/def ::highlights-query string?)
(s/def ::indents-query-path (s/or :string string? :fn fn?))
(s/def ::indents-query string?)
(s/def ::lsp-url string?)
(s/def ::extensions (s/coll-of string? :min-count 1))
(s/def ::file-icon string?)
(s/def ::fallback-highlighter string?)
(s/def ::indent-size pos-int?)

(s/def ::language-config (s/keys :req-un [::extensions]
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

(s/def ::languages
  (s/map-of (s/or :string string? :keyword keyword?) ::language-config))

;; Editor configuration specs
(s/def ::tree-sitter-wasm (s/or :string string? :fn fn?))
(s/def ::extra-extensions any?)
(s/def ::default-protocol string?)
(s/def ::on-content-change fn?)
(s/def ::editor-config (s/keys :opt-un [::tree-sitter-wasm ::extra-extensions ::default-protocol ::on-content-change ::languages]))

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

;; Shared resources atom for language-specific resources
(defonce resources (atom {:lsp {} :tree-sitter {}}))

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

(defn validate-editor-config!
  [config]
  (when-not (s/valid? ::editor-config config)
    (let [explain (s/explain-str ::editor-config config)]
      (log/error "Invalid Editor properties:" explain)
      (throw (ex-info "Invalid Editor properties" {:explain explain})))))

(defn normalize-editor-config
  "Normalizes editor config by converting camelCase keys to kebab-case.
  Validates the config with spec and throws on invalid or unrecognized keys."
  [config]
  (let [normalized (convert-config-keys config)
        expected #{:tree-sitter-wasm :extra-extensions :default-protocol :on-content-change :languages}
        actual (set (keys normalized))]
    (when-let [extra (seq (set/difference actual expected))]
      (log/error "Unrecognized keys in editor config:" extra)
      (throw (ex-info "Unrecognized keys in editor config" {:extra extra})))
    (when-not (s/valid? ::editor-config normalized)
      (log/error "Invalid editor config" (s/explain-str ::editor-config normalized))
      (throw (ex-info "Invalid editor config" {:explain (s/explain-str ::editor-config normalized)})))
    normalized))

(defn normalize-languages
  "Ensures all keys in the languages map are strings, converting keywords if necessary.
  Also normalizes inner config keys from camelCase to kebab-case.
  Validates each config with spec and throws on invalid or unrecognized keys."
  [langs]
  (reduce-kv
   (fn [m k v]
     (let [key-str (if (keyword? k) (name k) (str k))
           config (convert-config-keys v)
           expected #{:grammar-wasm
                      :parser
                      :highlights-query-path
                      :highlights-query
                      :indents-query-path
                      :indents-query
                      :lsp-url
                      :extensions
                      :file-icon
                      :fallback-highlighter
                      :indent-size}
           actual (set (keys config))]
       (when-let [extra (seq (set/difference actual expected))]
         (log/error "Unrecognized keys in language config for" key-str ":" extra)
         (throw (ex-info "Unrecognized keys in language config" {:lang key-str :extra extra})))
       (when-not (s/valid? ::language-config config)
         (log/error "Invalid language config for" key-str (s/explain-str ::language-config config))
         (throw (ex-info "Invalid language config" {:lang key-str
                                                    :explain (s/explain-str ::language-config config)})))
       (assoc m key-str config)))
   {}
   langs))

(defn get-resource
  "Retrieves a resource from the shared atom, or nil if not present."
  [type lang]
  (get-in @resources [type lang :resource]))

(defn get-resource-promise
  "Retrieves the loading promise for a resource, or nil if not loading."
  [type lang]
  (get-in @resources [type lang :promise]))

(defn set-resource!
  "Stores a loaded resource in the shared atom."
  [type lang resource]
  (swap! resources assoc-in [type lang :resource] resource))

(defn set-resource-promise!
  "Stores a loading promise in the shared atom."
  [type lang promise]
  (swap! resources assoc-in [type lang :promise] promise))

(defn clear-resource-promise!
  "Clears the loading promise after resolution."
  [type lang]
  (swap! resources update-in [type lang] dissoc :promise))

(defn load-resource
  "Loads a resource asynchronously if not present, using a supplier function that returns a value, [:ok value], promise, channel, or [:error error].
  Returns a channel that puts [:ok resource] or [:error error]."
  [type lang supplier]
  (if-let [resource (get-resource type lang)]
    (let [ch (promise-chan)]
        (put! ch [:ok resource])
        ch)
      (if-let [p (get-resource-promise type lang)]
        (let [ch (chan)]
            (go
              (let [res (<! p)]
                (put! ch res)))
            ch)
          (let [p (chan)]
            (set-resource-promise! type lang p)
            (go
              (try
                (let [sup (supplier)
                      sup-res (if (satisfies? async-impl/ReadPort sup)
                                (<! sup)
                                sup)
                      [status val] (cond
                                     (and (seqable? sup-res) (#{:ok :error} (first sup-res))) sup-res
                                     :else [:ok sup-res])
                      res-ch (if (instance? js/Promise val)
                               (promise->chan val)
                               (let [ch (promise-chan)]
                                 (put! ch [status val])
                                 ch))
                      res-res (<! res-ch)]
                  (if (and (seqable? res-res) (= :error (first res-res)))
                    (put! p res-res)
                    (let [res (if (seqable? res-res) (second res-res) res-res)]
                      (set-resource! type lang res)
                      (put! p [:ok res]))))
                (catch js/Error e
                  (log/error "Failed to create WebSocket for lang=" lang ": " (.-message e))
                  (put! p [:error e]))
                (finally
                  (clear-resource-promise! type lang))))
            p))))

(defn close-resource!
  "Closes a resource for a language and removes it from the atom."
  [type lang closer]
  (when-let [resource (get-resource type lang)]
    (closer resource))
  (swap! resources update type dissoc lang))
