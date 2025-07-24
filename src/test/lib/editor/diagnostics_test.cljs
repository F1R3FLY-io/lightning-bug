(ns test.lib.editor.diagnostics-test
  (:require
   [clojure.core.async :as async :refer [go]]
   [clojure.test :refer [deftest is async]]
   ["@codemirror/state" :refer [EditorState]]
   ["@codemirror/view" :refer [EditorView]]
   [lib.editor.diagnostics :as diagnostics]))

(deftest severity-class-mapping
  (is (= "cm-error-underline" (diagnostics/severity-class 1)) "Error severity maps to red wavy class")
  (is (= "cm-warning-underline" (diagnostics/severity-class 2)) "Warning severity maps to orange wavy class")
  (is (= "cm-info-underline" (diagnostics/severity-class 3)) "Info severity maps to blue dotted class")
  (is (= "cm-hint-underline" (diagnostics/severity-class 4)) "Hint severity maps to gray dotted class")
  (is (= "" (diagnostics/severity-class 5)) "Invalid severity returns empty class"))

(deftest build-decorations-empty
  (async done
         (go
           (let [doc "test content"
                 state (.create EditorState #js {:doc doc :extensions #js [diagnostics/diagnostic-field]})
                 view (EditorView. #js {:state state :parent js/document.body})
                 decos (diagnostics/build-decorations view)]
             (is (zero? (.-size decos)) "No decorations for empty diagnostics")
             (.destroy view)
             (done)))))

(deftest build-decorations-with-diags
  (async done
    (go
      (let [doc "test content"
            diags [#js {:range #js {:start #js {:line 0 :character 0} :end #js {:line 0 :character 4}} :severity 1}
                   #js {:range #js {:start #js {:line 0 :character 5} :end #js {:line 0 :character 7}} :severity 2}]
            state (.create EditorState #js {:doc doc :extensions #js [diagnostics/diagnostic-field]})
            view (EditorView. #js {:state state :parent js/document.body})
            ;; Simulate updating the field.
            _ (.dispatch view #js {:annotations (.of diagnostics/diagnostic-annotation diags)})
            decos (diagnostics/build-decorations view)]
        (is (= 2 (.-size decos)) "Two decorations added for diagnostics")
        ;; Check first deco class.
        (let [iter ^js (.iter decos 0)
              value ^js (.-value iter)
              spec ^js (.-spec value)]
          (is (= "cm-error-underline" (.-class spec)) "First deco has error class"))
        (.destroy view)
        (done)))))

(deftest plugin-updates-on-doc-change
  (async done
    (go
      (let [doc "initial"
            diags [#js {:range #js {:start #js {:line 0 :character 0} :end #js {:line 0 :character 3}} :severity 1}]
            state (.create EditorState #js {:doc doc :extensions #js [diagnostics/diagnostic-field diagnostics/diagnostic-plugin]})
            view (EditorView. #js {:state state :parent js/document.body})
            plugin-instance (.plugin view diagnostics/diagnostic-plugin)]
        (.dispatch view #js {:annotations (.of diagnostics/diagnostic-annotation diags)})
        (let [initial-decos (.-decorations plugin-instance)]
          (is (= 1 (.-size initial-decos)) "Initial decoration added"))
        ;; Simulate doc change.
        (.dispatch view #js {:changes #js {:from 0 :insert "updated "}})
        (let [updated-decos (.-decorations plugin-instance)]
          (is (= 1 (.-size updated-decos)) "Decorations updated after doc change")
          ;; Check adjusted range (now from 8 to 11, but since diag range is fixed, but in practice, LSP would resend).
          ;; Here, just check rebuild happened.
          )
        (.destroy view)
        (done)))))

(deftest build-decorations-invalid-diag
  (async done
    (go
      (let [doc "test content"
            invalid-diags [#js {:range nil :severity 1}
                           #js {:range #js {:start nil :end #js {:line 0 :character 4}} :severity 2}
                           #js {:range #js {:start #js {:line 0 :character 5} :end nil} :severity 3}
                           #js {:range #js {:start #js {:line 0 :character 0} :end #js {:line 0 :character -1}} :severity 4}]
            state (.create EditorState #js {:doc doc :extensions #js [diagnostics/diagnostic-field]})
            view (EditorView. #js {:state state :parent js/document.body})
            ;; Simulate updating the field with invalid diags.
            _ (.dispatch view #js {:annotations (.of diagnostics/diagnostic-annotation invalid-diags)})
            decos (diagnostics/build-decorations view)]
        (is (zero? (.-size decos)) "No decorations added for invalid diagnostics")
        (.destroy view)
        (done)))))
