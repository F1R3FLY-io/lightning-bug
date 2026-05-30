(ns lib.workspace.doc-sync
  "Reactive cross-editor document synchronization.

  When two or more editors (panes) view the SAME (workspace, file), edits in one
  must propagate live to the others — Google-Docs-style — while each pane keeps its
  own cursor/selection/scroll. This namespace owns the transport: a per-(workspace,
  file) RxJS `Subject` carrying CodeMirror `ChangeSet` deltas.

  Correctness:
  - Authoritative persisted text remains DataScript (`:document/text`, synced by the
    EXP-011 idle path). This Subject is a low-latency *delta* channel between live
    views; it is downstream of the persisted model, not a replacement for it.
  - Echo-suppression is DOUBLE-guarded: (1) each delta is tagged with its origin
    `pane-id` and a subscriber ignores its own emissions; (2) a receiver applies the
    remote delta annotated with `external-set-annotation`, so the receiver's own
    updateListener treats it as API-driven and neither re-publishes it nor re-runs
    LSP/DataScript-as-user-edit (the same invariant `setText`/`activate-document` use).
  - Ordering: in-process, single-threaded, synchronous fan-out in publish order ⇒
    all panes apply deltas in the same order ⇒ convergence (no OT/CRDT needed).
  - Cost: `has-peers?` lets the producer skip serialization entirely when a file has
    no second viewer, so the common single-editor case pays ~nothing."
  (:require
   ["@codemirror/state" :refer [ChangeSet]]
   ["rxjs" :refer [Subject]]
   [lib.editor.annotations :refer [external-set-annotation]]))

(defn get-or-create-stream!
  "Returns the {:subject :seq :ref-count} entry for (ws, uri), creating it (with a
  fresh Subject) on first use and incrementing the subscriber ref-count."
  [ws uri]
  (-> (swap! (:doc-streams ws) update uri
             (fn [entry]
               (if entry
                 (update entry :ref-count inc)
                 {:subject (Subject.) :seq 0 :ref-count 1})))
      (get uri)))

(defn release-stream!
  "Decrements the subscriber ref-count for (ws, uri); drops the stream entirely when
  the last subscriber leaves (so a stale Subject is not retained)."
  [ws uri]
  (swap! (:doc-streams ws)
         (fn [m]
           (let [entry (get m uri)]
             (cond
               (nil? entry) m
               (<= (:ref-count entry) 1) (dissoc m uri)
               :else (update-in m [uri :ref-count] dec))))))

(defn has-peers?
  "True if (ws, uri) has more than one subscribed pane — i.e. a local edit needs to
  be propagated. Lets the producer skip delta serialization in the single-pane case."
  [ws uri]
  (> (get-in @(:doc-streams ws) [uri :ref-count] 0) 1))

(defn publish-delta!
  "Publishes a delta {:origin <pane-id> :changes <ChangeSet.toJSON>} on (ws, uri)'s
  Subject, stamping it with a monotonic :seq and the :uri. No-op if no stream exists."
  [ws uri delta]
  (when-let [entry (get @(:doc-streams ws) uri)]
    (let [seq (:seq entry)]
      (swap! (:doc-streams ws) update-in [uri :seq] inc)
      (.next ^js (:subject entry) (assoc delta :uri uri :seq seq)))))

(defn subscribe-pane
  "Subscribes `pane-id` to (ws, uri)'s change stream. `on-remote-delta` is invoked
  with each delta that did NOT originate from this pane (origin echo-suppression).
  Returns the RxJS subscription (call .unsubscribe on cleanup; pair with release-stream!)."
  [ws uri pane-id on-remote-delta]
  (let [{:keys [subject]} (get-or-create-stream! ws uri)]
    (.subscribe ^js subject
                (fn [delta]
                  (when (not= (:origin delta) pane-id)
                    (on-remote-delta delta))))))

(defn apply-remote-delta!
  "Applies a remote delta to this pane's CodeMirror view: reconstructs the ChangeSet,
  dispatches it, MAPS this pane's selection through the change (preserving the local
  cursor), does NOT scroll, and annotates it external so it isn't re-published."
  [^js view delta]
  (when view
    (let [^js st (.-state view)
          changes (.fromJSON ChangeSet (:changes delta))]
      (.dispatch view #js {:changes changes
                           :selection (.map (.-selection st) changes)
                           :scrollIntoView false
                           :annotations (.of external-set-annotation true)}))))
