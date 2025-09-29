(ns lib.utils
  (:require
   [clojure.core.async :refer [put! promise-chan]]
   [taoensso.timbre :as log]))

(defn log-error-with-cause
  ([^js/Error error]
   (letfn [(collect-error-chain [err chain]
             (if err
               (recur (.-cause err) (conj chain err))
               chain))]
     (let [error-chain (collect-error-chain error [])]
       (doseq [[i err] (map-indexed vector error-chain)]
         (if (zero? i)
           (log/error (str "Error: " (.-message err) "\n" (.-stack err)))
           (log/error (str "Caused by: " (.-message err) "\n" (.-stack err))))))))
  ([message ^js/Error error]
   (log/error message)
   (log-error-with-cause error)))

(defn generate-uuid
  "Generates a random UUID."
  []
  (random-uuid))

(defn get-extension
  "Returns the primary extension for a language from the db."
  [db lang]
  (or (first (get-in db [:languages lang :extensions])) "text"))

(defn get-lang-from-ext
  [db ext]
  (or (ffirst (filter (fn [[_ v]] (some #{ext} (:extensions v))) (:languages db)))
      (:default-language db)))

(defn new-untitled-name
  "Generates an untitled file name with optional index and extension."
  [db n]
  (let [lang (:default-language db)
        ext (get-extension db lang)]
    (str "untitled" (when (pos? n) (str "-" n)) ext)))

(defn offset->pos [^js/Text doc ^number offset one-based?]
  (let [line (.lineAt doc offset)
        l (if one-based? (.-number line) (dec (.-number line)))
        c (if one-based? (inc (- offset (.-from line))) (- offset (.-from line)))]
    {:line l :column c}))

(defn pos->offset [^js/Text doc pos one-based?]
  (let [line-num (:line pos)
        l (if one-based? line-num (inc line-num))
        max-line (.-lines doc)]
    (when (and (>= l 1) (<= l max-line))
      (let [line (.line doc l)
            col (:column pos)
            c (if one-based? (dec col) col)
            max-c (.-length line)]
        (when (and (>= c 0) (<= c max-c))
          (+ (.-from line) c))))))

(defn debounce
  "Returns a debounced version of function f that delays invocation by ms milliseconds."
  [f ms]
  (let [timer (atom nil)]
    (fn [& args]
      (when @timer (js/clearTimeout @timer))
      (reset! timer (js/setTimeout #(apply f args) ms)))))

(defn split-uri
  "Splits a URI into its protocol and file path."
  [uri]
  (let [[_ protocol path] (re-find #"^([a-zA-Z]+:/{0,2})?(.*)$" uri)]
    [protocol path]))

(defn resolve-nested-promise
  "Recursively resolves a Promise until a non-Promise value is obtained."
  [p]
  (js/Promise.
   (fn [resolve reject]
     (if (or (nil? p) (not (instance? js/Promise p)))
       (do
         (log/warn "resolve-nested-promise received non-Promise value:" (type p))
         (resolve p))
       (letfn [(resolve-loop [value depth]
                 (log/trace "Resolving Promise at depth" depth "with value type" (type value))
                 (if (> depth 10)
                   (reject (js/Error. "Maximum Promise resolution depth exceeded"))
                   (if (instance? js/Promise value)
                     (-> value
                         (.then #(resolve-loop % (inc depth)))
                         (.catch reject))
                     (do
                       (log/trace "Resolved non-Promise value:" (type value))
                       (resolve value)))))]
         (-> p
             (.then #(resolve-loop % 0))
             (.catch reject)))))))

(defn promise->chan
  "Converts a JS Promise to an async channel, putting [:ok val] on resolve or [:error err] on reject."
  [p]
  (let [ch (promise-chan)]
    (if (or (nil? p) (not (instance? js/Promise p)))
      (do
        (log/error "promise->chan received non-Promise value:" (pr-str p) "type:" (type p))
        (put! ch [:error (js/Error. (str "Invalid Promise: received value " (pr-str p) " of type " (type p)))]))
      (-> (resolve-nested-promise p)
          (.then (fn [result]
                   (log/trace "Promise resolved to value of type" (type result))
                   (if (instance? js/Promise result)
                     (do
                       (log/error "Unexpected nested Promise after resolution")
                       (put! ch [:error (js/Error. "Unexpected nested Promise after resolution")]))
                     (put! ch [:ok result]))))
          (.catch (fn [e]
                    (let [err (js/Error. (str "Promise rejected: " (.-message e)) #js {:cause e})]
                      (log-error-with-cause err)
                      (put! ch [:error err]))))))
    ch))
