(ns lib.editor.annotations
  (:require
   ["@codemirror/state" :refer [Annotation]]))

;; Annotation to mark external full-text set transactions (skips debounced LSP in update listener)
(def external-set-annotation (.define Annotation))
