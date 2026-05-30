(ns test.app.events-test
  "Tests for Re-Frame event handlers in app.events."
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [re-frame.core :as rf]
   [re-frame.db :as rf-db]
   [lib.workspace :as ws]
   [app.db :refer [default-db]]
   [app.events :as events]
   [app.subs]
   [test.app.reframe-helpers :as rfh]))

;; =============================================================================
;; Fixtures
;; =============================================================================

(use-fixtures :each
  {:before (fn []
             (ws/reset-workspace! @ws/default-workspace)
             (rfh/reset-app-db!)
             (rfh/reset-captured-effects!))
   :after (fn []
            (rfh/restore-all-effects!)
            (rfh/restore-all-coeffects!))})

;; =============================================================================
;; Initialization Tests
;; =============================================================================

(deftest initialize-creates-default-db
  (testing "::initialize event sets up default database state"
    (reset! rf-db/app-db {})
    (rf/dispatch-sync [::events/initialize])
    (is (= default-db @rf-db/app-db))))

;; =============================================================================
;; Status Tests
;; =============================================================================

(deftest set-status-updates-db
  (testing "::set-status updates the status in db"
    (rf/dispatch-sync [::events/set-status :running])
    (is (= :running (:status @rf-db/app-db)))
    (rf/dispatch-sync [::events/set-status :idle])
    (is (= :idle (:status @rf-db/app-db)))))

;; =============================================================================
;; Cursor and Selection Tests
;; =============================================================================

(deftest update-cursor-updates-db
  (testing "::update-cursor updates cursor position in db"
    (rf/dispatch-sync [::events/update-cursor {:line 10 :column 5}])
    (is (= {:line 10 :column 5} (get-in @rf-db/app-db [:editor :cursor])))))

(deftest update-selection-updates-db
  (testing "::update-selection updates selection in db"
    (let [selection {:from {:line 1 :column 0}
                     :to {:line 3 :column 10}}]
      (rf/dispatch-sync [::events/update-selection selection])
      (is (= selection (get-in @rf-db/app-db [:editor :selection]))))))

;; =============================================================================
;; Search Tests
;; =============================================================================

(deftest search-with-empty-term-clears-results
  (testing "::search with empty term clears results"
    ;; Set up initial state with some results
    (swap! rf-db/app-db assoc :search {:term "old" :results ["line 1" "line 2"]})
    ;; Mock the coeffects
    (rfh/mock-coeffect! :document-repo/active-document {:text "some text"})
    (rfh/mock-coeffect! :logs/all [])
    (rf/dispatch-sync [::events/search ""])
    (is (= "" (get-in @rf-db/app-db [:search :term])))
    (is (empty? (get-in @rf-db/app-db [:search :results])))))

(deftest search-finds-matches-in-code
  (testing "::search finds matching lines in document text"
    (rfh/mock-coeffect! :document-repo/active-document
                        {:text "line one\nline two contains match\nline three"})
    (rfh/mock-coeffect! :logs/all [])
    (rf/dispatch-sync [::events/search "match"])
    (is (= "match" (get-in @rf-db/app-db [:search :term])))
    (let [results (get-in @rf-db/app-db [:search :results])]
      (is (= 1 (count results)))
      (is (= "line two contains match" (first results))))))

(deftest search-is-case-insensitive
  (testing "::search is case-insensitive"
    (rfh/mock-coeffect! :document-repo/active-document
                        {:text "LINE WITH MATCH\nline with match\nno hits here"})
    (rfh/mock-coeffect! :logs/all [])
    (rf/dispatch-sync [::events/search "MATCH"])
    (let [results (get-in @rf-db/app-db [:search :results])]
      (is (= 2 (count results))))))

(deftest search-includes-logs
  (testing "::search includes matching log messages"
    (rfh/mock-coeffect! :document-repo/active-document {:text ""})
    (rfh/mock-coeffect! :logs/all ["Log with error" "Another log" "Error message"])
    (rf/dispatch-sync [::events/search "error"])
    (let [results (get-in @rf-db/app-db [:search :results])]
      (is (= 2 (count results))))))

(deftest toggle-search-toggles-visibility
  (testing "::toggle-search toggles search panel visibility"
    (swap! rf-db/app-db assoc-in [:search :visible?] false)
    (rf/dispatch-sync [::events/toggle-search])
    (is (true? (get-in @rf-db/app-db [:search :visible?])))
    (rf/dispatch-sync [::events/toggle-search])
    (is (false? (get-in @rf-db/app-db [:search :visible?])))))

;; =============================================================================
;; Rename Modal Tests
;; =============================================================================

(deftest open-rename-modal-populates-name
  (testing "::open-rename-modal extracts filename from active URI"
    (rfh/mock-coeffect! :document-repo/active-uri "file:///path/to/myfile.rho")
    (rf/dispatch-sync [::events/open-rename-modal])
    (is (true? (get-in @rf-db/app-db [:modals :rename :visible?])))
    (is (= "myfile.rho" (get-in @rf-db/app-db [:modals :rename :new-name])))))

(deftest open-rename-modal-with-no-active-uri
  (testing "::open-rename-modal handles no active URI"
    (rfh/mock-coeffect! :document-repo/active-uri nil)
    (rf/dispatch-sync [::events/open-rename-modal])
    (is (true? (get-in @rf-db/app-db [:modals :rename :visible?])))
    (is (nil? (get-in @rf-db/app-db [:modals :rename :new-name])))))

(deftest close-rename-modal-hides-modal
  (testing "::close-rename-modal hides the modal"
    (swap! rf-db/app-db assoc-in [:modals :rename :visible?] true)
    (rf/dispatch-sync [::events/close-rename-modal])
    (is (false? (get-in @rf-db/app-db [:modals :rename :visible?])))))

(deftest set-rename-name-updates-name
  (testing "::set-rename-name updates the new name"
    (rf/dispatch-sync [::events/set-rename-name "new-filename.rho"])
    (is (= "new-filename.rho" (get-in @rf-db/app-db [:modals :rename :new-name])))))

(deftest confirm-rename-triggers-effect
  (testing "::confirm-rename triggers editor/rename-document effect"
    (rfh/mock-coeffect! :document-repo/active-uri "file:///old.rho")
    (rfh/mock-effect! :editor/rename-document)
    (swap! rf-db/app-db assoc-in [:modals :rename :new-name] "new.rho")
    (rf/dispatch-sync [::events/confirm-rename])
    (is (false? (get-in @rf-db/app-db [:modals :rename :visible?])))
    (is (rfh/effect-was-triggered? :editor/rename-document))
    (let [effects (rfh/get-captured-effects-of-type :editor/rename-document)]
      (is (= 1 (count effects)))
      (is (= {:new-uri "new.rho" :old-uri "file:///old.rho"}
             (:value (first effects)))))))

(deftest confirm-rename-does-nothing-without-active-uri
  (testing "::confirm-rename does nothing when no active URI"
    (rfh/mock-coeffect! :document-repo/active-uri nil)
    (rfh/mock-effect! :editor/rename-document)
    (swap! rf-db/app-db assoc-in [:modals :rename :new-name] "new.rho")
    (rf/dispatch-sync [::events/confirm-rename])
    (is (not (rfh/effect-was-triggered? :editor/rename-document)))))

;; =============================================================================
;; Logs Tests
;; =============================================================================

(deftest toggle-logs-toggles-visibility
  (testing "::toggle-logs toggles log panel visibility"
    (swap! rf-db/app-db assoc :logs-visible? false)
    (rf/dispatch-sync [::events/toggle-logs])
    (is (true? (:logs-visible? @rf-db/app-db)))
    (rf/dispatch-sync [::events/toggle-logs])
    (is (false? (:logs-visible? @rf-db/app-db)))))

(deftest set-logs-height-updates-height
  (testing "::set-logs-height updates the log panel height"
    (rf/dispatch-sync [::events/set-logs-height 300])
    (is (= 300 (:logs-height @rf-db/app-db)))))

;; =============================================================================
;; Editor Cursor Effect Tests
;; =============================================================================

(deftest set-editor-cursor-triggers-effect
  (testing "::set-editor-cursor triggers editor/set-cursor effect"
    (rfh/mock-effect! :editor/set-cursor)
    (rf/dispatch-sync [::events/set-editor-cursor {:line 5 :column 10}])
    (is (rfh/effect-was-triggered? :editor/set-cursor))
    (is (rfh/effect-was-triggered-with? :editor/set-cursor {:line 5 :column 10}))))

;; =============================================================================
;; Highlight Range Tests
;; =============================================================================

(deftest set-highlight-range-with-range
  (testing "::set-highlight-range triggers highlight effect when range provided"
    (rfh/mock-effect! :editor/highlight-range)
    (let [range {:from {:line 1 :column 0}
                 :to {:line 1 :column 10}}]
      (rf/dispatch-sync [::events/set-highlight-range range])
      (is (rfh/effect-was-triggered? :editor/highlight-range))
      (is (rfh/effect-was-triggered-with? :editor/highlight-range range)))))

(deftest set-highlight-range-with-nil-clears
  (testing "::set-highlight-range triggers clear highlight when nil"
    (rfh/mock-effect! :editor/clear-highlight)
    (rf/dispatch-sync [::events/set-highlight-range nil])
    (is (rfh/effect-was-triggered? :editor/clear-highlight))))

(deftest update-highlights-updates-db
  (testing "::update-highlights updates highlights in db"
    (let [range {:from {:line 1 :column 0}
                 :to {:line 1 :column 10}}]
      (rf/dispatch-sync [::events/update-highlights range])
      (is (= range (get-in @rf-db/app-db [:editor :highlights]))))))

;; =============================================================================
;; Editor Ready Tests
;; =============================================================================

(deftest editor-ready-sets-flag
  (testing "::editor-ready sets the ready flag"
    (swap! rf-db/app-db assoc-in [:editor :ready] false)
    (rf/dispatch-sync [::events/editor-ready])
    (is (true? (get-in @rf-db/app-db [:editor :ready])))))

;; =============================================================================
;; Agent Tests
;; =============================================================================

(deftest run-agent-sets-status-and-schedules-validation
  (testing "::run-agent sets status to running and dispatches later"
    (rf/dispatch-sync [::events/run-agent])
    (is (= :running (:status @rf-db/app-db)))))

(deftest validate-agent-does-not-crash
  (testing "::validate-agent runs without error"
    (rf/dispatch-sync [::events/validate-agent])
    ;; Just ensure no exception is thrown
    (is true)))

;; =============================================================================
;; Handle Editor Event Tests
;; =============================================================================

(deftest handle-editor-event-placeholder
  (testing "::handle-editor-event handles unknown event types gracefully"
    (let [initial-db @rf-db/app-db]
      (rf/dispatch-sync [::events/handle-editor-event {:type :unknown :data {}}])
      ;; Should return db unchanged for unknown types
      (is (= initial-db @rf-db/app-db)))))

;; =============================================================================
;; Event Handler Direct Testing
;; =============================================================================

(deftest direct-event-handler-test
  (testing "Event handlers update db correctly via dispatch"
    (reset! rf-db/app-db default-db)
    (rf/dispatch-sync [::events/set-status :custom-status])
    (is (= :custom-status (:status @rf-db/app-db)))))
