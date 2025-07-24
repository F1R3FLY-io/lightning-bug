(ns app.shared
  (:require [reagent.core :as r]))

(defonce editor-ref-atom (r/atom nil))
