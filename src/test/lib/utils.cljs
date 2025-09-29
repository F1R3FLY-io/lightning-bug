(ns test.lib.utils
  (:require [clojure.core.async :refer [go <! timeout]]
            [lib.db :as db]))

(defn ref->editor
  ([^js ref]
   (.-current ref)))

(defn editor->close-document!
  ([^js editor]
   (.closeDocument editor))
  ([^js editor uri]
   (.closeDocument editor uri)))

(defn editor->save-document!
  ([^js editor]
   (.saveDocument editor))
  ([^js editor uri]
   (.saveDocument editor uri)))

(defn editor->rename-document!
  ([^js editor new-file-or-uri]
   (.renameDocument editor new-file-or-uri))
  ([^js editor new-file-or-uri old-file-or-uri]
   (.renameDocument editor new-file-or-uri old-file-or-uri)))

(defn editor->open-document!
  ([^js editor uri text lang]
   (.openDocument editor uri text lang))
  ([^js editor uri text lang make-active?]
   (.openDocument editor uri text lang make-active?)))

(defn editor->cursor
  ([^js editor]
   (.getCursor editor)))

(defn editor->set-cursor!
  ([^js editor loc]
   (.setCursor editor loc)))

(defn editor->clear-highlight!
  ([^js editor]
   (.clearHighlight editor)))

(defn editor->highlight-range!
  ([^js editor ^js from-js ^js to-js]
   (.highlightRange editor from-js to-js)))

(defn editor->activate-document!
  ([^js editor uri]
   (.activateDocument editor uri)))

(defn editor->set-selection!
  ([^js editor ^js from-js ^js to-js]
   (.setSelection editor from-js to-js)))

(defn editor->set-text!
  ([^js editor text]
   (.setText editor text))
  ([^js editor text uri]
   (.setText editor text uri)))

(defn editor->center-on-range!
  ([^js editor ^js from-js ^js to-js]
   (.centerOnRange editor from-js to-js)))

(defn editor->text
  ([^js editor]
   (.getText editor))
  ([^js editor uri]
   (.getText editor uri)))

(defn editor->events
  [^js editor]
  (.getEvents editor))

(defn editor->state
  [^js editor]
  (.getState editor))

(defn editor->file-path
  ([^js editor]
   (.getFilePath editor))
  ([^js editor uri]
   (.getFilePath editor uri)))

(defn editor->file-uri
  ([^js editor]
   (.getFileUri editor))
  ([^js editor uri]
   (.getFileUri editor uri)))

(defn editor->diagnostics
  ([^js editor]
   (.getDiagnostics editor))
  ([^js editor uri]
   (.getDiagnostics editor uri)))

(defn editor->symbols
  ([^js editor]
   (.getSymbols editor))
  ([^js editor uri]
   (.getSymbols editor uri)))

(defn editor->db
  [^js editor]
  (.getDb editor))

(defn editor->search-term
  [^js editor]
  (.getSearchTerm editor))

(defn editor->log-level
  [^js editor]
  (.getLogLevel editor))

(defn editor->set-log-level!
  [^js editor level]
  (.setLogLevel editor level))

(defn editor->query
  ([^js editor query]
   (.query editor query))
  ([^js editor query params]
   (.query editor query params)))

(defn wait-for
  [pred timeout-ms]
  (go
    (try
      (let [start (js/Date.now)]
        (loop []
          (if (pred)
            [:ok true]
            (if (> (- (js/Date.now) start) timeout-ms)
              [:ok false]
              (do
                (<! (timeout 10))
                (recur))))))
      (catch :default e
        [:error (js/Error. (str "(wait-for pred " timeout-ms ") failed") #js {:cause e})]))))

(defn wait-for-ready [editor-ref timeout-ms]
  (wait-for #(some-> (ref->editor editor-ref) .isReady) timeout-ms))

(defn wait-for-event [events-atom event-type timeout-ms]
  (wait-for #(some (fn [evt] (= event-type (:type evt))) @events-atom) timeout-ms))

(defn wait-for-uri [uri timeout-ms]
  (wait-for #(db/document-id-by-uri uri) timeout-ms))

(defn wait-for-opened-uri [uri timeout-ms]
  (wait-for #(db/document-opened-by-uri? uri) timeout-ms))
