(ns app.editor.state
  (:require [reagent.core :as r]))

;; Global state for CodeMirror view and DOM ref.
(defonce editor-view (atom nil))
(defonce editor-ref (atom nil))
(defonce cursor-pos (r/atom {:line 1 :col 1}))
