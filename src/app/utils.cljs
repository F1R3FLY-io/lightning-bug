(ns app.utils)

(defn generate-uuid []
  (random-uuid))

(defn get-extension [db lang]
  (or (first (get-in db [:languages lang :extensions])) ""))

(defn new-untitled-name [db n]
  (let [lang (:default-language db)
        ext (get-extension db lang)]
    (str "untitled" (when (pos? n) (str "-" n)) ext)))
