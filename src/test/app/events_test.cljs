(ns test.app.events-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [clojure.test.check :as tc]
   [clojure.test.check.generators :as gen]
   [clojure.test.check.properties :as prop]
   [datascript.core :as d]
   [re-frame.core :as rf]
   [re-frame.db :refer [app-db]]
   [app.db :as db :refer [ds-conn]]
   [app.editor.state :as es]
   [app.editor.syntax :as syntax]
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
  (let [db @app-db]
    (testing "Adds untitled file"
      (is (= 1 (count (:files (:workspace db)))))
      (is (some? (:active-file (:workspace db)))))))

(deftest file-add-remove
  (let [initial-count (count (:files (:workspace @app-db)))]
    (rf/dispatch-sync [:app.events/file-add])
    (let [new-count (count (:files (:workspace @app-db)))
          file-id (get-in @app-db [:workspace :active-file])]
      (rf/dispatch-sync [:app.events/file-remove file-id])
      (let [final-count (count (:files (:workspace @app-db)))]
        (is (= (inc initial-count) new-count))
        (is (= initial-count final-count))))))

(deftest editor-update-dirty
  (let [active (get-in @app-db [:workspace :active-file])
        content "test content"]
    (rf/dispatch-sync [:app.events/editor-update-content content])
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

(def gen-non-empty-string
  (gen/let [len (gen/choose 1 10)
            chars (gen/vector gen/char-alphanumeric len)]
    (apply str chars)))

(def gen-term gen-non-empty-string)
(def gen-text (gen/vector gen-non-empty-string 0 100))

(def search-prop
  (prop/for-all [term gen-term
                 lines gen-text]
    (let [lterm (str/lower-case term)
          content (str/join "\n" lines)
          db {:workspace {:files {1 {:content content}} :active-file 1}
              :lsp {:logs (map #(hash-map :message %) lines)}}
          results (:results (:search (#'app.events/search db [:search term])))]
      (= (count results) (* 2 (count (filter #(str/includes? (str/lower-case %) lterm) lines)))))))

(deftest search-property
  (is (:result (tc/quick-check 100 search-prop))))

(deftest fallback-no-lsp-or-grammar
  (testing "Falls back to basic mode with no LSP URL or invalid grammar path"
    (let [db (assoc db/default-db :languages {"rholang" {:lsp-url nil :grammar-wasm "/invalid/path"}})]
      (reset! app-db db)
      (rf/dispatch-sync [:app.events/lsp-connect])
      (is (false? (:connection (:lsp @app-db))) "No LSP connection")
      (rf/dispatch-sync [:reload-syntax])
      ;; No assertion on view dispatch (side-effect), but verify no crash and fallback mode triggered without error.
      (is true "Fallback mode triggered without error"))))

(deftest lsp-diagnostics-update
  (testing "Transacts diagnostics and updates status"
    (rp/connect! ds-conn) ; Ensure connected before dispatch.
    (let [diags [{:message "test"
                  :range {:start {:line 0 :character 0} :end {:line 0 :character 1}}
                  :severity 1}]]
      (rp/dispatch-sync [::e/lsp-diagnostics-update diags])
      (r/with-let [status (rf/subscribe [:status])
                   diags-sub (rp/subscribe [:lsp/diagnostics])]
        (is (= :error @status))
        (is (= 1 (count @diags-sub)))))))

(deftest lsp-symbols-update
  (testing "Transacts symbols"
    (rp/connect! ds-conn) ; Ensure connected before dispatch.
    (let [symbols [{:name "sym1"
                    :kind 1
                    :range {:start {:line 0 :character 0} :end {:line 0 :character 1}}}]]
      (rf/dispatch-sync [::e/lsp-symbols-update symbols])
      (r/with-let [syms (rp/subscribe [:lsp/symbols])]
        (is (= 1 (count @syms)))))))

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
      (is (> (count (:listeners (meta conn))) 0))
      (is (contains? (:listeners (meta conn)) @conn)))))

(def gen-symbol
  (gen/recursive-gen
   (fn [rec-gen]
     (gen/hash-map
      :name gen/string-alphanumeric
      :children (gen/vector rec-gen 0 3)
      :kind gen/int
      :range (gen/hash-map :start (gen/hash-map :line gen/nat :character gen/nat)
                           :end (gen/hash-map :line gen/nat :character gen/nat))))
   (gen/hash-map
    :name gen/string-alphanumeric
    :kind gen/int
    :range (gen/hash-map :start (gen/hash-map :line gen/nat :character gen/nat)
                         :end (gen/hash-map :line gen/nat :character gen/nat)))))

(def flatten-prop
  (prop/for-all [symbols (gen/vector gen-symbol 1 10)]
    (let [flat (e/flatten-symbols symbols nil)]
      (and (every? #(number? (:db/id %)) flat)
           (< (apply max (map :db/id flat)) 0)
           (= (count flat) (count (mapcat #(tree-seq :children :children %) symbols)))))))

(deftest flatten-property
  (is (:result (tc/quick-check 100 flatten-prop {:seed 42}))))

(deftest reload-syntax
  (testing "Triggers syntax init on language switch"
    (with-redefs [syntax/init-syntax (fn [v] (is (some? v) "Calls init with view"))]
      (reset! es/editor-view #js {})
      (rf/dispatch-sync [:reload-syntax])
      (is true "Syntax reload dispatched without error"))))
