(ns test.app.views.editor-test
  (:require
   ["react" :as react]
   ["react-dom/client" :as rdclient]
   [reagent.core :as r]
   [lib.db :refer [conn]]
   [app.db :refer [default-db]]
   [app.events :as e]
   [app.shared :refer [editor-ref-atom]]
   [datascript.core :as d]
   [re-frame.db :as rf-db]
   [app.views.editor :as editor]
   [re-frame.core :as rf]
   [re-posh.core :as rp]
   [day8.re-frame.test :as rf-test]
   [taoensso.timbre :as log]
   [clojure.core.async :refer [go <! timeout]]
   [clojure.test :refer [deftest is use-fixtures async]]))

(set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) true)

;; Mock WebSocket to prevent real LSP connections during tests, avoiding connection errors.
(def mock-socket (js/Object.))
(set! (.-binaryType mock-socket) "arraybuffer")
(set! (.-onopen mock-socket) (fn []))
(set! (.-onmessage mock-socket) (fn []))
(set! (.-onclose mock-socket) (fn []))
(set! (.-onerror mock-socket) (fn []))
(set! (.-send mock-socket) (fn [_]))

(def old-ws js/WebSocket)

(defn act-flush [f]
  (react/act (fn [] (f) (r/flush))))

(defn act-mount [container comp]
  (let [root-atom (atom nil)]
    (act-flush
     (fn []
       (let [root (rdclient/createRoot container)]
         (.render root (r/as-element comp))
         (reset! root-atom root))))
    @root-atom))

(defn act-unmount [root]
  (act-flush #(.unmount root)))

(use-fixtures :each
  {:before (fn []
             (set! js/WebSocket (fn [_] mock-socket)) ;; Existing mock.
             (log/set-min-level! :trace)
             (d/reset-conn! conn (d/empty-db (:schema default-db)))
             (reset! rf-db/app-db {})
             (act-flush #(rf/dispatch-sync [::e/initialize]))
             ;; Remove :lsp-url from all languages to prevent any connection attempts.
             (swap! rf-db/app-db update :languages
                    (fn [langs]
                      (into {} (map (fn [[k v]] [k (dissoc v :lsp-url)]) langs))))
             (swap! rf-db/app-db assoc :languages (merge (:languages @rf-db/app-db) {"text" {:extensions [".txt"]
                                                                                             :fallback-highlighter "none"
                                                                                             :file-icon "fas fa-file text-secondary"}}) :default-language "text")
             (let [active (get-in @rf-db/app-db [:workspace :active-file])]
               (swap! rf-db/app-db assoc-in [:workspace :files active :language] "text")
               (swap! rf-db/app-db assoc-in [:workspace :files active :name] "untitled.txt"))
             (rf/clear-subscription-cache!)
             (rp/connect! conn))
   :after (fn []
            (set! js/WebSocket old-ws)
            (log/set-min-level! :debug))}) ;; Existing restore.

(deftest editor-renders
  (async done
    (go
      (let [container (js/document.createElement "div")]
        (set! (.-display (.-style container)) "none")
        (js/document.body.appendChild container)
        (let [root (act-mount container [:f> editor/component])]
          (<! (timeout 100))
          (act-flush identity)
          (log/debug "Checking for editor container after render")
          (is (some? (.querySelector container ".code-editor")) "Editor container rendered")
          (act-unmount root))
        (js/document.body.removeChild container)
        (done)))))

(deftest editor-no-file
  (async done
    (go
      (swap! rf-db/app-db assoc-in [:workspace :active-file] nil)
      (let [container (js/document.createElement "div")]
        (set! (.-display (.-style container)) "none")
        (js/document.body.appendChild container)
        (let [root (act-mount container [:f> editor/component])]
          (<! (timeout 100))
          (act-flush identity)
          (log/debug "Checking for no-file message after render")
          (is (some? (.querySelector container "div")) "Renders message for no active file")
          (act-unmount root))
        (js/document.body.removeChild container)
        (done)))))

(deftest editor-state-persists-across-rerenders
  (rf-test/run-test-async
   (let [container (js/document.createElement "div")]
     (set! (.-display (.-style container)) "none")
     (js/document.body.appendChild container)
     (let [root (act-mount container [:f> editor/component])]
       (rf-test/wait-for
        [::e/editor-ready]
        (let [initial-instance (.-current @editor-ref-atom)]
          (react/act #(rf/dispatch [::e/editor-update-content "force rerender"]))
          (rf-test/wait-for
           [::e/editor-update-content]
           (let [^js post-instance (.-current @editor-ref-atom)]
             (is (= initial-instance post-instance) "Editor ref persists across content change/rerender")
             (is (.isReady post-instance) "Editor remains ready after rerender"))
           (act-unmount root)
           (js/document.body.removeChild container))))))))

(deftest editor-cursor-update
  (rf-test/run-test-async
   (log/debug ":: BEGIN :: editor-cursor-update")
   (let [container (js/document.createElement "div")]
     (set! (.-display (.-style container)) "none")
     (js/document.body.appendChild container)
     (let [root (act-mount container [:f> editor/component])]
       (log/debug ":: 1 ::")
       (rf-test/wait-for
        [::e/editor-ready] {:timeout 1000}
        (log/debug ":: 2 ::")
        (let [^js instance (.-current @editor-ref-atom)]
          (when instance
            (.setText instance "line1\nline2")))
        (log/debug ":: 3 ::")
        (rf-test/wait-for
         [::e/editor-update-content] {:timeout 1000}
         (log/debug ":: 4 ::")
         (react/act #(rf/dispatch [::e/set-editor-cursor {:line 2 :column 3}]))
         (log/debug ":: 5 ::")
         (rf-test/wait-for
          [::e/update-cursor] {:timeout 1000}
          (log/debug ":: 6 ::")
          (r/with-let [cursor @(rf/subscribe [:editor/cursor])]
            (is (= {:line 2 :column 3} cursor) "Cursor updated")
            ;; (log/debug ":: 7 ::")
            ;; (react/act #(.unmount root))
            ;; (log/debug ":: 8 ::")
            ;; (js/document.body.removeChild container)
            (log/debug ":: END :: editor-cursor-update")))))))))

;; (deftest editor-highlight-range
;;   (rf-test/run-test-async
;;    (log/debug ":: BEGIN :: editor-highlight-range")
;;    (let [container (js/document.createElement "div")]
;;      (set! (.-display (.-style container)) "none")
;;      (js/document.body.appendChild container)
;;      (log/debug ":: 0 ::")
;;      (let [root (act-mount container [:f> editor/component])]
;;        (log/debug ":: 1 ::")
;;        (rf-test/wait-for
;;         [::e/editor-ready]
;;         (log/debug ":: 2 ::")
;;         (let [instance (.-current @editor-ref-atom)]
;;           (.setText instance "lorem ipsum")
;;           (act-flush identity))
;;         (log/debug ":: 3 ::")
;;         (rf-test/wait-for
;;          [::e/editor-update-content]
;;          (log/debug ":: 4 ::")
;;          (act-flush #(rf/dispatch [::e/set-highlight-range {:from {:line 1 :column 1} :to {:line 1 :column 6}}]))
;;          (log/debug ":: 5 ::")
;;          (rf-test/wait-for
;;           [::e/update-highlights]
;;           (log/debug ":: 6 ::")
;;           (is (= {:from {:line 1 :column 1} :to {:line 1 :column 6}} @(rf/subscribe [:editor/highlights])) "Highlights updated")
;;           (log/debug "Verifying highlight after set")
;;           (act-flush #(rf/dispatch [::e/set-highlight-range nil]))
;;           (log/debug ":: 7 ::")
;;           (rf-test/wait-for
;;            [::e/update-highlights]
;;            (log/debug ":: 8 ::")
;;            (r/with-let [highlights @(rf/subscribe [:editor/highlights])]
;;              (is (nil? highlights) "Highlights reset")
;;              ;; (log/debug "Verifying clear after set nil")
;;              ;; (act-unmount root)
;;              ;; (js/document.body.removeChild container)
;;              (log/debug ":: END :: editor-highlight-range"))))))))))

;; (deftest editor-full-cycle-integration
;;   (rf-test/run-test-async
;;    (let [container (js/document.createElement "div")]
;;      (set! (.-display (.-style container)) "none")
;;      (js/document.body.appendChild container)
;;      (let [root (act-mount container [:f> editor/component])]
;;        (log/debug "Starting full cycle: render, update content, set cursor, highlight")
;;        (rf-test/wait-for
;;         [::e/editor-ready]
;;         (let [instance (.-current @editor-ref-atom)]
;;           (.setText instance "lorem ipsum"))
;;         (rf-test/wait-for
;;          [::e/editor-update-content]
;;          (is (some? (.querySelector container ".code-editor")) "Initial render")
;;          (react/act #(rf/dispatch [::e/editor-update-content "integrated"]))
;;          (is (= "integrated" @(rf/subscribe [:active-content])) "Content updated")
;;          (react/act #(rf/dispatch [::e/set-editor-cursor {:line 1 :column 5}]))
;;          (rf-test/wait-for
;;           [::e/update-cursor]
;;           (is (= {:line 1 :column 5} @(rf/subscribe [:editor/cursor])) "Cursor set")
;;           (react/act #(rf/dispatch-sync [::e/set-highlight-range
;;                                          {:from {:line 1 :column 1}
;;                                           :to {:line 1 :column 10}}]))
;;           (act-unmount root)
;;           (js/document.body.removeChild container))))))))
