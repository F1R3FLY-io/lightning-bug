(ns test.app.subs-test
  (:require [clojure.test :refer [deftest is]]
            [app.subs :as s]))

(deftest active-content
  (let [db {:workspace {:files {1 {:content "test"}} :active-file 1}}]
    (is (= "test" (s/active-content-fn db [])))))
