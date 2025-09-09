(ns app.subs
  (:require
   [clojure.string :as str]
   [re-frame.core :as rf]
   [lib.db :as lib-db]))

(rf/reg-sub
 :workspace/files
 (fn [_ _]
   (let [docs (lib-db/documents)]
     (into {} (map (fn [doc]
                     (let [name (last (str/split (:uri doc) #"/"))]
                       [(:uri doc) (assoc doc :name name)])) docs)))))

(rf/reg-sub
 :workspace/active-file
 (fn [_ _]
   (lib-db/active-uri)))

(rf/reg-sub
 :active-lang
 (fn [_ _]
   (lib-db/active-lang)))

(rf/reg-sub
 :languages
 (fn [db _]
   (:languages db)))

(rf/reg-sub
 :default-language
 (fn [db _]
   (:default-language db)))

(rf/reg-sub
 :active-name
 (fn [_ _]
   (when-let [uri (lib-db/active-uri)]
     (last (str/split uri #"/")))))

(rf/reg-sub
 :active-content
 (fn [_ _]
   (lib-db/active-text)))

(rf/reg-sub
 :lsp/connected?
 (fn [db _]
   (let [lang (or (lib-db/active-lang) "text")]
     (get-in db [:lsp lang :connected?]))))

(rf/reg-sub
 :search/visible?
 (fn [db _]
   (:visible? (:search db))))

(rf/reg-sub
 :filtered-logs
 (fn [_ [_ term]]
   (filter #(str/includes? (:message %) term) (lib-db/logs))))

(rf/reg-sub
 :status
 (fn [db _]
   (:status db)))

(rf/reg-sub
 :search-results
 (fn [db _]
   (:results (:search db))))

(rf/reg-sub
 :rename/visible?
 (fn [db _]
   (get-in db [:modals :rename :visible?])))

(rf/reg-sub
 :rename/new-name
 (fn [db _]
   (get-in db [:modals :rename :new-name])))

(rf/reg-sub
 :editor/cursor
 (fn [db _]
   (get-in db [:editor :cursor])))

(rf/reg-sub
 :editor/selection
 (fn [db _]
   (get-in db [:editor :selection])))

(rf/reg-sub
 :lsp/diagnostics
 (fn [_ _]
   (lib-db/diagnostics)))

(rf/reg-sub
 :lsp/symbols
 (fn [_ _]
   (lib-db/symbols)))

(rf/reg-sub
 :logs
 (fn [_ _]
   (lib-db/logs)))

(rf/reg-sub
 :logs-visible?
 (fn [db _]
   (:logs-visible? db)))

(rf/reg-sub
 :logs-height
 (fn [db _]
   (or (:logs-height db) 200)))

(rf/reg-sub
 :editor/highlights
 (fn [db _]
   (get-in db [:editor :highlights])))

(rf/reg-sub
 :editor/ready
 (fn [db _]
   (get-in db [:editor :ready])))
