(ns test.app.system-test
  "Tests for the Phase 2 hexagonal wiring: the app.system dependency container
   and that app.cofx/app.fx actually delegate to the injected repositories
   (the dependency-injection / testability seam)."
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [re-frame.core :as rf]
   [domain.protocols :as p]
   [app.system :as sys]
   [app.cofx]   ;; register real coeffects
   [app.fx]))   ;; register real effects

(use-fixtures :each
  {:after (fn [] (sys/reset-system!))})

;; =============================================================================
;; app.system dependency container
;; =============================================================================

(deftest init!-populates-the-four-repositories
  (testing "init! instantiates document/diagnostics/symbols/log repositories"
    (sys/init!)
    (is (some? (sys/document-repo)))
    (is (some? (sys/diagnostics-repo)))
    (is (some? (sys/symbols-repo)))
    (is (some? (sys/log-repo)))))

(deftest set-system!-injects-mocks
  (testing "set-system! replaces the repositories with injected values"
    (sys/set-system! {:document-repo :mock-doc :log-repo :mock-log})
    (is (= :mock-doc (sys/document-repo)))
    (is (= :mock-log (sys/log-repo)))))

(deftest reset-system!-lazily-restores-production
  (testing "after reset, the next accessor lazily restores a real repository"
    (sys/set-system! {:document-repo :mock-doc})
    (is (= :mock-doc (sys/document-repo)))
    (sys/reset-system!)
    ;; lazy re-init to the production DataScript repo (a record, not the mock keyword)
    (is (some? (sys/document-repo)))
    (is (not= :mock-doc (sys/document-repo)))))

;; =============================================================================
;; app.cofx delegates to the injected document repository
;; =============================================================================

(rf/reg-event-fx
 ::read-active-uri
 [(rf/inject-cofx :document-repo/active-uri)]
 (fn [{:keys [active-uri]} [_ result]]
   (reset! result active-uri)
   {}))

(deftest cofx-active-uri-delegates-to-injected-repo
  (testing ":document-repo/active-uri coeffect reads from the injected repository"
    (let [result (atom :unset)]
      (sys/set-system!
       {:document-repo #_:clj-kondo/ignore (reify p/IDocumentRepository
                                             (get-active-uri [_] "file:///injected.rho"))})
      (rf/dispatch-sync [::read-active-uri result])
      (is (= "file:///injected.rho" @result)))))

;; =============================================================================
;; app.fx delegates to the injected document repository
;; =============================================================================

(rf/reg-event-fx
 ::do-create
 (fn [_ [_ doc]]
   {:document/create doc}))

(deftest fx-create-delegates-to-injected-repo
  (testing ":document/create effect calls create-document! on the injected repository"
    (let [created (atom nil)]
      (sys/set-system!
       {:document-repo #_:clj-kondo/ignore (reify p/IDocumentRepository
                                             (create-document! [_ doc] (reset! created doc) 1))})
      (rf/dispatch-sync [::do-create {:uri "file:///new.rho" :text "x"}])
      (is (= {:uri "file:///new.rho" :text "x"} @created)))))
