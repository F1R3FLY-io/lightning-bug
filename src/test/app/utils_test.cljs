(ns test.app.utils-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [app.db :refer [default-db]]
   [app.utils :as u]))

(deftest new-untitled-name
  (testing "Generates untitled file name with extension"
    (let [db default-db
          ext (u/get-extension db (:default-language db))]
      (is (= (str "untitled" ext) (u/new-untitled-name db 0)))
      (is (= (str "untitled-5" ext) (u/new-untitled-name db 5)))
      (is (= "untitled" (u/new-untitled-name (assoc db :languages {}) 0)) "No extension if language not found"))))
