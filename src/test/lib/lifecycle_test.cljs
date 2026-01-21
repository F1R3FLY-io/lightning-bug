(ns test.lib.lifecycle-test
  "Tests for the lib.lifecycle resource lifecycle management module."
  (:require
   [clojure.test :refer [deftest is testing use-fixtures async]]
   [clojure.core.async :refer [go <! timeout promise-chan put!]]
   [lib.lifecycle :as lifecycle]
   [domain.protocols :as p]))

;; =============================================================================
;; Test Resources
;; =============================================================================

(defrecord TestResource [id state-atom]
  p/IResourceLifecycle

  (start! [_this]
    (let [result-ch (promise-chan)]
      (go
        (swap! state-atom assoc :started? true :start-order (swap! (::order @state-atom) inc))
        (put! result-ch [:ok id]))
      result-ch))

  (stop! [_this]
    (let [result-ch (promise-chan)]
      (go
        (swap! state-atom assoc :stopped? true :stop-order (swap! (::order @state-atom) inc))
        (put! result-ch [:ok]))
      result-ch))

  (started? [_this]
    (:started? @state-atom false))

  (restart! [this]
    (go
      (<! (p/stop! this))
      (<! (p/start! this)))))

(defn make-test-resource
  [id]
  (->TestResource id (atom {::order (atom 0)})))

(defrecord FailingResource [id fail-on-start? fail-on-stop?]
  p/IResourceLifecycle

  (start! [_this]
    (let [result-ch (promise-chan)]
      (go
        (if fail-on-start?
          (put! result-ch [:error (ex-info "Start failed" {:id id})])
          (put! result-ch [:ok id])))
      result-ch))

  (stop! [_this]
    (let [result-ch (promise-chan)]
      (go
        (if fail-on-stop?
          (put! result-ch [:error (ex-info "Stop failed" {:id id})])
          (put! result-ch [:ok])))
      result-ch))

  (started? [_this] false)
  (restart! [_this] (promise-chan)))

(defrecord PlainResource [id value])

;; =============================================================================
;; Fixtures
;; =============================================================================

(use-fixtures :each
  {:before lifecycle/reset-registry!
   :after lifecycle/reset-registry!})

;; =============================================================================
;; Registration Tests
;; =============================================================================

(deftest register-resource!-stores-resource
  (testing "Registering a resource stores it in the registry"
    (let [resource (make-test-resource :test-resource)]
      (lifecycle/register-resource! :test-resource resource)
      (is (= resource (lifecycle/get-resource :test-resource))))))

(deftest register-resource!-with-options
  (testing "Registering with priority and depends-on"
    (let [resource (make-test-resource :prioritized)]
      (lifecycle/register-resource! :prioritized resource
                                    :priority 50
                                    :depends-on #{:other})
      (let [resources (lifecycle/list-resources)
            entry (first (filter #(= :prioritized (:key %)) resources))]
        (is (= 50 (:priority entry)))
        (is (= #{:other} (:depends-on entry)))))))

(deftest unregister-resource!-removes-resource
  (async done
         (go
           (let [resource (make-test-resource :to-remove)]
             (lifecycle/register-resource! :to-remove resource)
             (is (some? (lifecycle/get-resource :to-remove)))
             (<! (lifecycle/unregister-resource! :to-remove))
             (is (nil? (lifecycle/get-resource :to-remove))))
           (done))))

(deftest get-resource-returns-nil-for-missing
  (testing "Getting non-existent resource returns nil"
    (is (nil? (lifecycle/get-resource :non-existent)))))

(deftest resource-started?-returns-false-initially
  (testing "Newly registered resource is not started"
    (let [resource (make-test-resource :test)]
      (lifecycle/register-resource! :test resource)
      (is (false? (lifecycle/resource-started? :test))))))

;; =============================================================================
;; Start/Stop Tests
;; =============================================================================

(deftest start-resource!-starts-single-resource
  (async done
         (go
           (let [state (atom {})
                 resource (->TestResource :single (atom {::order (atom 0)}))]
             (lifecycle/register-resource! :single resource)
             (let [[status val] (<! (lifecycle/start-resource! :single))]
               (is (= :ok status))
               (is (= :single val))
               (is (true? (lifecycle/resource-started? :single)))))
           (done))))

(deftest start-resource!-returns-error-for-missing
  (async done
         (go
           (let [[status _] (<! (lifecycle/start-resource! :missing))]
             (is (= :error status)))
           (done))))

(deftest start-resource!-returns-ok-if-already-started
  (async done
         (go
           (let [resource (make-test-resource :already-started)]
             (lifecycle/register-resource! :already-started resource)
             (<! (lifecycle/start-resource! :already-started))
             ;; Start again
             (let [[status _] (<! (lifecycle/start-resource! :already-started))]
               (is (= :ok status))))
           (done))))

(deftest stop-resource!-stops-single-resource
  (async done
         (go
           (let [resource (make-test-resource :to-stop)]
             (lifecycle/register-resource! :to-stop resource)
             (<! (lifecycle/start-resource! :to-stop))
             (is (true? (lifecycle/resource-started? :to-stop)))
             (let [[status _] (<! (lifecycle/stop-resource! :to-stop))]
               (is (= :ok status))
               (is (false? (lifecycle/resource-started? :to-stop)))))
           (done))))

(deftest stop-resource!-returns-ok-if-not-started
  (async done
         (go
           (let [resource (make-test-resource :not-started)]
             (lifecycle/register-resource! :not-started resource)
             (let [[status _] (<! (lifecycle/stop-resource! :not-started))]
               (is (= :ok status))))
           (done))))

(deftest stop-resource!-returns-ok-for-missing
  (async done
         (go
           (let [[status _] (<! (lifecycle/stop-resource! :non-existent))]
             (is (= :ok status)))
           (done))))

;; =============================================================================
;; Plain Resource Tests (No IResourceLifecycle)
;; =============================================================================

(deftest start-resource!-handles-plain-resource
  (async done
         (go
           (let [resource (->PlainResource :plain "value")]
             (lifecycle/register-resource! :plain resource)
             (let [[status _] (<! (lifecycle/start-resource! :plain))]
               (is (= :ok status))
               (is (true? (lifecycle/resource-started? :plain)))))
           (done))))

(deftest stop-resource!-handles-plain-resource-with-cleanup-fn
  (async done
         (go
           (let [cleaned-up? (atom false)
                 resource (->PlainResource :with-cleanup "value")]
             (lifecycle/register-resource! :with-cleanup resource
                                           :cleanup-fn (fn [_] (reset! cleaned-up? true)))
             (<! (lifecycle/start-resource! :with-cleanup))
             (<! (lifecycle/stop-resource! :with-cleanup))
             (is (true? @cleaned-up?)))
           (done))))

;; =============================================================================
;; Failing Resource Tests
;; =============================================================================

(deftest start-resource!-handles-failure
  (async done
         (go
           (let [resource (->FailingResource :fail-start true false)]
             (lifecycle/register-resource! :fail-start resource)
             (let [[status _] (<! (lifecycle/start-resource! :fail-start))]
               (is (= :error status))
               (is (false? (lifecycle/resource-started? :fail-start)))))
           (done))))

(deftest stop-resource!-handles-failure
  (async done
         (go
           (let [resource (->FailingResource :fail-stop false true)]
             (lifecycle/register-resource! :fail-stop resource)
             (<! (lifecycle/start-resource! :fail-stop))
             (let [[status _] (<! (lifecycle/stop-resource! :fail-stop))]
               (is (= :error status))
               ;; Resource is still marked as not started even on failure
               (is (false? (lifecycle/resource-started? :fail-stop)))))
           (done))))

;; =============================================================================
;; Batch Operation Tests
;; =============================================================================

(deftest start-all!-starts-all-resources
  (async done
         (go
           (let [r1 (make-test-resource :r1)
                 r2 (make-test-resource :r2)
                 r3 (make-test-resource :r3)]
             (lifecycle/register-resource! :r1 r1 :priority 1)
             (lifecycle/register-resource! :r2 r2 :priority 2)
             (lifecycle/register-resource! :r3 r3 :priority 3)
             (let [[status _] (<! (lifecycle/start-all!))]
               (is (= :ok status))
               (is (true? (lifecycle/resource-started? :r1)))
               (is (true? (lifecycle/resource-started? :r2)))
               (is (true? (lifecycle/resource-started? :r3)))))
           (done))))

(deftest stop-all!-stops-all-resources
  (async done
         (go
           (let [r1 (make-test-resource :r1)
                 r2 (make-test-resource :r2)]
             (lifecycle/register-resource! :r1 r1)
             (lifecycle/register-resource! :r2 r2)
             (<! (lifecycle/start-all!))
             (let [[status _] (<! (lifecycle/stop-all!))]
               (is (= :ok status))
               (is (false? (lifecycle/resource-started? :r1)))
               (is (false? (lifecycle/resource-started? :r2)))))
           (done))))

(deftest start-all!-respects-dependencies
  (async done
         (go
           ;; Create resources with dependencies
           ;; :child depends on :parent
           (let [parent-started (atom false)
                 child-checked-parent (atom false)
                 parent (reify p/IResourceLifecycle
                          (start! [_]
                            (let [ch (promise-chan)]
                              (go
                                (reset! parent-started true)
                                (put! ch [:ok :parent]))
                              ch))
                          (stop! [_] (promise-chan))
                          (started? [_] @parent-started)
                          (restart! [_] (promise-chan)))
                 child (reify p/IResourceLifecycle
                         (start! [_]
                           (let [ch (promise-chan)]
                             (go
                               ;; Check that parent was started
                               (reset! child-checked-parent @parent-started)
                               (put! ch [:ok :child]))
                             ch))
                         (stop! [_] (promise-chan))
                         (started? [_] false)
                         (restart! [_] (promise-chan)))]
             (lifecycle/register-resource! :parent parent :priority 1)
             (lifecycle/register-resource! :child child :priority 2 :depends-on #{:parent})
             (<! (lifecycle/start-all!))
             ;; Child should have seen parent as started
             (is (true? @child-checked-parent)))
           (done))))

;; =============================================================================
;; List Resources Tests
;; =============================================================================

(deftest list-resources-returns-all
  (testing "list-resources returns all registered resources"
    (let [r1 (make-test-resource :r1)
          r2 (make-test-resource :r2)]
      (lifecycle/register-resource! :r1 r1 :priority 10)
      (lifecycle/register-resource! :r2 r2 :priority 20)
      (let [resources (lifecycle/list-resources)]
        (is (= 2 (count resources)))
        (is (some #(= :r1 (:key %)) resources))
        (is (some #(= :r2 (:key %)) resources))))))

;; =============================================================================
;; Lifecycle Manager Tests
;; =============================================================================

(deftest make-lifecycle-manager-creates-manager
  (testing "Creating a lifecycle manager"
    (let [manager (lifecycle/make-lifecycle-manager)]
      (is (some? manager))
      (is (satisfies? p/IResourceLifecycle manager)))))

(deftest lifecycle-manager-start!-starts-all
  (async done
         (go
           (let [r1 (make-test-resource :r1)
                 manager (lifecycle/make-lifecycle-manager)]
             (lifecycle/register-resource! :r1 r1)
             (let [[status _] (<! (p/start! manager))]
               (is (= :ok status))
               (is (true? (lifecycle/resource-started? :r1)))))
           (done))))

(deftest lifecycle-manager-stop!-stops-all
  (async done
         (go
           (let [r1 (make-test-resource :r1)
                 manager (lifecycle/make-lifecycle-manager)]
             (lifecycle/register-resource! :r1 r1)
             (<! (p/start! manager))
             (let [[status _] (<! (p/stop! manager))]
               (is (= :ok status))
               (is (false? (lifecycle/resource-started? :r1)))))
           (done))))

;; =============================================================================
;; Reset Registry Tests
;; =============================================================================

(deftest reset-registry!-clears-all
  (testing "reset-registry! clears all resources"
    (let [r1 (make-test-resource :r1)]
      (lifecycle/register-resource! :r1 r1)
      (is (some? (lifecycle/get-resource :r1)))
      (lifecycle/reset-registry!)
      (is (nil? (lifecycle/get-resource :r1))))))
