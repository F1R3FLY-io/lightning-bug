(ns test.lib.editor.diagnostics-test
  (:require
   [clojure.test :refer [deftest is]]
   [lib.editor.diagnostics :as diagnostics]))

(deftest severity-class-mapping
  (is (= "error" (diagnostics/severity-class 1)) "Error severity maps to red wavy class")
  (is (= "warning" (diagnostics/severity-class 2)) "Warning severity maps to orange wavy class")
  (is (= "info" (diagnostics/severity-class 3)) "Info severity maps to blue dotted class")
  (is (= "hint" (diagnostics/severity-class 4)) "Hint severity maps to gray dotted class")
  (is (= "" (diagnostics/severity-class 5)) "Invalid severity returns empty class"))
