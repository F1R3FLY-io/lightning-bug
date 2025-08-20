(ns test.app.events-test
  (:require
   [clojure.test :refer [deftest is use-fixtures]]
   [datascript.core :as d]
   [day8.re-frame.test :as rf-test]
   [re-frame.core :as rf]
   [re-posh.core :as rp]
   [app.events :as e]
   [app.subs]
   [lib.db :as lib-db :refer [conn flatten-diags flatten-symbols]]
   [taoensso.timbre :as log]))

(use-fixtures :each
  {:before (fn []
             (log/set-min-level! :info)
             (d/reset-conn! conn (d/empty-db lib-db/schema))
             (rp/connect! conn)
             (rf/dispatch-sync [::e/initialize]))
   :after (fn [] (log/set-min-level! :debug))})

(deftest initialize
  (rf-test/run-test-sync
   (let [files @(rf/subscribe [:workspace/files])
         active @(rf/subscribe [:workspace/active-file])
         file (get files active)]
     (is (= 1 (count files)))
     (is (= "untitled.rho" (:name file)))
     (is (= "" (:content file)))
     (is (= "rholang" (:language file)))
     (is (false? (:dirty? file))))))

(deftest file-add
  (rf-test/run-test-sync
   (rf/dispatch [::e/file-add])
   (let [files @(rf/subscribe [:workspace/files])
         active @(rf/subscribe [:workspace/active-file])
         file (get files active)]
     (is (= 2 (count files)))
     (is (= "untitled-1.rho" (:name file)))
     (is (= "" (:content file)))
     (is (= "rholang" (:language file)))
     (is (false? (:dirty? file))))))

(deftest file-open
  (rf-test/run-test-sync
   (let [first-id (first (keys @(rf/subscribe [:workspace/files])))]
     (rf/dispatch [::e/file-add])
     (rf/dispatch [::e/file-open first-id])
     (is (= first-id @(rf/subscribe [:workspace/active-file]))))))

(deftest file-remove
  (rf-test/run-test-sync
   (let [first-id (first (keys @(rf/subscribe [:workspace/files])))]
     (rf/dispatch [::e/file-add])
     (rf/dispatch [::e/file-remove first-id])
     (is (= 1 (count @(rf/subscribe [:workspace/files]))))
     (is (not= first-id @(rf/subscribe [:workspace/active-file]))))))

(deftest file-rename
  (rf-test/run-test-sync
   (let [active @(rf/subscribe [:workspace/active-file])]
     (rf/dispatch [::e/file-rename active "new-name.rho"])
     (let [files @(rf/subscribe [:workspace/files])
           active @(rf/subscribe [:workspace/active-file])
           file (get files active)]
       (is (= "new-name.rho" (:name file)))
       (is (= "rholang" (:language file)))))))

(deftest editor-update-content
  (rf-test/run-test-sync
   (rf/dispatch [::e/editor-update-content "updated"])
   (is (= "updated" @(rf/subscribe [:active-content])))
   (is (true? (get-in @(rf/subscribe [:workspace/files]) [@(rf/subscribe [:workspace/active-file]) :dirty?])))))

(deftest lsp-diagnostics-update
  (rf-test/run-test-sync
   (let [diag-params {:uri "file://test"
                      :diagnostics [{:range {:start {:line 0
                                                     :character 0}
                                             :end {:line 0
                                                   :character 5}}
                                     :severity 1
                                     :message "error"}]}
         diags (flatten-diags diag-params)]
     (rp/dispatch [::e/lsp-diagnostics-update diags])
     (let [stored-diags @(rf/subscribe [:lsp/diagnostics])]
       (is (= 1 (count stored-diags)))
       (is (= "error" (:diagnostic/message (first stored-diags))))
       (is (= :error @(rf/subscribe [:status])))))))

(deftest log-append
  (rf-test/run-test-sync
   (rf/dispatch [::e/log-append {:message "test log"}])
   (let [logs @(rf/subscribe [:logs])]
     (is (= 1 (count logs)))
     (is (= "test log" (:message (first logs)))))))

(deftest search
  (rf-test/run-test-sync
   (rf/dispatch [::e/editor-update-content "hello\nworld"])
   (rf/dispatch [::e/log-append {:message "hello log"}])
   (rf/dispatch [::e/search "hello"])
   (let [results @(rf/subscribe [:search-results])]
     (is (= 1 (count results)))
     (is (= "hello log" (first results))))))

(deftest toggle-search
  (rf-test/run-test-sync
   (rf/dispatch [::e/toggle-search])
   (is (true? @(rf/subscribe [:search/visible?])))))

(deftest rename-modal
  (rf-test/run-test-sync
   (rf/dispatch [::e/open-rename-modal])
   (is (true? @(rf/subscribe [:rename/visible?])))
   (rf/dispatch [::e/set-rename-name "new"])
   (is (= "new" @(rf/subscribe [:rename/new-name])))
   (rf/dispatch [::e/confirm-rename])
   (is (= "new" @(rf/subscribe [:active-name])))
   (is (false? @(rf/subscribe [:rename/visible?])))))

(deftest toggle-logs
  (rf-test/run-test-sync
   (rf/dispatch [::e/toggle-logs])
   (is (true? @(rf/subscribe [:logs-visible?])))))

(deftest set-logs-height
  (rf-test/run-test-sync
   (rf/dispatch [::e/set-logs-height 300])
   (is (= 300 @(rf/subscribe [:logs-height])))))

(deftest update-cursor
  (rf-test/run-test-sync
   (rf/dispatch [::e/update-cursor {:line 3 :column 4}])
   (is (= {:line 3 :column 4} @(rf/subscribe [:editor/cursor])))))

(deftest update-selection
  (rf-test/run-test-sync
   (rf/dispatch [::e/update-selection {:from {:line 1 :column 1} :to {:line 1 :column 5}}])
   (is (= {:from {:line 1 :column 1} :to {:line 1 :column 5}} @(rf/subscribe [:editor/selection])))))

(deftest lsp-symbols-update
  (rf-test/run-test-sync
   (let [hier-symbols [{:name "parent"
                        :kind 5
                        :range {:start {:line 0
                                        :character 0}
                                :end {:line 2
                                      :character 0}}
                        :selectionRange {:start {:line 0
                                                 :character 0}
                                         :end {:line 0
                                               :character 6}}
                        :children [{:name "child"
                                    :kind 12
                                    :range {:start {:line 1
                                                    :character 2}
                                            :end {:line 1
                                                  :character 7}}
                                    :selectionRange {:start {:line 1
                                                             :character 2}
                                                     :end {:line 1
                                                           :character 7}}}]}]
         symbols (flatten-symbols hier-symbols nil "test-uri")]
     (rp/dispatch [::e/lsp-symbols-update symbols])
     (let [stored-syms @(rf/subscribe [:lsp/symbols])]
       (is (= 2 (count stored-syms)))
       (is (= "parent" (:symbol/name (first (filter #(= "parent" (:symbol/name %)) stored-syms)))))
       (is (= "child" (:symbol/name (first (filter #(= "child" (:symbol/name %)) stored-syms)))))))))

(deftest lsp-set-connection
  (rf-test/run-test-sync
   (rf/dispatch [::e/lsp-set-connection true])
   (is (true? @(rf/subscribe [:lsp/connected?])))))

(deftest set-status
  (rf-test/run-test-sync
   (rf/dispatch [::e/set-status :success])
   (is (= :success @(rf/subscribe [:status])))))

(deftest run-agent
  (rf-test/run-test-async
   (rf/dispatch [::e/run-agent])
   (rf-test/wait-for [::e/validate-agent]
     (is (= :running @(rf/subscribe [:status]))))))

(deftest handle-editor-event-diagnostics
  (rf-test/run-test-sync
   (let [data [{:uri "file://test"
                :message "error"
                :severity 1
                :startLine 0
                :startChar 0
                :endLine 0
                :endChar 5}]]
     (rf/dispatch [::e/handle-editor-event {:type "diagnostics" :data data}])
     (rf-test/wait-for [::e/lsp-diagnostics-update]
       (let [stored-diags @(rf/subscribe [:lsp/diagnostics])]
         (is (= 1 (count stored-diags)))
         (is (= "error" (:diagnostic/message (first stored-diags))))
         (is (= :error @(rf/subscribe [:status]))))))))

(deftest handle-editor-event-symbols
  (rf-test/run-test-sync
   (let [data [{:name "root" :kind 1 :startLine 0 :startChar 0 :endLine 2 :endChar 0
                :selectionStartLine 0 :selectionStartChar 0 :selectionEndLine 0 :selectionEndChar 4
                :parent nil}]]
     (rf/dispatch [::e/handle-editor-event {:type "symbols" :data data}])
     (rf-test/wait-for [::e/lsp-symbols-update]
       (let [stored-syms @(rf/subscribe [:lsp/symbols])]
         (is (= 1 (count stored-syms)))
         (is (= "root" (:symbol/name (first stored-syms)))))))))
