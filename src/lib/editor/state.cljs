(ns lib.editor.state
  (:require [reagent.core :as r]))

(def cursor-pos (r/atom {:line 1 :col 1}))
