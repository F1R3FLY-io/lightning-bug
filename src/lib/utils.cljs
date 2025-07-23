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
