(ns lib.utils)

(defn offset-to-pos [^js/Text doc ^number offset one-based?]
  (let [line (.lineAt doc offset)
        l (if one-based? (.-number line) (dec (.-number line)))
        c (if one-based? (inc (- offset (.-from line))) (- offset (.-from line)))]
    {:line l :column c}))

(defn pos-to-offset [^js/Text doc pos one-based?]
  (let [line-num (:line pos)
        l (if one-based? line-num (inc line-num))
        max-line (.-lines doc)]
    (when (and (>= l 1) (<= l max-line))
      (let [line (.line doc l)
            col (:column pos)
            c (if one-based? (dec col) col)
            max-c (.-length line)]
        (+ (.-from line) (min (max c 0) max-c))))))

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
