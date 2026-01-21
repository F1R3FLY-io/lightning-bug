(ns test.app.subs-test
  "Tests for Re-Frame subscriptions in app.subs."
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [re-frame.core :as rf]
   [re-frame.db :as rf-db]
   [datascript.core :as d]
   [lib.db :as db]
   [app.db :refer [default-db]]
   [app.subs]
   [test.lib.test-helpers :as h]
   [test.app.reframe-helpers :as rfh]))

;; =============================================================================
;; Fixtures
;; =============================================================================

(use-fixtures :each
  {:before (fn []
             (d/reset-conn! db/conn (d/empty-db db/schema))
             (rfh/reset-app-db!))})

;; =============================================================================
;; Workspace Subscriptions
;; =============================================================================

(deftest workspace-files-returns-all-documents
  (testing ":workspace/files returns all documents with names"
    (h/create-test-document! {:uri "file:///path/to/file1.rho"
                              :text "content1"
                              :language "rholang"
                              :version 1
                              :dirty false
                              :opened true})
    (h/create-test-document! {:uri "file:///path/to/file2.rho"
                              :text "content2"
                              :language "rholang"
                              :version 2
                              :dirty true
                              :opened false})
    (let [files @(rf/subscribe [:workspace/files])]
      (is (= 2 (count files)))
      (is (contains? files "file:///path/to/file1.rho"))
      (is (contains? files "file:///path/to/file2.rho"))
      ;; Check that name is extracted
      (is (= "file1.rho" (get-in files ["file:///path/to/file1.rho" :name])))
      (is (= "file2.rho" (get-in files ["file:///path/to/file2.rho" :name]))))))

(deftest workspace-files-returns-empty-when-no-documents
  (testing ":workspace/files returns empty map when no documents"
    (let [files @(rf/subscribe [:workspace/files])]
      (is (empty? files)))))

(deftest workspace-active-file-returns-uri
  (testing ":workspace/active-file returns the active document URI"
    (h/create-test-document! {:uri "file:///test.rho"})
    (db/update-active-uri! "file:///test.rho")
    (is (= "file:///test.rho" @(rf/subscribe [:workspace/active-file])))))

(deftest workspace-active-file-returns-nil-when-none
  (testing ":workspace/active-file returns nil when no active document"
    (is (nil? @(rf/subscribe [:workspace/active-file])))))

;; =============================================================================
;; Language Subscriptions
;; =============================================================================

(deftest active-lang-returns-language
  (testing ":active-lang returns the language of active document"
    (h/create-test-document! {:uri "file:///test.rho"
                              :language "rholang"})
    (db/update-active-uri! "file:///test.rho")
    (is (= "rholang" @(rf/subscribe [:active-lang])))))

(deftest active-lang-returns-nil-when-no-active
  (testing ":active-lang returns nil when no active document"
    (is (nil? @(rf/subscribe [:active-lang])))))

(deftest languages-returns-configured-languages
  (testing ":languages returns the configured language map"
    (let [langs @(rf/subscribe [:languages])]
      (is (map? langs))
      (is (contains? langs "rholang"))
      (is (contains? langs "text")))))

(deftest default-language-returns-default
  (testing ":default-language returns the default language"
    (is (= "rholang" @(rf/subscribe [:default-language])))))

;; =============================================================================
;; Active Document Subscriptions
;; =============================================================================

(deftest active-name-returns-filename
  (testing ":active-name extracts filename from active URI"
    (h/create-test-document! {:uri "file:///path/to/myfile.rho"})
    (db/update-active-uri! "file:///path/to/myfile.rho")
    (is (= "myfile.rho" @(rf/subscribe [:active-name])))))

(deftest active-name-returns-nil-when-no-active
  (testing ":active-name returns nil when no active document"
    (is (nil? @(rf/subscribe [:active-name])))))

(deftest active-content-returns-text
  (testing ":active-content returns the text of active document"
    (h/create-test-document! {:uri "file:///test.rho"
                              :text "document content here"})
    (db/update-active-uri! "file:///test.rho")
    (is (= "document content here" @(rf/subscribe [:active-content])))))

(deftest active-content-returns-nil-when-no-active
  (testing ":active-content returns nil when no active document"
    (is (nil? @(rf/subscribe [:active-content])))))

;; =============================================================================
;; LSP Subscriptions
;; =============================================================================

(deftest lsp-connected?-returns-status
  (testing ":lsp/connected? returns connection status for active language"
    (h/create-test-document! {:uri "file:///test.rho"
                              :language "rholang"})
    (db/update-active-uri! "file:///test.rho")
    (swap! rf-db/app-db assoc-in [:lsp "rholang" :connected?] true)
    (is (true? @(rf/subscribe [:lsp/connected?])))
    (swap! rf-db/app-db assoc-in [:lsp "rholang" :connected?] false)
    (is (false? @(rf/subscribe [:lsp/connected?])))))

(deftest lsp-connected?-returns-nil-when-no-state
  (testing ":lsp/connected? returns nil when no LSP state"
    ;; When no language is active, defaults to "text"
    (is (nil? @(rf/subscribe [:lsp/connected?])))))

(deftest lsp-diagnostics-returns-all
  (testing ":lsp/diagnostics returns all diagnostics"
    (h/create-test-document! {:uri "file:///test.rho"})
    (db/replace-diagnostics-by-uri! "file:///test.rho" nil
                                    [{:message "Error 1"
                                      :severity 1
                                      :startLine 0
                                      :startChar 0
                                      :endLine 0
                                      :endChar 5}
                                     {:message "Warning"
                                      :severity 2
                                      :startLine 1
                                      :startChar 0
                                      :endLine 1
                                      :endChar 3}])
    (let [diags @(rf/subscribe [:lsp/diagnostics])]
      (is (= 2 (count diags))))))

(deftest lsp-symbols-returns-all
  (testing ":lsp/symbols returns all symbols"
    (h/create-test-document! {:uri "file:///test.rho"})
    (let [symbols (db/flatten-symbols
                   [{:name "func1"
                     :kind 12
                     :range {:start {:line 0 :character 0}
                             :end {:line 5 :character 0}}
                     :selectionRange {:start {:line 0 :character 4}
                                      :end {:line 0 :character 9}}}]
                   nil "file:///test.rho")]
      (db/replace-symbols! "file:///test.rho" symbols))
    (let [syms @(rf/subscribe [:lsp/symbols])]
      (is (= 1 (count syms))))))

;; =============================================================================
;; Search Subscriptions
;; =============================================================================

(deftest search-visible?-returns-visibility
  (testing ":search/visible? returns search panel visibility"
    (swap! rf-db/app-db assoc-in [:search :visible?] true)
    (is (true? @(rf/subscribe [:search/visible?])))
    (swap! rf-db/app-db assoc-in [:search :visible?] false)
    (is (false? @(rf/subscribe [:search/visible?])))))

(deftest search-results-returns-results
  (testing ":search-results returns search results"
    (swap! rf-db/app-db assoc-in [:search :results] ["line 1" "line 2"])
    (is (= ["line 1" "line 2"] @(rf/subscribe [:search-results])))))

;; =============================================================================
;; Log Subscriptions
;; =============================================================================

(deftest logs-returns-all-logs
  (testing ":logs returns all log entries"
    (db/create-logs! [{:message "Log 1" :lang "rholang"}
                      {:message "Log 2" :lang "text"}])
    (let [logs @(rf/subscribe [:logs])]
      (is (= 2 (count logs))))))

(deftest filtered-logs-filters-by-term
  (testing ":filtered-logs filters logs by search term"
    (db/create-logs! [{:message "Error in processing" :lang "rholang"}
                      {:message "Info message" :lang "rholang"}
                      {:message "Another error occurred" :lang "text"}])
    (let [filtered @(rf/subscribe [:filtered-logs "error"])]
      ;; Note: filter is case-sensitive by default
      (is (= 1 (count filtered)))
      (is (some #(= "Another error occurred" (:message %)) filtered)))))

(deftest logs-visible?-returns-visibility
  (testing ":logs-visible? returns log panel visibility"
    (swap! rf-db/app-db assoc :logs-visible? true)
    (is (true? @(rf/subscribe [:logs-visible?])))
    (swap! rf-db/app-db assoc :logs-visible? false)
    (is (false? @(rf/subscribe [:logs-visible?])))))

(deftest logs-height-returns-height
  (testing ":logs-height returns log panel height"
    (swap! rf-db/app-db assoc :logs-height 350)
    (is (= 350 @(rf/subscribe [:logs-height])))))

(deftest logs-height-returns-default-when-not-set
  (testing ":logs-height returns default when not set"
    (swap! rf-db/app-db dissoc :logs-height)
    (is (= 200 @(rf/subscribe [:logs-height])))))

;; =============================================================================
;; Status Subscriptions
;; =============================================================================

(deftest status-returns-status
  (testing ":status returns the current status"
    (swap! rf-db/app-db assoc :status :running)
    (is (= :running @(rf/subscribe [:status])))
    (swap! rf-db/app-db assoc :status :idle)
    (is (= :idle @(rf/subscribe [:status])))))

;; =============================================================================
;; Rename Modal Subscriptions
;; =============================================================================

(deftest rename-visible?-returns-visibility
  (testing ":rename/visible? returns rename modal visibility"
    (swap! rf-db/app-db assoc-in [:modals :rename :visible?] true)
    (is (true? @(rf/subscribe [:rename/visible?])))
    (swap! rf-db/app-db assoc-in [:modals :rename :visible?] false)
    (is (false? @(rf/subscribe [:rename/visible?])))))

(deftest rename-new-name-returns-name
  (testing ":rename/new-name returns the new name for rename"
    (swap! rf-db/app-db assoc-in [:modals :rename :new-name] "newfile.rho")
    (is (= "newfile.rho" @(rf/subscribe [:rename/new-name])))))

;; =============================================================================
;; Editor Subscriptions
;; =============================================================================

(deftest editor-cursor-returns-cursor
  (testing ":editor/cursor returns the cursor position"
    (swap! rf-db/app-db assoc-in [:editor :cursor] {:line 10 :column 5})
    (is (= {:line 10 :column 5} @(rf/subscribe [:editor/cursor])))))

(deftest editor-selection-returns-selection
  (testing ":editor/selection returns the current selection"
    (let [selection {:from {:line 1 :column 0}
                     :to {:line 3 :column 10}}]
      (swap! rf-db/app-db assoc-in [:editor :selection] selection)
      (is (= selection @(rf/subscribe [:editor/selection]))))))

(deftest editor-selection-returns-nil-when-none
  (testing ":editor/selection returns nil when no selection"
    (swap! rf-db/app-db assoc-in [:editor :selection] nil)
    (is (nil? @(rf/subscribe [:editor/selection])))))

(deftest editor-highlights-returns-highlights
  (testing ":editor/highlights returns the current highlights"
    (let [range {:from {:line 5 :column 0}
                 :to {:line 5 :column 15}}]
      (swap! rf-db/app-db assoc-in [:editor :highlights] range)
      (is (= range @(rf/subscribe [:editor/highlights]))))))

(deftest editor-ready-returns-ready-state
  (testing ":editor/ready returns the editor ready state"
    (swap! rf-db/app-db assoc-in [:editor :ready] true)
    (is (true? @(rf/subscribe [:editor/ready])))
    (swap! rf-db/app-db assoc-in [:editor :ready] false)
    (is (false? @(rf/subscribe [:editor/ready])))))
