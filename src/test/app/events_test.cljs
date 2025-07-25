(ns test.app.events-test
  (:require
   [clojure.core.async :refer [go <! timeout]]
   [clojure.test :refer [deftest is testing async use-fixtures]]
   [datascript.core :as d]
   [re-frame.core :as rf]
   [re-frame.db :refer [app-db]]
   [reagent.ratom :as ratom]
   [app.db :as db]
   [app.events :as e]
   [app.shared :refer [editor-ref-atom]]
   [re-posh.core :as rp]))

(use-fixtures :each
  {:before (fn []
             (set! db/ds-conn (d/create-conn db/schema))
             (reset! app-db {})
             (reset! editor-ref-atom nil)
             (rf/dispatch-sync [::e/initialize])
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
      (is (= (str "untitled" ext) (get-in db [:workspace :files active :name]))))))

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
    (is (= "text" (get-in @app-db [:workspace :files active :language])) "Fallback to text for .txt")))

(deftest editor-update-dirty
  (let [active (get-in @app-db [:workspace :active-file])
        content "test content"]
    (rf/dispatch-sync [::e/editor-update-content content])
    (is (= content (get-in @app-db [:workspace :files active :content])))
    (is (true? (get-in @app-db [:workspace :files active :dirty?])) "Marked dirty")))

(deftest flatten-symbols
  (testing "Flattens hierarchical symbols"
    (let [hierarchical [{:name "root" :children [{:name "child1"} {:name "child2" :children [{:name "grandchild"}]}]}]
          flat (e/flatten-symbols hierarchical nil)
          conn (d/create-conn {:parent {:db/valueType :db.type/ref}})]
      (is (= 4 (count flat)))
      (is (every? #(contains? % :db/id) flat))
      (is (not (contains? (first flat) :parent)))
      (is (some #(= "grandchild" (:name %)) flat))
      (d/transact! conn flat)
      (is (= 4 (count (d/q '[:find ?e :where [?e]] @conn))) "Transacts without nil ref error"))))

(deftest lsp-diagnostics-update
  (testing "Transacts diagnostics"
    (let [diags [{:message "test"
                  :range {:start {:line 0 :character 0} :end {:line 0 :character 1}}
                  :severity 1}]]
      (rf/dispatch-sync [::e/lsp-diagnostics-update diags])
      (binding [ratom/*ratom-context* (ratom/make-reaction (fn []))]
        (let [diags-sub @(rf/subscribe [:lsp/diagnostics])]
          (is (= 1 (count diags-sub))))))))

(deftest lsp-symbols-update
  (testing "Transacts symbols"
    (let [symbols [{:name "sym1"
                    :kind 1
                    :range {:start {:line 0 :character 0} :end {:line 0 :character 1}}}]]
      (rf/dispatch-sync [::e/lsp-symbols-update symbols])
      (binding [ratom/*ratom-context* (ratom/make-reaction (fn []))]
        (let [syms @(rf/subscribe [:lsp/symbols])]
          (is (= 1 (count syms))))))))

(deftest update-cursor
  (let [cursor {:line 2 :column 3}]
    (rf/dispatch-sync [::e/update-cursor cursor])
    (is (= cursor (get-in @app-db [:editor :cursor])))))

(deftest posh-reconnect-on-reload
  (let [conn (d/create-conn db/schema)]
    (testing "Connects and verifies listeners"
      (is (some? conn) "Connection created")
      (is (some? @conn) "Derefs to DB map")
      (is (some? (meta conn)) "Conn atom has metadata")
      (rp/connect! conn)
      (let [listeners (:listeners @(:atom conn))]
        (when (is (some? listeners) "Listeners map attached")
          (is (> (count listeners) 0) "Listeners populated")
          (is (contains? listeners :posh-listener) "Posh listener key present"))))
    (testing "Simulate unload: unlisten"
      (d/unlisten! conn :posh-listener)
      (when-let [listeners (:listeners @(:atom conn))]
        (is (not (contains? listeners :posh-listener)) "Posh listener removed")))
    (testing "Reconnects on reload"
      (rp/connect! conn)
      (let [listeners (:listeners @(:atom conn))]
        (when (is (some? listeners) "Listeners still attached after reconnect")
          (is (> (count listeners) 0) "Listeners count >0 after reconnect")
          (is (contains? listeners :posh-listener) "Posh listener re-added"))))
    (testing "Transact and query works with Posh"
      (d/transact! conn [[:db/add -1 :test/key "value"]])
      (is (= #{["value"]} (d/q '[:find ?v :where [?e :test/key ?v]] @conn)) "Transact and query succeed"))))

(deftest file-rename-lang-detection-unknown-ext
  (let [active (:active-file (:workspace @app-db))
        new-name "test.unknown"]
    (rf/dispatch-sync [::e/file-rename active new-name])
    (is (= new-name (get-in @app-db [:workspace :files active :name])))
    (is (= (:default-language @app-db) (get-in @app-db [:workspace :files active :language])) "Fallback to default for unknown extension")))

(deftest file-rename-no-ext
  (let [active (:active-file (:workspace @app-db))
        new-name "test"]
    (rf/dispatch-sync [::e/file-rename active new-name])
    (is (= new-name (get-in @app-db [:workspace :files active :name])))
    (is (= (:default-language @app-db) (get-in @app-db [:workspace :files active :language])) "Fallback to default with no extension")))

(deftest log-append
  (let [log {:message "Test log"}]
    (rf/dispatch-sync [::e/log-append log])
    (is (= [log] (get-in @app-db [:lsp :logs])) "Log appended to lsp/logs")))

(deftest search-empty-term
  (rf/dispatch-sync [::e/search ""])
  (let [results (get-in @app-db [:search :results])]
    (is (empty? results) "Empty term yields no results")))

(deftest toggle-search
  (let [initial-visible (get-in @app-db [:search :visible?])]
    (rf/dispatch-sync [::e/toggle-search])
    (is (not= initial-visible (get-in @app-db [:search :visible?])) "Toggles visibility")))

(deftest open-rename-modal
  (let [active-name (get-in @app-db [:workspace :files (get-in @app-db [:workspace :active-file]) :name])]
    (rf/dispatch-sync [::e/open-rename-modal])
    (is (true? (get-in @app-db [:modals :rename :visible?])) "Modal visible")
    (is (= active-name (get-in @app-db [:modals :rename :new-name])) "New name set to current")))

(deftest confirm-rename
  (async done
         (go
           (let [active (get-in @app-db [:workspace :active-file])
                 new-name "renamed.rho"]
             (rf/dispatch-sync [::e/set-rename-name new-name])
             (rf/dispatch [::e/confirm-rename])
             (<! (timeout 50))
             (is (= new-name (get-in @app-db [:workspace :files active :name])) "File renamed")
             (done)))))

(deftest confirm-rename-calls-editor-method
  (let [active (get-in @app-db [:workspace :active-file])
        new-name "renamed.rho"
        called (atom false)]
    (with-redefs [editor-ref-atom (atom #js {:current #js {:renameDocument (fn [name]
                                                                             (reset! called true)
                                                                             (is (= new-name name) "Called renameDocument with new name"))}})]
      (rf/dispatch-sync [::e/set-rename-name new-name])
      (rf/dispatch-sync [::e/confirm-rename])
      (is @called "Editor renameDocument method called"))))
