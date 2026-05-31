From Stdlib Require Import Lists.List.
Import ListNotations.

Inductive state : Type :=
| Disconnected
| Connecting
| Connected
| Initializing
| Initialized
| Disconnecting
| Error.

Definition connected_state (s : state) : bool :=
  match s with
  | Connected | Initializing | Initialized => true
  | _ => false
  end.

Definition initialized_state (s : state) : bool :=
  match s with
  | Initialized => true
  | _ => false
  end.

Definition connecting_state (s : state) : bool :=
  match s with
  | Connecting => true
  | _ => false
  end.

Definition reachable_state := connected_state.

Record flags : Type := {
  flag_connected : bool;
  flag_initialized : bool;
  flag_connecting : bool;
  flag_reachable : bool
}.

Definition state_flags (s : state) : flags :=
  {| flag_connected := connected_state s;
     flag_initialized := initialized_state s;
     flag_connecting := connecting_state s;
     flag_reachable := reachable_state s |}.

Definition valid_transition (from to : state) : bool :=
  match from, to with
  | Disconnected, Connecting => true
  | Connecting, Connected => true
  | Connecting, Error => true
  | Connecting, Disconnected => true
  | Connected, Initializing => true
  | Connected, Disconnecting => true
  | Connected, Error => true
  | Initializing, Initialized => true
  | Initializing, Disconnecting => true
  | Initializing, Error => true
  | Initializing, Disconnected => true
  | Initialized, Disconnecting => true
  | Initialized, Error => true
  | Initialized, Disconnected => true
  | Disconnecting, Disconnected => true
  | Error, Disconnected => true
  | Error, Connecting => true
  | _, _ => false
  end.

Definition all_states : list state :=
  [Disconnected; Connecting; Connected; Initializing;
   Initialized; Disconnecting; Error].

Definition all_transitions : list (state * state) :=
  [(Disconnected, Connecting);
   (Connecting, Connected);
   (Connecting, Error);
   (Connecting, Disconnected);
   (Connected, Initializing);
   (Connected, Disconnecting);
   (Connected, Error);
   (Initializing, Initialized);
   (Initializing, Disconnecting);
   (Initializing, Error);
   (Initializing, Disconnected);
   (Initialized, Disconnecting);
   (Initialized, Error);
   (Initialized, Disconnected);
   (Disconnecting, Disconnected);
   (Error, Disconnected);
   (Error, Connecting)].

Theorem initialized_implies_connected :
  forall s, initialized_state s = true -> connected_state s = true.
Proof.
  destruct s; simpl; intros H; try discriminate; reflexivity.
Qed.

Theorem flags_are_derived :
  forall s,
    flag_connected (state_flags s) = connected_state s /\
    flag_initialized (state_flags s) = initialized_state s /\
    flag_connecting (state_flags s) = connecting_state s /\
    flag_reachable (state_flags s) = reachable_state s.
Proof.
  intros s. destruct s; simpl; repeat split; reflexivity.
Qed.

Theorem initialized_flag_implies_connected_flag :
  forall s,
    flag_initialized (state_flags s) = true ->
    flag_connected (state_flags s) = true.
Proof.
  intros s H.
  destruct (flags_are_derived s) as [Hc [Hi [_ _]]].
  rewrite Hi in H. rewrite Hc.
  apply initialized_implies_connected. exact H.
Qed.

Theorem valid_transition_complete :
  forall from to,
    valid_transition from to = true <-> In (from, to) all_transitions.
Proof.
  destruct from, to; simpl; firstorder congruence.
Qed.
