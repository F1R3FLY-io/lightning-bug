(ns test.app.utils-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [app.db :as db :refer [default-db]]
   [app.languages :as langs]
   [app.utils :as u]
   [taoensso.timbre :as log]))

(deftest new-untitled-name
  (testing "Generates untitled file name with extension"
    (let [db default-db
          ext (u/get-extension db (:default-language db))]
      (is (= (str "untitled" ext) (u/new-untitled-name db 0)))
      (is (= (str "untitled-5" ext) (u/new-untitled-name db 5)))
      (is (= "untitled" (u/new-untitled-name (assoc db :languages {}) 0)) "No extension if language not found"))))

(deftest default-lang-fallback
  (testing "Falls back to first registered if configured invalid"
    (with-redefs [langs/registry (atom {"rholang" {}})
                  langs/default-lang (atom "invalid")]
      (let [registry-langs @langs/registry
            configured @langs/default-lang
            default-lang (if (and configured (contains? registry-langs configured))
                           configured
                           (do
                             (when configured
                               (log/warn "Configured default-lang" configured "not in registry; falling back"))
                             (or (first (keys registry-langs)) "text")))]
        (is (= "rholang" default-lang)))))
  (testing "Uses configured if valid"
    (with-redefs [langs/registry (atom {"rholang" {}})
                  langs/default-lang (atom "rholang")]
      (let [registry-langs @langs/registry
            configured @langs/default-lang
            default-lang (if (and configured (contains? registry-langs configured))
                           configured
                           (or (first (keys registry-langs)) "text"))]
        (is (= "rholang" default-lang)))))
  (testing "Falls back to text if no registered"
    (with-redefs [langs/registry (atom {})
                  langs/default-lang (atom nil)]
      (let [registry-langs @langs/registry
            configured @langs/default-lang
            default-lang (if (and configured (contains? registry-langs configured))
                           configured
                           (or (first (keys registry-langs)) "text"))]
        (is (= "text" default-lang))))))
