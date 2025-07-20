(ns test.app.utils-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [clojure.test.check :as tc]
   [clojure.test.check.generators :as gen]
   [clojure.test.check.properties :as prop]
   [app.utils :as u]))

(deftest generate-uuid
  (let [id (u/generate-uuid)]
    (is (uuid? id))))

(deftest make-uuid-native
  (testing "Uses native random-uuid"
    (is (uuid? (u/generate-uuid)))
    (is (not= (u/generate-uuid) (u/generate-uuid)) "Generates unique UUIDs")))

(deftest icon
  (testing "diag icons"
    (is (vector? (u/icon {:type :diag :severity :error})))
    (is (str/includes? (str (u/icon {:type :diag :severity :error})) "exclamation-triangle")))
  (testing "file icons"
    (is (str/includes? (str (u/icon {:type :file :lang "rholang"})) "code")))
  (testing "symbol icons"
    (is (str/includes? (str (u/icon {:type :symbol :kind 12})) "cogs"))))

(deftest log-class
  (is (= "log-error text-danger" (u/log-class :error)))
  (is (= "log-info text-info" (u/log-class :info)))
  (is (= "text-secondary" (u/log-class :other))))

(deftest highlight-text
  (let [result (u/highlight-text "hello world hello" "hello")]
    (is (= 3 (count result)))  ; Adjusted for new impl (no empty parts).
    (is (= "hello world hello" (str/join "" (map (fn [el] (if (vector? el) (last el) el)) result))))))

(deftest highlight-text-non-boundary
  (let [result (u/highlight-text "a hello b hello c" "hello")]
    (is (= 5 (count result)))
    (is (= "a hello b hello c" (str/join "" (map (fn [el] (if (vector? el) (last el) el)) result))))))

(def gen-text (gen/such-that not-empty gen/string-alphanumeric))
(def gen-term (gen/such-that not-empty gen/string-alphanumeric))

(def highlight-prop
  (prop/for-all [text gen-text
                 term gen-term]
    (let [result (u/highlight-text text term)]
      (and (seq? result)
           (= text (str/join "" (map (fn [el] (if (vector? el) (last el) el)) result)))
           (= (count (re-seq (js/RegExp. (js/RegExp.escape term) "ig") text))
              (count (filter vector? result)))))))

(deftest highlight-property
  (is (:result (tc/quick-check 100 highlight-prop {:seed 42}))))
