(ns lib.utils
  (:require [taoensso.timbre :as log]))

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
  "Returns a debounced version of function f that delays invocation by ms milliseconds.
  Clears any existing timer before scheduling a new one."
  [f ms]
  (let [timer (atom nil)]
    (fn [& args]
      (when @timer (js/clearTimeout @timer))
      (reset! timer (js/setTimeout #(apply f args) ms)))))

(defn split-uri
  "Splits a URI into its protocol and file path"
  [uri]
  (let [[_ protocol path] (re-find #"^([a-zA-Z]+:/{0,2})?(.*)$" uri)]
    [protocol path]))
