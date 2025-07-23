(ns app.utils
  (:require [clojure.string :as str]))

(defn generate-uuid
  "Generates a random UUID."
  []
  (random-uuid))

(defn get-extension
  "Returns the primary extension for a language from the db."
  [db lang]
  (or (first (get-in db [:languages lang :extensions])) ""))

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
