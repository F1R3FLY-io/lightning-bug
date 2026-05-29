(ns test.lib.lsp.connection-manager-test
  "Tests for the LSP connection manager module."
  (:require
   [clojure.test :refer [deftest is testing use-fixtures async]]
   [clojure.core.async :refer [go <! timeout]]
   [reagent.core :as r]
   [lib.lsp.connection-manager :as cm]
   [lib.lsp.client :as lsp]
   [domain.protocols :as p]))

;; =============================================================================
;; Fixtures
;; =============================================================================

(use-fixtures :each
  {:before cm/stop-all-cleanup-tasks!
   :after cm/stop-all-cleanup-tasks!})

;; =============================================================================
;; State Machine Tests
;; =============================================================================

(deftest valid-transition?-allows-valid-transitions
  (testing "Valid transitions from disconnected"
    (is (true? (cm/valid-transition? :disconnected :connecting))))

  (testing "Valid transitions from connecting"
    (is (true? (cm/valid-transition? :connecting :connected)))
    (is (true? (cm/valid-transition? :connecting :error)))
    (is (true? (cm/valid-transition? :connecting :disconnected))))

  (testing "Valid transitions from connected"
    (is (true? (cm/valid-transition? :connected :initializing)))
    (is (true? (cm/valid-transition? :connected :disconnecting)))
    (is (true? (cm/valid-transition? :connected :error))))

  (testing "Valid transitions from initializing"
    (is (true? (cm/valid-transition? :initializing :initialized)))
    (is (true? (cm/valid-transition? :initializing :error)))
    (is (true? (cm/valid-transition? :initializing :disconnected))))

  (testing "Valid transitions from initialized"
    (is (true? (cm/valid-transition? :initialized :disconnecting)))
    (is (true? (cm/valid-transition? :initialized :error)))
    (is (true? (cm/valid-transition? :initialized :disconnected))))

  (testing "Valid transitions from disconnecting"
    (is (true? (cm/valid-transition? :disconnecting :disconnected))))

  (testing "Valid transitions from error"
    (is (true? (cm/valid-transition? :error :disconnected)))
    (is (true? (cm/valid-transition? :error :connecting)))))

(deftest valid-transition?-rejects-invalid-transitions
  (testing "Invalid transitions from disconnected"
    (is (false? (cm/valid-transition? :disconnected :connected)))
    (is (false? (cm/valid-transition? :disconnected :initialized)))
    (is (false? (cm/valid-transition? :disconnected :disconnecting))))

  (testing "Invalid transitions from connecting"
    (is (false? (cm/valid-transition? :connecting :initialized)))
    (is (false? (cm/valid-transition? :connecting :initializing))))

  (testing "Invalid transitions from connected"
    (is (false? (cm/valid-transition? :connected :initialized)))
    (is (false? (cm/valid-transition? :connected :connecting))))

  (testing "Invalid transitions from initializing"
    (is (false? (cm/valid-transition? :initializing :connected)))
    (is (false? (cm/valid-transition? :initializing :connecting))))

  (testing "Invalid transitions from initialized"
    (is (false? (cm/valid-transition? :initialized :connecting)))
    (is (false? (cm/valid-transition? :initialized :connected))))

  (testing "Invalid transitions from disconnecting"
    (is (false? (cm/valid-transition? :disconnecting :connected)))
    (is (false? (cm/valid-transition? :disconnecting :initialized))))

  (testing "Invalid transitions from error"
    (is (false? (cm/valid-transition? :error :connected)))
    (is (false? (cm/valid-transition? :error :initialized)))))

;; =============================================================================
;; Pending Request Tests
;; =============================================================================

(deftest make-pending-request-creates-record
  (testing "Creating a pending request record"
    (let [request (cm/make-pending-request 1 :document-symbol "file:///test.rho" 5000)]
      (is (= 1 (:id request)))
      (is (= :document-symbol (:type request)))
      (is (= "file:///test.rho" (:uri request)))
      (is (= 5000 (:timeout-ms request)))
      (is (number? (:timestamp request))))))

(deftest request-expired?-detects-timeout
  (testing "Fresh request is not expired"
    (let [request (cm/make-pending-request 1 :test "uri" 1000)]
      (is (false? (cm/request-expired? request)))))

  (testing "Old request is expired"
    (let [request (cm/->PendingRequest 1 :test "uri" (- (js/Date.now) 2000) 1000)]
      (is (true? (cm/request-expired? request))))))

(deftest cleanup-stale-requests!-removes-expired
  (testing "Cleanup removes expired requests"
    (let [state-atom (r/atom {:lsp {"test-lang" {:pending {1 {:timestamp (- (js/Date.now) 120000)}}
                                                  :request-timeout-ms 60000}}})
          removed (atom nil)]
      (cm/cleanup-stale-requests! state-atom "test-lang"
                                  (fn [_lang id _info]
                                    (reset! removed id)))
      (is (empty? (get-in @state-atom [:lsp "test-lang" :pending])))
      (is (= 1 @removed)))))

(deftest cleanup-stale-requests!-keeps-valid
  (testing "Cleanup keeps non-expired requests"
    (let [state-atom (r/atom {:lsp {"test-lang" {:pending {1 {:timestamp (js/Date.now)}}
                                                  :request-timeout-ms 60000}}})]
      (cm/cleanup-stale-requests! state-atom "test-lang" nil)
      (is (= 1 (count (get-in @state-atom [:lsp "test-lang" :pending])))))))

;; =============================================================================
;; Connection Manager Factory Tests
;; =============================================================================

(deftest make-connection-manager-creates-manager
  (testing "Creating a connection manager"
    (let [state-atom (r/atom {})
          events (js/Object.)  ; Mock RxJS Subject
          manager (cm/make-connection-manager state-atom events)]
      (is (some? manager))
      (is (satisfies? p/ILspClient manager)))))

(deftest make-connection-manager-with-custom-config
  (testing "Creating with custom configuration"
    (let [state-atom (r/atom {})
          events (js/Object.)
          config {:request-timeout-ms 30000
                  :init-timeout-ms 15000}
          manager (cm/make-connection-manager state-atom events config)]
      (is (some? manager))
      (is (= 30000 (get-in manager [:config :request-timeout-ms])))
      (is (= 15000 (get-in manager [:config :init-timeout-ms]))))))

;; =============================================================================
;; Protocol Implementation Tests
;; =============================================================================

(deftest connection-manager-connected?-checks-state
  (testing "connected? returns true for appropriate states"
    (let [state-atom (r/atom {:lsp {"test" {:state :initialized}}})
          manager (cm/make-connection-manager state-atom nil)]
      (is (true? (p/connected? manager "test")))))

  (testing "connected? returns true for initializing state"
    (let [state-atom (r/atom {:lsp {"test" {:state :initializing}}})
          manager (cm/make-connection-manager state-atom nil)]
      (is (true? (p/connected? manager "test")))))

  (testing "connected? returns false for disconnected state"
    (let [state-atom (r/atom {:lsp {"test" {:state :disconnected}}})
          manager (cm/make-connection-manager state-atom nil)]
      (is (false? (p/connected? manager "test")))))

  (testing "connected? returns false for missing language"
    (let [state-atom (r/atom {})
          manager (cm/make-connection-manager state-atom nil)]
      (is (false? (p/connected? manager "nonexistent"))))))

(deftest connection-manager-initialized?-checks-state
  (testing "initialized? returns true only for initialized state"
    (let [state-atom (r/atom {:lsp {"test" {:state :initialized}}})
          manager (cm/make-connection-manager state-atom nil)]
      (is (true? (p/initialized? manager "test")))))

  (testing "initialized? returns false for other states"
    (let [state-atom (r/atom {:lsp {"test" {:state :connecting}}})
          manager (cm/make-connection-manager state-atom nil)]
      (is (false? (p/initialized? manager "test"))))))

;; =============================================================================
;; Cleanup Task Tests
;; =============================================================================

(deftest start-cleanup-task!-creates-interval
  (async done
         (go
           ;; Include expired requests so callback fires on each cleanup
           (let [expired-timestamp (- (js/Date.now) 120000)  ; 2 minutes ago (> 60s timeout)
                 state-atom (r/atom {:lsp {"test-lang"
                                           {:pending {1 {:timestamp expired-timestamp}
                                                      2 {:timestamp expired-timestamp}
                                                      3 {:timestamp expired-timestamp}
                                                      4 {:timestamp expired-timestamp}}
                                            :request-timeout-ms 60000}}})
                 calls (atom 0)]
             (cm/start-cleanup-task! state-atom "test-lang" 50
                                     (fn [_ _ _] (swap! calls inc)))
             (<! (timeout 150))
             (cm/stop-cleanup-task! "test-lang")
             ;; Should have been called at least twice in 150ms with 50ms interval
             ;; (4 expired requests cleared on first tick, so at least 4 calls)
             (is (>= @calls 2)))
           (done))))

(deftest stop-cleanup-task!-stops-interval
  (async done
         (go
           ;; Include expired requests so callback fires on cleanup
           (let [expired-timestamp (- (js/Date.now) 120000)
                 state-atom (r/atom {:lsp {"test-lang"
                                           {:pending {1 {:timestamp expired-timestamp}
                                                      2 {:timestamp expired-timestamp}}
                                            :request-timeout-ms 60000}}})
                 calls (atom 0)]
             (cm/start-cleanup-task! state-atom "test-lang" 50
                                     (fn [_ _ _] (swap! calls inc)))
             (<! (timeout 75))
             (cm/stop-cleanup-task! "test-lang")
             (let [count-after-stop @calls]
               (<! (timeout 100))
               ;; Should not have increased after stop
               (is (= count-after-stop @calls))))
           (done))))

(deftest stop-all-cleanup-tasks!-stops-all
  (testing "stop-all-cleanup-tasks! clears all intervals"
    (let [state-atom (r/atom {:lsp {"lang1" {:pending {}}
                                    "lang2" {:pending {}}}})]
      (cm/start-cleanup-task! state-atom "lang1" 100 nil)
      (cm/start-cleanup-task! state-atom "lang2" 100 nil)
      (cm/stop-all-cleanup-tasks!)
      ;; Further calls to stop should be no-ops
      (cm/stop-cleanup-task! "lang1")
      (cm/stop-cleanup-task! "lang2"))))

;; =============================================================================
;; Timeout Wrapper Tests
;; =============================================================================

(deftest with-timeout-returns-result-before-timeout
  (async done
         (go
           (let [fast-op (go [:ok "fast result"])
                 result (<! (cm/with-timeout fast-op 1000 "Test operation"))]
             (is (= [:ok "fast result"] result)))
           (done))))

(deftest with-timeout-returns-error-on-timeout
  (async done
         (go
           (let [slow-op (go
                           (<! (timeout 500))
                           [:ok "slow result"])
                 result (<! (cm/with-timeout slow-op 100 "Test operation"))]
             (is (= [:error :timeout] result)))
           (done))))

;; =============================================================================
;; Configuration Tests
;; =============================================================================

(deftest default-config-has-expected-values
  (testing "DEFAULT-CONFIG contains all expected keys"
    (is (contains? cm/DEFAULT-CONFIG :request-timeout-ms))
    (is (contains? cm/DEFAULT-CONFIG :init-timeout-ms))
    (is (contains? cm/DEFAULT-CONFIG :cleanup-interval-ms))
    (is (contains? cm/DEFAULT-CONFIG :max-reconnect-attempts))
    (is (contains? cm/DEFAULT-CONFIG :reconnect-delay-ms))
    (is (contains? cm/DEFAULT-CONFIG :reconnect-backoff-factor))))

(deftest default-config-values-are-reasonable
  (testing "DEFAULT-CONFIG values are within reasonable ranges"
    (is (>= (:request-timeout-ms cm/DEFAULT-CONFIG) 10000))
    (is (<= (:request-timeout-ms cm/DEFAULT-CONFIG) 120000))
    (is (>= (:init-timeout-ms cm/DEFAULT-CONFIG) 5000))
    (is (pos? (:max-reconnect-attempts cm/DEFAULT-CONFIG)))
    (is (pos? (:reconnect-delay-ms cm/DEFAULT-CONFIG)))
    (is (> (:reconnect-backoff-factor cm/DEFAULT-CONFIG) 1))))

;; =============================================================================
;; States Constant Tests
;; =============================================================================

(deftest states-contains-all-expected
  (testing "STATES contains all expected connection states"
    (is (contains? cm/STATES :disconnected))
    (is (contains? cm/STATES :connecting))
    (is (contains? cm/STATES :connected))
    (is (contains? cm/STATES :initializing))
    (is (contains? cm/STATES :initialized))
    (is (contains? cm/STATES :disconnecting))
    (is (contains? cm/STATES :error))))

(deftest transitions-covers-all-states
  (testing "TRANSITIONS has entries for all states"
    (doseq [state cm/STATES]
      (is (contains? cm/TRANSITIONS state)
          (str "TRANSITIONS should contain " state)))))

;; =============================================================================
;; ILspClient Delegation Tests (Phase 2 — CM is now the live per-editor client)
;; =============================================================================

(deftest connect-supplier-returns-resource-supplier
  (testing "connect-supplier returns a 0-arg fn that calls lsp/connect with this client's state-atom + events"
    (let [state-atom (r/atom {})
          events (js/Object.)
          manager (cm/make-connection-manager state-atom events)
          captured (atom nil)]
      (with-redefs [lsp/connect (fn [lang config sa ev]
                                  (reset! captured {:lang lang :config config :sa sa :ev ev})
                                  :connect-ch)]
        (let [supplier (p/connect-supplier manager "rholang" "ws://localhost:1234")]
          (is (fn? supplier))
          (is (= :connect-ch (supplier)))
          (is (= {:lang "rholang" :config {:url "ws://localhost:1234"} :sa state-atom :ev events}
                 @captured)))))))

(deftest notify-did-change-incremental!-delegates
  (testing "notify-did-change-incremental! passes through to lsp with the client's state-atom"
    (let [state-atom (r/atom {})
          manager (cm/make-connection-manager state-atom nil)
          captured (atom nil)]
      (with-redefs [lsp/notify-did-change-incremental
                    (fn [lang uri changes version sa] (reset! captured [lang uri changes version sa]))]
        (p/notify-did-change-incremental! manager "rholang" "file:///a.rho" [{:text "x"}] 3)
        (is (= ["rholang" "file:///a.rho" [{:text "x"}] 3 state-atom] @captured))))))

(deftest shutdown-all!-uses-1-arity
  (testing "shutdown-all! calls lsp/request-shutdown with only the state-atom (1-arity = all languages)"
    (let [state-atom (r/atom {:lsp {"a" {} "b" {}}})
          manager (cm/make-connection-manager state-atom nil)
          captured (atom nil)]
      (with-redefs [lsp/request-shutdown (fn ([sa] (reset! captured [sa]))
                                           ([lang sa] (reset! captured [lang sa])))]
        (p/shutdown-all! manager)
        (is (= [state-atom] @captured))))))

(deftest request-shutdown!-is-thin-pass-through
  (testing "request-shutdown! delegates with [language state-atom] and does NOT run the state machine"
    (let [state-atom (r/atom {:lsp {"rholang" {:state :initialized}}})
          manager (cm/make-connection-manager state-atom nil)
          captured (atom nil)]
      (with-redefs [lsp/request-shutdown (fn ([sa] (reset! captured [sa]))
                                           ([lang sa] (reset! captured [lang sa])))]
        (p/request-shutdown! manager "rholang")
        (is (= ["rholang" state-atom] @captured))
        (is (= :initialized (get-in @state-atom [:lsp "rholang" :state]))
            "narrowed: no transition to :disconnecting/:disconnected")))))

(deftest request-symbols!-is-unguarded-pass-through
  (testing "request-symbols! delegates even when not initialized (matches lib.core's live behavior)"
    (let [state-atom (r/atom {:lsp {"rholang" {:state :disconnected}}})
          manager (cm/make-connection-manager state-atom nil)
          captured (atom nil)]
      (with-redefs [lsp/request-document-symbol (fn [& args] (reset! captured (vec args)))]
        (p/request-symbols! manager "rholang" "file:///a.rho")
        (is (= ["rholang" "file:///a.rho" state-atom] @captured))))))
