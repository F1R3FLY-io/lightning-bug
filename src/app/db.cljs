(ns app.db
  (:require [datascript.core :as d]))

(def schema {:symbol/parent {:db/valueType :db.type/ref}
             :symbol/range {:db/cardinality :db.cardinality/one}
             :diagnostic/location {:db/cardinality :db.cardinality/one}})

(def default-db
  {:workspace {:files {} :active-file nil}
   :panes {:layout [:editor :explorer :symbols :diagnostics :output]
           :visible? true}
   :lsp {:transport :websocket
         :url "ws://localhost:8080"
         :connection nil
         :diagnostics []
         :symbols []
         :logs []}
   :ds-conn (d/create-conn schema)})
