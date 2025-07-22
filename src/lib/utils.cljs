(ns lib.utils)

(defn offset-to-pos [^js/Text doc ^number offset one-based?]
  (let [line (.lineAt doc offset)
        l (if one-based? (.-number line) (dec (.-number line)))
        c (if one-based? (inc (- offset (.-from line))) (- offset (.-from line)))]
    {:line l :column c}))

(defn pos-to-offset [^js/Text doc pos one-based?]
  (let [l (if one-based? (:line pos) (inc (:line pos)))
        line (.line doc l)
        c (if one-based? (dec (:column pos)) (:column pos))]
    (+ (.-from line) c)))
