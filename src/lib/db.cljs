(ns lib.db)

;; Schema for Datascript entities; used in both lib and app for consistency.
(def schema {:symbol/parent {:db/valueType :db.type/ref}
             :symbol/range {:db/cardinality :db.cardinality/one}
             :diagnostic/range {:db/cardinality :db.cardinality/one}})
