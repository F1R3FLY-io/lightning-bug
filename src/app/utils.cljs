(ns app.utils)

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

(defn debounce
  "Returns a debounced version of function f that delays invocation by ms milliseconds.
  Clears any existing timer before scheduling a new one."
  [f ms]
  (let [timer (atom nil)]
    (fn [& args]
      (when @timer (js/clearTimeout @timer))
      (reset! timer (js/setTimeout #(apply f args) ms)))))
