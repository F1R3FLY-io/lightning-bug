(ns test.app.events-test
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [datascript.core :as d]
   [re-frame.core :as rf]
   [re-frame.db :refer [app-db]]
   [app.db :as db]
   [app.events :as e]
   [re-posh.core :as rp]
   [reagent.core :as r]))

(use-fixtures :each
  {:before (fn []
             (set! db/ds-conn (d/create-conn db/schema))
             (reset! app-db {})
             (rp/dispatch-sync [::e/initialize])
             (rp/connect! db/ds-conn))
   :after (fn [])})

(deftest initialize
  (let [db @app-db
        active (:active-file (:workspace db))
        lang (get-in db [:workspace :files active :language])
        ext (first (get-in db [:languages lang :extensions]))]
    (testing "Adds untitled file"
      (is (= 1 (count (:files (:workspace db)))))
      (is (some? active))
      (is (= (str "untitled" ext) (get-in db[:workspace :files active :name]))))))

(deftest file-add-remove
  (let [initial-count (count (:files (:workspace @app-db)))
        ext (first (get-in @app-db [:languages (:default-language @app-db) :extensions]))]
    (rf/dispatch-sync [::e/file-add])
    (let [new-count (count (:files (:workspace @app-db)))
          file-id (:active-file (:workspace @app-db))
          file-name (get-in @app-db [:workspace :files file-id :name])]
      (is (= (str "untitled-1" ext) file-name) "New file has correct name with extension")
      (rf/dispatch-sync [::e/file-remove file-id])
      (let [final-count (count (:files (:workspace @app-db)))]
        (is (= (inc initial-count) new-count))
        (is (= initial-count final-count))))))

(deftest file-remove-last
  (let [initial-active (get-in @app-db [:workspace :active-file])]
    (rf/dispatch-sync [::e/file-remove initial-active])
    (is (empty? (get-in @app-db [:workspace :files])))
    (is (nil? (get-in @app-db [:workspace :active-file])) "Active file nil after removing last")))

(deftest file-rename-lang-detection
  (let [active (:active-file (:workspace @app-db))
        new-name "test.rho"]
    (rf/dispatch-sync [::e/file-rename active new-name])
    (is (= new-name (get-in @app-db [:workspace :files active :name])))
    (is (= "rholang" (get-in @app-db [:workspace :files active :language])) "Language updated for .rho extension")
    (rf/dispatch-sync [::e/file-rename active "test.txt"])
    (is (= "text" (get-in @app-db [:workspace :files active :language])) "Fallback to default for unknown extension")))

(deftest editor-update-dirty
  (let [active (get-in @app-db [:workspace :active-file])
        content "test content"]
    (rf/dispatch-sync [::e/editor-update-content content])
    (is (= content (get-in @app-db [:workspace :files active :content])))
    (is (true? (get-in @app-db [:workspace :files active :dirty?])))))

(deftest flatten-symbols
  (testing "Flattens hierarchical symbols"
    (let [hierarchical [{:name "root" :children [{:name "child1"} {:name "child2" :children [{:name "grandchild"}]}]}]
          flat (e/flatten-symbols hierarchical nil)]
      (is (= 4 (count flat)))
      (is (every? #(contains? % :db/id) flat))
      (is (not (contains? (first flat) :parent)))
      (is (some #(= "grandchild" (:name %)) flat)))))

(deftest lsp-diagnostics-update
  (testing "Transacts diagnostics"
    (let [diags [{:message "test"
                  :range {:start {:line 0 :character 0} :end {:line 0 :character 1}}
                  :severity 1}]]
      (rf/dispatch-sync [::e/lsp-diagnostics-update diags])
      (r/with-let [diags-sub (rf/subscribe [:lsp/diagnostics])]
        (is (= 1 (count @diags-sub)))))))

(deftest lsp-symbols-update
  (testing "Transacts symbols"
    (let [symbols [{:name "sym1"
                    :kind 1
                    :range {:start {:line 0 :character 0} :end {:line 0 :character 1}}}]]
      (rf/dispatch-sync [::e/lsp-symbols-update symbols])
      (r/with-let [syms (rf/subscribe [:lsp/symbols])]
        (is (= 1 (count @syms)))))))

(deftest update-cursor
  (let [cursor {:line 2 :column 3}]
    (rf/dispatch-sync [::e/update-cursor cursor])
    (is (= cursor (get-in @app-db [:editor :cursor])))))

(deftest posh-reconnect-on-reload
  (let [conn (d/create-conn {:symbol/parent {:db/valueType :db.type/ref}
                             :symbol/range {:db/cardinality :db.cardinality/one}
                             :diagnostic/range {:db/cardinality :db.cardinality/one}})]
    (testing "Reconnects if conn present"
      (is (some? conn))
      (is (some? @conn))
      (is (some? (meta conn)))
      (rp/connect! conn)
      (is (some? (:listeners (meta conn))))
      (is (> (count @(:listeners (meta conn))) 0))
      (is (contains? @(:listeners (meta conn)) :posh)))))
