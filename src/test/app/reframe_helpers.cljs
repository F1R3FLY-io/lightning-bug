(ns test.app.reframe-helpers
  "Re-Frame test utilities for testing events, subscriptions, and effects.

   Provides fixtures for resetting app-db, capturing effects,
   and mocking coeffects for isolated unit testing."
  (:require [clojure.test :refer [is]]
            [clojure.core.async :refer [go <! timeout promise-chan put!]]
            [re-frame.core :as rf]
            [re-frame.db :as rf-db]
            [re-frame.registrar :as rf-registrar]
            [app.db :refer [default-db]]
            [app.events]  ; Ensure events are registered
            [app.subs]))  ; Ensure subs are registered

;; =============================================================================
;; Re-Frame Fixtures
;; =============================================================================

(defn reset-app-db!
  "Resets the Re-Frame app-db to default state."
  []
  (reset! rf-db/app-db default-db))

(defn with-clean-reframe
  "Fixture that resets app-db before test."
  [f]
  (reset-app-db!)
  (f))

(defn set-app-db!
  "Sets the Re-Frame app-db to a specific state."
  [state]
  (reset! rf-db/app-db state))

(defn get-app-db
  "Returns the current Re-Frame app-db state."
  []
  @rf-db/app-db)

(defn update-app-db!
  "Updates the Re-Frame app-db with a function."
  [f & args]
  (swap! rf-db/app-db #(apply f % args)))

;; =============================================================================
;; Event Dispatch Helpers
;; =============================================================================

(defn dispatch-sync
  "Dispatches an event synchronously and returns the new db state."
  [event]
  (rf/dispatch-sync event)
  @rf-db/app-db)

(defn dispatch-sync-wait
  "Dispatches an event synchronously and waits for predicate to be true.
   Returns a channel with [:ok value] or [:error :timeout]."
  [event pred timeout-ms]
  (let [result-ch (promise-chan)]
    (rf/dispatch-sync event)
    (go
      (let [start (js/Date.now)]
        (loop []
          (if-let [value (pred @rf-db/app-db)]
            (put! result-ch [:ok value])
            (if (> (- (js/Date.now) start) timeout-ms)
              (put! result-ch [:error :timeout])
              (do
                (<! (timeout 10))
                (recur)))))))
    result-ch))

;; =============================================================================
;; Effect Capture
;; =============================================================================

(def ^:private captured-effects (atom []))

(defn- capture-effect
  "Captures an effect invocation."
  [effect-key effect-value]
  (swap! captured-effects conj {:effect effect-key :value effect-value}))

(defn reset-captured-effects!
  "Clears all captured effects."
  []
  (reset! captured-effects []))

(defn get-captured-effects
  "Returns all captured effects."
  []
  @captured-effects)

(defn get-captured-effects-of-type
  "Returns captured effects of a specific type."
  [effect-key]
  (filter #(= effect-key (:effect %)) @captured-effects))

(defn effect-was-triggered?
  "Returns true if the specified effect was triggered."
  [effect-key]
  (some #(= effect-key (:effect %)) @captured-effects))

(defn effect-was-triggered-with?
  "Returns true if the specified effect was triggered with the given value."
  [effect-key expected-value]
  (some #(and (= effect-key (:effect %))
              (= expected-value (:value %)))
        @captured-effects))

;; =============================================================================
;; Effect Mocking
;; =============================================================================

(def ^:private original-effect-handlers (atom {}))

(defn mock-effect!
  "Registers a mock effect handler that captures invocations.
   Stores the original handler for restoration after the test."
  [effect-key]
  (let [original (rf-registrar/get-handler :fx effect-key)]
    (when original
      (swap! original-effect-handlers assoc effect-key original))
    (rf/reg-fx effect-key
               (fn [value]
                 (capture-effect effect-key value)))))

(defn mock-effect-with!
  "Registers a mock effect handler with custom behavior."
  [effect-key handler-fn]
  (let [original (rf-registrar/get-handler :fx effect-key)]
    (when original
      (swap! original-effect-handlers assoc effect-key original))
    (rf/reg-fx effect-key
               (fn [value]
                 (capture-effect effect-key value)
                 (handler-fn value)))))

(defn restore-effect!
  "Restores the original effect handler."
  [effect-key]
  (when-let [original (get @original-effect-handlers effect-key)]
    (rf/reg-fx effect-key original)
    (swap! original-effect-handlers dissoc effect-key)))

(defn restore-all-effects!
  "Restores all original effect handlers."
  []
  (doseq [[effect-key original] @original-effect-handlers]
    (rf/reg-fx effect-key original))
  (reset! original-effect-handlers {}))

;; =============================================================================
;; Coeffect Mocking
;; =============================================================================

(def ^:private original-cofx-handlers (atom {}))

(defn mock-coeffect!
  "Registers a mock coeffect that returns a fixed value.
   Maps namespaced keys to their actual coeffect keys to match real implementation."
  [cofx-key value]
  (let [original (rf-registrar/get-handler :cofx cofx-key)]
    (when original
      (swap! original-cofx-handlers assoc cofx-key original))
    (rf/reg-cofx cofx-key
                 (fn [coeffects _]
                   (let [key (case cofx-key
                               :logs/all :logs
                               :document-repo/active-document :active-document
                               :document-repo/active-uri :active-uri
                               :document-repo/documents :documents
                               :document-repo/document :document
                               :lsp/diagnostics :diagnostics
                               :lsp/diagnostics-by-uri :diagnostics
                               :lsp/symbols :symbols
                               :lsp/symbols-by-uri :symbols
                               :editor/ref :editor-ref
                               :editor/current :editor
                               :editor/ready? :editor-ready?
                               :now :now
                               ;; Default: strip namespace
                               (keyword (name cofx-key)))]
                     (assoc coeffects key value))))))

(defn mock-coeffect-with!
  "Registers a mock coeffect with custom handler function."
  [cofx-key handler-fn]
  (let [original (rf-registrar/get-handler :cofx cofx-key)]
    (when original
      (swap! original-cofx-handlers assoc cofx-key original))
    (rf/reg-cofx cofx-key handler-fn)))

(defn restore-coeffect!
  "Restores the original coeffect handler."
  [cofx-key]
  (when-let [original (get @original-cofx-handlers cofx-key)]
    (rf/reg-cofx cofx-key original)
    (swap! original-cofx-handlers dissoc cofx-key)))

(defn restore-all-coeffects!
  "Restores all original coeffect handlers."
  []
  (doseq [[cofx-key original] @original-cofx-handlers]
    (rf/reg-cofx cofx-key original))
  (reset! original-cofx-handlers {}))

;; =============================================================================
;; Subscription Testing
;; =============================================================================

(defn sub-value
  "Returns the current value of a subscription.
   Takes a subscription vector like [:my-sub arg1 arg2]."
  [sub-vec]
  @(rf/subscribe sub-vec))

(defn assert-sub-value
  "Asserts that a subscription returns the expected value."
  [sub-vec expected]
  (is (= expected (sub-value sub-vec))
      (str "Subscription " sub-vec " should return " expected)))

(defn wait-for-sub
  "Waits for a subscription to return a truthy value.
   Returns a channel with [:ok value] or [:error :timeout]."
  [sub-vec timeout-ms]
  (let [result-ch (promise-chan)]
    (go
      (let [start (js/Date.now)]
        (loop []
          (if-let [value (sub-value sub-vec)]
            (put! result-ch [:ok value])
            (if (> (- (js/Date.now) start) timeout-ms)
              (put! result-ch [:error :timeout])
              (do
                (<! (timeout 10))
                (recur)))))))
    result-ch))

;; =============================================================================
;; Event Handler Testing (Direct)
;; =============================================================================

(defn run-event-handler
  "Runs an event handler directly with given coeffects and event.
   Returns the effects map.

   Usage:
   (run-event-handler ::my-event
                      {:db default-db :active-uri \"file:///test.rho\"}
                      [::my-event arg1 arg2])"
  [event-key coeffects event-vec]
  (when-let [handler (rf-registrar/get-handler :event event-key)]
    (handler coeffects event-vec)))

(defn run-event-db-handler
  "Runs an event-db handler directly with given db and event.
   Returns the new db state.

   Usage:
   (run-event-db-handler ::my-event default-db [::my-event arg1])"
  [event-key db event-vec]
  (when-let [handler (rf-registrar/get-handler :event event-key)]
    (handler db event-vec)))

;; =============================================================================
;; Test Fixture Composition
;; =============================================================================

(defn with-mocked-effects
  "Fixture that mocks common effects and restores them after test.
   Effect keys is a vector of effect keywords to mock."
  [effect-keys f]
  (reset-captured-effects!)
  (doseq [k effect-keys]
    (mock-effect! k))
  (try
    (f)
    (finally
      (restore-all-effects!)
      (reset-captured-effects!))))

(defn with-mocked-coeffects
  "Fixture that mocks coeffects and restores them after test.
   Cofx-map is a map of {cofx-key mock-value}."
  [cofx-map f]
  (doseq [[k v] cofx-map]
    (mock-coeffect! k v))
  (try
    (f)
    (finally
      (restore-all-coeffects!))))

(defn with-test-db
  "Fixture that sets up app-db with custom initial state."
  [initial-db f]
  (set-app-db! initial-db)
  (f))

;; =============================================================================
;; Assertion Helpers
;; =============================================================================

(defn assert-db-path
  "Asserts that a path in app-db has the expected value."
  [path expected]
  (is (= expected (get-in @rf-db/app-db path))
      (str "db path " path " should equal " expected)))

(defn assert-effect-triggered
  "Asserts that an effect was triggered."
  [effect-key]
  (is (effect-was-triggered? effect-key)
      (str "Effect " effect-key " should have been triggered")))

(defn assert-effect-triggered-with
  "Asserts that an effect was triggered with specific value."
  [effect-key expected-value]
  (is (effect-was-triggered-with? effect-key expected-value)
      (str "Effect " effect-key " should have been triggered with " expected-value)))

(defn assert-no-effect-triggered
  "Asserts that an effect was NOT triggered."
  [effect-key]
  (is (not (effect-was-triggered? effect-key))
      (str "Effect " effect-key " should NOT have been triggered")))
