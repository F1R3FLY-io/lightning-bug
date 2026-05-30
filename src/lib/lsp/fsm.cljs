(ns lib.lsp.fsm
  "Pure LSP connection-state finite-state machine (NO dependencies). Shared by
  lib.lsp.client (which writes the keyword :state as the single source of truth) and
  lib.lsp.connection-manager (whose protocol readers derive from it). Kept dependency-free
  so both can require it without a cycle.")

(def STATES
  "Valid connection states."
  #{:disconnected
    :connecting
    :connected
    :initializing
    :initialized
    :disconnecting
    :error})

(def TRANSITIONS
  "Valid state transitions."
  {:disconnected #{:connecting}
   :connecting #{:connected :error :disconnected}
   :connected #{:initializing :disconnecting :error}
   :initializing #{:initialized :error :disconnected}
   :initialized #{:disconnecting :error :disconnected}
   :disconnecting #{:disconnected}
   :error #{:disconnected :connecting}})

(defn valid-transition?
  "Returns true if the transition from `current` to `next` state is valid."
  [current next]
  (contains? (get TRANSITIONS current #{}) next))

(defn state->connected?
  "True for states in which the socket is open (connected/initializing/initialized)."
  [state]
  (contains? #{:connected :initializing :initialized} state))

(defn state->initialized?
  "True only once the LSP initialize handshake has completed."
  [state]
  (= :initialized state))

(defn state->flags
  "Derived boolean projection of a keyword :state — the transitional mirror for code
  and tests still reading the legacy :connected?/:initialized?/:connecting?/:reachable?
  flags. Removed once all readers query the keyword model."
  [state]
  {:connected? (state->connected? state)
   :initialized? (state->initialized? state)
   :connecting? (= :connecting state)
   :reachable? (state->connected? state)})
