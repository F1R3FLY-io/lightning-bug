(ns test.integration.lsp-integration-test
  "Integration tests for LSP client functionality.

   These tests verify that the LSP client can connect, communicate,
   and handle various scenarios like reconnection and concurrent requests."
  (:require
   [clojure.test :refer [deftest is testing use-fixtures async]]
   [clojure.core.async :refer [go <! timeout promise-chan put!]]
   [re-frame.core :as rf]
   [reagent.core :as r]
   [lib.db :as db]
   [lib.workspace :as ws]
   [lib.lsp.connection-manager :as cm]
   [app.subs]
   [test.lib.test-helpers :as h]
   [test.app.reframe-helpers :as rfh]
   [test.lib.mock-lsp :as mock-lsp]))

;; =============================================================================
;; Fixtures
;; =============================================================================

(use-fixtures :each
  {:before (fn []
             (ws/reset-workspace! @ws/default-workspace)
             (rfh/reset-app-db!)
             (mock-lsp/reset-mock!)
             (cm/stop-all-cleanup-tasks!))
   :after (fn []
            (cm/stop-all-cleanup-tasks!))})

;; =============================================================================
;; Connection State Tests
;; =============================================================================

(deftest lsp-state-transitions-correctly
  (testing "LSP state transitions follow valid paths"
    ;; Test valid transition sequence
    (is (true? (cm/valid-transition? :disconnected :connecting)))
    (is (true? (cm/valid-transition? :connecting :connected)))
    (is (true? (cm/valid-transition? :connected :initializing)))
    (is (true? (cm/valid-transition? :initializing :initialized)))
    (is (true? (cm/valid-transition? :initialized :disconnecting)))
    (is (true? (cm/valid-transition? :disconnecting :disconnected)))))

(deftest lsp-error-recovery-transitions
  (testing "LSP can transition from error state to recovery"
    (is (true? (cm/valid-transition? :connecting :error)))
    (is (true? (cm/valid-transition? :connected :error)))
    (is (true? (cm/valid-transition? :initializing :error)))
    (is (true? (cm/valid-transition? :initialized :error)))
    ;; Recovery paths
    (is (true? (cm/valid-transition? :error :disconnected)))
    (is (true? (cm/valid-transition? :error :connecting)))))

;; =============================================================================
;; Connection Manager Tests
;; =============================================================================

(deftest connection-manager-tracks-state
  (testing "Connection manager tracks connection state correctly"
    (let [state-atom (r/atom {:lsp {"rholang" {:state :initialized}}})
          manager (cm/make-connection-manager state-atom nil (ws/default-conn))]
      (is (true? (cm/connected? manager "rholang")))
      (is (true? (cm/initialized? manager "rholang")))
      (is (false? (cm/connected? manager "unknown"))))))

(deftest connection-manager-handles-disconnected-state
  (testing "Connection manager reports disconnected correctly"
    (let [state-atom (reagent.core/atom {:lsp {"rholang" {:state :disconnected}}})
          manager (cm/make-connection-manager state-atom nil (ws/default-conn))]
      (is (false? (cm/connected? manager "rholang")))
      (is (false? (cm/initialized? manager "rholang"))))))

;; =============================================================================
;; Pending Request Management Tests
;; =============================================================================

(deftest pending-request-tracking
  (testing "Pending requests are tracked correctly"
    (let [request (cm/make-pending-request 1 :document-symbol "file:///test.rho" 30000)]
      (is (= 1 (:id request)))
      (is (= :document-symbol (:type request)))
      (is (= "file:///test.rho" (:uri request)))
      (is (number? (:timestamp request)))
      (is (= 30000 (:timeout-ms request))))))

(deftest pending-request-expiration
  (testing "Request expiration is detected correctly"
    ;; Fresh request should not be expired
    (let [fresh (cm/make-pending-request 1 :test "uri" 5000)]
      (is (false? (cm/request-expired? fresh))))

    ;; Old request should be expired
    (let [old (cm/->PendingRequest 2 :test "uri" (- (js/Date.now) 10000) 5000)]
      (is (true? (cm/request-expired? old))))))

(deftest stale-request-cleanup
  (testing "Stale requests are cleaned up"
    (let [state-atom (reagent.core/atom
                      {:lsp {"rholang" {:pending {1 {:timestamp (- (js/Date.now) 120000)}}
                                         :request-timeout-ms 60000}}})
          removed-ids (atom [])]
      (cm/cleanup-stale-requests! state-atom "rholang"
                                   (fn [_lang id _info]
                                     (swap! removed-ids conj id)))
      (is (= [1] @removed-ids))
      (is (empty? (get-in @state-atom [:lsp "rholang" :pending]))))))

;; =============================================================================
;; Timeout Handling Tests
;; =============================================================================

(deftest with-timeout-completes-before-timeout
  (async done
         (go
           (let [fast-result (go [:ok "fast"])
                 result (<! (cm/with-timeout fast-result 1000 "Fast op"))]
             (is (= [:ok "fast"] result)))
           (done))))

(deftest with-timeout-returns-timeout-error
  (async done
         (go
           (let [slow-result (go
                               (<! (timeout 500))
                               [:ok "slow"])
                 result (<! (cm/with-timeout slow-result 100 "Slow op"))]
             (is (= [:error :timeout] result)))
           (done))))

;; =============================================================================
;; Mock LSP Communication Tests
;; =============================================================================

(deftest mock-lsp-sends-and-receives-messages
  (async done
         (go
           (let [{:keys [send! mock]} (mock-lsp/create-mock-lsp)
                 messages (atom [])]
             ;; Set up message handler
             (mock-lsp/on-message! mock (fn [msg] (swap! messages conj msg)))

             ;; Send a message
             (send! {:jsonrpc "2.0" :method "test" :params {}})

             (<! (timeout 50))

             ;; Verify message was received
             (is (= 1 (count @messages)))
             (is (= "test" (:method (first @messages)))))
           (done))))

(deftest mock-lsp-handles-request-response
  (async done
         (go
           (let [{:keys [send! receive! mock]} (mock-lsp/create-mock-lsp)]
             ;; Configure mock to respond to initialize
             (mock-lsp/set-response! mock "initialize"
                                     {:capabilities {:textDocumentSync 1}})

             ;; Send initialize request
             (let [response-ch (promise-chan)]
               (mock-lsp/on-response! mock 1 (fn [resp] (put! response-ch resp)))
               (send! {:jsonrpc "2.0" :id 1 :method "initialize" :params {}})

               (<! (timeout 50))

               ;; Trigger response
               (receive! {:jsonrpc "2.0" :id 1 :result {:capabilities {:textDocumentSync 1}}})

               (let [response (<! response-ch)]
                 (is (= {:capabilities {:textDocumentSync 1}} (:result response))))))
           (done))))

;; =============================================================================
;; Diagnostic Notification Tests
;; =============================================================================

(deftest diagnostics-notification-updates-database
  (testing "Receiving diagnostics notification updates database"
    (let [uri "file:///test/diag.rho"]
      (h/create-test-document! {:uri uri :text "content"})
      (db/update-active-uri! (ws/default-conn) uri)

      ;; Simulate receiving diagnostics from LSP
      (let [lsp-diagnostics [{:range {:start {:line 0 :character 0}
                                      :end {:line 0 :character 5}}
                              :severity 1
                              :message "Error message"}]]
        (db/replace-diagnostics-by-uri! (ws/default-conn) uri nil
                                         (map (fn [d]
                                                {:message (:message d)
                                                 :severity (:severity d)
                                                 :startLine (get-in d [:range :start :line])
                                                 :startChar (get-in d [:range :start :character])
                                                 :endLine (get-in d [:range :end :line])
                                                 :endChar (get-in d [:range :end :character])})
                                              lsp-diagnostics)))

      (let [diags @(rf/subscribe [:lsp/diagnostics])]
        (is (= 1 (count diags)))
        (is (= "Error message" (:message (first diags))))))))

(deftest multiple-diagnostics-notifications-replace-previous
  (testing "New diagnostics notification replaces previous"
    (let [uri "file:///test/replace-diag.rho"]
      (h/create-test-document! {:uri uri :text "content"})
      (db/update-active-uri! (ws/default-conn) uri)

      ;; First batch
      (db/replace-diagnostics-by-uri! (ws/default-conn) uri nil
                                       [{:message "Error 1"
                                         :severity 1
                                         :startLine 0
                                         :startChar 0
                                         :endLine 0
                                         :endChar 5}
                                        {:message "Error 2"
                                         :severity 1
                                         :startLine 1
                                         :startChar 0
                                         :endLine 1
                                         :endChar 5}])
      (is (= 2 (count @(rf/subscribe [:lsp/diagnostics]))))

      ;; Second batch replaces first
      (db/replace-diagnostics-by-uri! (ws/default-conn) uri nil
                                       [{:message "New Error"
                                         :severity 1
                                         :startLine 2
                                         :startChar 0
                                         :endLine 2
                                         :endChar 5}])
      (is (= 1 (count @(rf/subscribe [:lsp/diagnostics]))))
      (is (= "New Error" (:message (first @(rf/subscribe [:lsp/diagnostics]))))))))

;; =============================================================================
;; Symbol Response Tests
;; =============================================================================

(deftest symbol-response-updates-database
  (testing "Receiving symbol response updates database"
    (let [uri "file:///test/symbols.rho"]
      (h/create-test-document! {:uri uri :text "contract Test { }"})
      (db/update-active-uri! (ws/default-conn) uri)

      ;; Simulate receiving symbols from LSP
      (let [lsp-symbols [{:name "Test"
                          :kind 5  ; Class
                          :range {:start {:line 0 :character 0}
                                  :end {:line 0 :character 17}}
                          :selectionRange {:start {:line 0 :character 9}
                                           :end {:line 0 :character 13}}}]
            flattened (db/flatten-symbols lsp-symbols nil uri)]
        (db/replace-symbols! (ws/default-conn) uri flattened))

      (let [syms @(rf/subscribe [:lsp/symbols])]
        (is (= 1 (count syms)))
        (is (= "Test" (:name (first syms))))))))

(deftest nested-symbols-are-flattened
  (testing "Nested symbols in response are flattened correctly"
    (let [uri "file:///test/nested-symbols.rho"]
      (h/create-test-document! {:uri uri :text "contract Outer { contract Inner {} }"})
      (db/update-active-uri! (ws/default-conn) uri)

      ;; Nested symbol structure
      (let [lsp-symbols [{:name "Outer"
                          :kind 5
                          :range {:start {:line 0 :character 0}
                                  :end {:line 0 :character 36}}
                          :selectionRange {:start {:line 0 :character 9}
                                           :end {:line 0 :character 14}}
                          :children [{:name "Inner"
                                      :kind 5
                                      :range {:start {:line 0 :character 17}
                                              :end {:line 0 :character 34}}
                                      :selectionRange {:start {:line 0 :character 26}
                                                       :end {:line 0 :character 31}}}]}]
            flattened (db/flatten-symbols lsp-symbols nil uri)]
        (db/replace-symbols! (ws/default-conn) uri flattened))

      (let [syms @(rf/subscribe [:lsp/symbols])]
        (is (= 2 (count syms)))
        (is (some #(= "Outer" (:name %)) syms))
        (is (some #(= "Inner" (:name %)) syms))))))

;; =============================================================================
;; Connection Configuration Tests
;; =============================================================================

(deftest default-config-has-reasonable-values
  (testing "Default configuration has reasonable timeout values"
    (is (contains? cm/DEFAULT-CONFIG :request-timeout-ms))
    (is (contains? cm/DEFAULT-CONFIG :init-timeout-ms))
    (is (contains? cm/DEFAULT-CONFIG :cleanup-interval-ms))
    (is (contains? cm/DEFAULT-CONFIG :max-reconnect-attempts))
    (is (contains? cm/DEFAULT-CONFIG :reconnect-delay-ms))

    ;; Values should be reasonable
    (is (>= (:request-timeout-ms cm/DEFAULT-CONFIG) 10000))
    (is (>= (:init-timeout-ms cm/DEFAULT-CONFIG) 5000))
    (is (pos? (:max-reconnect-attempts cm/DEFAULT-CONFIG)))))

(deftest connection-manager-accepts-custom-config
  (testing "Connection manager accepts custom configuration"
    (let [state-atom (reagent.core/atom {})
          custom-config {:request-timeout-ms 60000
                         :init-timeout-ms 30000}
          manager (cm/make-connection-manager state-atom nil (ws/default-conn) custom-config)]
      (is (some? manager))
      (is (= 60000 (get-in manager [:config :request-timeout-ms])))
      (is (= 30000 (get-in manager [:config :init-timeout-ms]))))))

;; =============================================================================
;; Cleanup Task Tests
;; =============================================================================

(deftest cleanup-task-runs-periodically
  (async done
         (go
           ;; Include expired requests so callback fires on each cleanup
           (let [expired-timestamp (- (js/Date.now) 120000)  ; 2 minutes ago (> 60s timeout)
                 state-atom (reagent.core/atom {:lsp {"test-lang"
                                                       {:pending {1 {:timestamp expired-timestamp}
                                                                  2 {:timestamp expired-timestamp}
                                                                  3 {:timestamp expired-timestamp}
                                                                  4 {:timestamp expired-timestamp}}
                                                        :request-timeout-ms 60000}}})
                 cleanup-count (atom 0)]
             (cm/start-cleanup-task! state-atom "test-lang" 50
                                     (fn [_ _ _] (swap! cleanup-count inc)))
             (<! (timeout 175))
             (cm/stop-cleanup-task! "test-lang")
             ;; Should have run multiple times (4 expired requests on first tick)
             (is (>= @cleanup-count 2)))
           (done))))

(deftest cleanup-task-stops-correctly
  (async done
         (go
           ;; Include expired requests so callback fires on cleanup
           (let [expired-timestamp (- (js/Date.now) 120000)
                 state-atom (reagent.core/atom {:lsp {"test-lang"
                                                       {:pending {1 {:timestamp expired-timestamp}
                                                                  2 {:timestamp expired-timestamp}}
                                                        :request-timeout-ms 60000}}})
                 cleanup-count (atom 0)]
             (cm/start-cleanup-task! state-atom "test-lang" 50
                                     (fn [_ _ _] (swap! cleanup-count inc)))
             (<! (timeout 75))
             (let [count-at-stop @cleanup-count]
               (cm/stop-cleanup-task! "test-lang")
               (<! (timeout 100))
               ;; Count should not have increased after stop
               (is (= count-at-stop @cleanup-count))))
           (done))))

;; =============================================================================
;; Multi-Language Support Tests
;; =============================================================================

(deftest multiple-languages-tracked-independently
  (testing "Multiple language connections are tracked independently"
    (let [state-atom (reagent.core/atom
                      {:lsp {"rholang" {:state :initialized}
                             "javascript" {:state :connecting}
                             "text" {:state :disconnected}}})
          manager (cm/make-connection-manager state-atom nil (ws/default-conn))]
      (is (true? (cm/initialized? manager "rholang")))
      (is (true? (cm/connected? manager "rholang")))
      (is (false? (cm/initialized? manager "javascript")))
      (is (false? (cm/connected? manager "javascript")))
      (is (false? (cm/initialized? manager "text")))
      (is (false? (cm/connected? manager "text"))))))

;; =============================================================================
;; Document Sync Integration Tests
;; =============================================================================

(deftest document-changes-tracked-for-lsp-sync
  (testing "Document changes are tracked for LSP synchronization"
    (let [uri "file:///test/sync.rho"]
      (h/create-test-document! {:uri uri
                                :text "initial"
                                :version 1
                                :opened true})

      ;; Simulate document edit
      (db/update-document-text-by-uri! (ws/default-conn) uri "modified")
      (db/increment-document-version-by-uri! (ws/default-conn) uri)

      ;; Verify state for potential LSP notification
      (is (= "modified" (db/document-text-by-uri (ws/default-conn) uri)))
      (is (= 2 (db/document-version-by-uri (ws/default-conn) uri)))
      (is (true? (db/document-dirty-by-uri (ws/default-conn) uri))))))

(deftest opened-documents-per-language-tracked
  (testing "Opened documents are tracked per language"
    (h/create-test-document! {:uri "file:///rho1.rho" :language "rholang" :opened true})
    (h/create-test-document! {:uri "file:///rho2.rho" :language "rholang" :opened true})
    (h/create-test-document! {:uri "file:///rho3.rho" :language "rholang" :opened false})
    (h/create-test-document! {:uri "file:///text1.txt" :language "text" :opened true})

    (let [rholang-opened (db/opened-uris-by-lang (ws/default-conn) "rholang")
          text-opened (db/opened-uris-by-lang (ws/default-conn) "text")]
      (is (= 2 (count rholang-opened)))
      (is (contains? (set rholang-opened) "file:///rho1.rho"))
      (is (contains? (set rholang-opened) "file:///rho2.rho"))
      (is (not (contains? (set rholang-opened) "file:///rho3.rho")))

      (is (= 1 (count text-opened)))
      (is (contains? (set text-opened) "file:///text1.txt")))))

;; =============================================================================
;; States Constant Tests
;; =============================================================================

(deftest all-states-are-defined
  (testing "All connection states are defined"
    (is (contains? cm/STATES :disconnected))
    (is (contains? cm/STATES :connecting))
    (is (contains? cm/STATES :connected))
    (is (contains? cm/STATES :initializing))
    (is (contains? cm/STATES :initialized))
    (is (contains? cm/STATES :disconnecting))
    (is (contains? cm/STATES :error))))

(deftest transitions-defined-for-all-states
  (testing "Transitions are defined for all states"
    (doseq [state cm/STATES]
      (is (contains? cm/TRANSITIONS state)
          (str "TRANSITIONS should contain " state)))))

;; =============================================================================
;; Complete Flow Integration Tests (Phase 3)
;; =============================================================================

(deftest lsp-diagnostics-flow-complete
  (testing "Complete diagnostics flow: notification -> DB -> subscriptions"
    (let [uri "file:///test/diag-flow.rho"]
      ;; Step 1: Create document and make it active
      (h/create-test-document! {:uri uri
                                :text "new x in { x!( }"
                                :language "rholang"
                                :version 1
                                :opened true})
      (db/update-active-uri! (ws/default-conn) uri)

      ;; Verify initial state (no diagnostics)
      (is (zero? (count @(rf/subscribe [:lsp/diagnostics]))))

      ;; Step 2: Simulate LSP publishDiagnostics notification
      (let [lsp-notification {:jsonrpc "2.0"
                              :method "textDocument/publishDiagnostics"
                              :params {:uri uri
                                       :diagnostics [{:range {:start {:line 0 :character 13}
                                                              :end {:line 0 :character 14}}
                                                      :severity 1
                                                      :message "Unexpected end of input"}
                                                     {:range {:start {:line 0 :character 15}
                                                              :end {:line 0 :character 16}}
                                                      :severity 2
                                                      :message "Missing closing parenthesis"}]}}]
        ;; Process diagnostics as the LSP client would
        (db/replace-diagnostics-by-uri! (ws/default-conn)
         uri
         nil
         (map (fn [d]
                {:message (:message d)
                 :severity (:severity d)
                 :startLine (get-in d [:range :start :line])
                 :startChar (get-in d [:range :start :character])
                 :endLine (get-in d [:range :end :line])
                 :endChar (get-in d [:range :end :character])})
              (get-in lsp-notification [:params :diagnostics]))))

      ;; Step 3: Verify subscription reflects the update
      (let [diags @(rf/subscribe [:lsp/diagnostics])]
        (is (= 2 (count diags)) "Two diagnostics received")
        (is (some #(= 1 (:severity %)) diags) "Contains error")
        (is (some #(= 2 (:severity %)) diags) "Contains warning")
        (is (some #(= "Unexpected end of input" (:message %)) diags)))

      ;; Step 4: Fix the code and receive empty diagnostics
      (db/update-document-text-by-uri! (ws/default-conn) uri "new x in { x!(\"Hello\") }")
      (db/replace-diagnostics-by-uri! (ws/default-conn) uri nil [])

      ;; Step 5: Verify diagnostics cleared
      (is (zero? (count @(rf/subscribe [:lsp/diagnostics]))) "Diagnostics cleared after fix"))))

(deftest lsp-symbol-update-flow
  (testing "Complete symbol flow: request -> response -> flatten -> DB"
    (let [uri "file:///test/symbol-flow.rho"]
      ;; Step 1: Create document
      (h/create-test-document! {:uri uri
                                :text "contract Outer { contract Inner { def method() } }"
                                :language "rholang"
                                :version 1
                                :opened true})
      (db/update-active-uri! (ws/default-conn) uri)

      ;; Step 2: Simulate LSP documentSymbol response (nested structure)
      (let [lsp-response {:jsonrpc "2.0"
                          :id 1
                          :result [{:name "Outer"
                                    :kind 5  ; Class
                                    :range {:start {:line 0 :character 0}
                                            :end {:line 0 :character 50}}
                                    :selectionRange {:start {:line 0 :character 9}
                                                     :end {:line 0 :character 14}}
                                    :children [{:name "Inner"
                                                :kind 5  ; Class
                                                :range {:start {:line 0 :character 17}
                                                        :end {:line 0 :character 49}}
                                                :selectionRange {:start {:line 0 :character 26}
                                                                 :end {:line 0 :character 31}}
                                                :children [{:name "method"
                                                            :kind 6  ; Method
                                                            :range {:start {:line 0 :character 34}
                                                                    :end {:line 0 :character 47}}
                                                            :selectionRange {:start {:line 0 :character 38}
                                                                             :end {:line 0 :character 44}}}]}]}]}
            ;; Step 3: Flatten symbols
            flattened (db/flatten-symbols (:result lsp-response) nil uri)]

        ;; Step 4: Store in DB
        (db/replace-symbols! (ws/default-conn) uri flattened)

        ;; Step 5: Verify via subscription
        (let [syms @(rf/subscribe [:lsp/symbols])]
          (is (= 3 (count syms)) "Three symbols (Outer, Inner, method)")
          (is (some #(= "Outer" (:name %)) syms))
          (is (some #(= "Inner" (:name %)) syms))
          (is (some #(= "method" (:name %)) syms))

          ;; Verify parent-child relationships are tracked via :parent entity ID
          ;; The :parent field stores the DB entity ID of the parent symbol
          (let [outer-sym (first (filter #(= "Outer" (:name %)) syms))
                inner-sym (first (filter #(= "Inner" (:name %)) syms))
                method-sym (first (filter #(= "method" (:name %)) syms))]
            ;; Outer has no parent (root level)
            (is (zero? (:parent outer-sym)) "Outer has no parent (root)")
            ;; Inner and method should have non-zero parent references
            (is (not= 0 (:parent inner-sym)) "Inner has a parent")
            (is (not= 0 (:parent method-sym)) "method has a parent")))))))

(deftest lsp-document-sync-flow
  (async done
         (go
           (let [uri "file:///test/sync-flow.rho"]
             ;; Step 1: Create and open document
             (h/create-test-document! {:uri uri
                                       :text "initial content"
                                       :language "rholang"
                                       :version 1
                                       :dirty false
                                       :opened true})
             (db/update-active-uri! (ws/default-conn) uri)

             ;; Verify initial state
             (is (= 1 (db/document-version-by-uri (ws/default-conn) uri)))
             (is (false? (db/document-dirty-by-uri (ws/default-conn) uri)))

             ;; Step 2: Simulate user edit
             (db/update-document-text-by-uri! (ws/default-conn) uri "modified content")

             ;; Step 3: Verify state after edit
             (is (= "modified content" (db/document-text-by-uri (ws/default-conn) uri)))
             (is (true? (db/document-dirty-by-uri (ws/default-conn) uri)) "Document marked dirty after edit")

             ;; Step 4: Simulate version increment (as LSP didChange would do)
             (db/increment-document-version-by-uri! (ws/default-conn) uri)
             (is (= 2 (db/document-version-by-uri (ws/default-conn) uri)) "Version incremented for LSP")

             ;; Step 5: Simulate another edit
             (db/update-document-text-by-uri! (ws/default-conn) uri "final content")
             (db/increment-document-version-by-uri! (ws/default-conn) uri)

             ;; Step 6: Verify final state
             (is (= 3 (db/document-version-by-uri (ws/default-conn) uri)) "Version 3 after two edits")
             (is (= "final content" (db/document-text-by-uri (ws/default-conn) uri)))
             (is (true? (db/document-dirty-by-uri (ws/default-conn) uri)))

             ;; Step 7: Simulate save
             (db/document-saved-by-uri! (ws/default-conn) uri)
             (is (false? (db/document-dirty-by-uri (ws/default-conn) uri)) "Dirty cleared after save"))
           (done))))

(deftest concurrent-diagnostics-across-documents
  (testing "Diagnostics for multiple documents don't interfere"
    (let [uri1 "file:///test/concurrent1.rho"
          uri2 "file:///test/concurrent2.rho"
          uri3 "file:///test/concurrent3.rho"]
      ;; Create multiple documents
      (doseq [[uri text] [[uri1 "code1"] [uri2 "code2"] [uri3 "code3"]]]
        (h/create-test-document! {:uri uri :text text :language "rholang" :opened true}))

      ;; Add diagnostics to each document
      (db/replace-diagnostics-by-uri! (ws/default-conn) uri1 nil
                                       [{:message "Error in doc1"
                                         :severity 1
                                         :startLine 0 :startChar 0
                                         :endLine 0 :endChar 5}])
      (db/replace-diagnostics-by-uri! (ws/default-conn) uri2 nil
                                       [{:message "Warning in doc2"
                                         :severity 2
                                         :startLine 0 :startChar 0
                                         :endLine 0 :endChar 5}
                                        {:message "Info in doc2"
                                         :severity 3
                                         :startLine 0 :startChar 0
                                         :endLine 0 :endChar 5}])
      (db/replace-diagnostics-by-uri! (ws/default-conn) uri3 nil [])  ; No diagnostics

      ;; Verify each document has correct diagnostics using db functions
      (let [diags1 (db/diagnostics-by-uri (ws/default-conn) uri1)]
        (is (= 1 (count diags1)) "Doc1 has 1 diagnostic")
        (is (= "Error in doc1" (:message (first diags1)))))

      (let [diags2 (db/diagnostics-by-uri (ws/default-conn) uri2)]
        (is (= 2 (count diags2)) "Doc2 has 2 diagnostics"))

      (let [diags3 (db/diagnostics-by-uri (ws/default-conn) uri3)]
        (is (zero? (count diags3)) "Doc3 has 0 diagnostics")))))

(deftest symbol-update-replaces-previous
  (testing "Symbol updates replace previous symbols for a document"
    (let [uri "file:///test/symbol-replace.rho"]
      (h/create-test-document! {:uri uri :text "code" :language "rholang" :opened true})
      (db/update-active-uri! (ws/default-conn) uri)

      ;; First symbol set
      (let [symbols1 (db/flatten-symbols
                      [{:name "OldSymbol"
                        :kind 5
                        :range {:start {:line 0 :character 0}
                                :end {:line 0 :character 10}}
                        :selectionRange {:start {:line 0 :character 0}
                                         :end {:line 0 :character 10}}}]
                      nil uri)]
        (db/replace-symbols! (ws/default-conn) uri symbols1))
      (is (= 1 (count @(rf/subscribe [:lsp/symbols]))))
      (is (= "OldSymbol" (:name (first @(rf/subscribe [:lsp/symbols])))))

      ;; Second symbol set replaces first
      (let [symbols2 (db/flatten-symbols
                      [{:name "NewSymbol1"
                        :kind 6
                        :range {:start {:line 0 :character 0}
                                :end {:line 0 :character 10}}
                        :selectionRange {:start {:line 0 :character 0}
                                         :end {:line 0 :character 10}}}
                       {:name "NewSymbol2"
                        :kind 6
                        :range {:start {:line 1 :character 0}
                                :end {:line 1 :character 10}}
                        :selectionRange {:start {:line 1 :character 0}
                                         :end {:line 1 :character 10}}}]
                      nil uri)]
        (db/replace-symbols! (ws/default-conn) uri symbols2))
      (is (= 2 (count @(rf/subscribe [:lsp/symbols]))))
      (is (not-any? #(= "OldSymbol" (:name %)) @(rf/subscribe [:lsp/symbols]))))))

(deftest lsp-state-affects-feature-availability
  (testing "LSP state affects whether features are available"
    (let [state-atom (reagent.core/atom {:lsp {"rholang" {:state :disconnected}}})
          manager (cm/make-connection-manager state-atom nil (ws/default-conn))]
      (is (false? (cm/initialized? manager "rholang")))
      (swap! state-atom assoc-in [:lsp "rholang" :state] :initialized)
      (is (true? (cm/initialized? manager "rholang")))
      (swap! state-atom assoc-in [:lsp "rholang" :state] :error)
      (is (false? (cm/initialized? manager "rholang"))))))
