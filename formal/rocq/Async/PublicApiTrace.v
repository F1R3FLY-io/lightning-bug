From LightningBug.Async Require Import Fsm.

Record api_state : Type := {
  opened : bool;
  version : nat;
  pending_change : bool
}.

Definition api_inv (s : api_state) : Prop :=
  pending_change s = true -> opened s = true.

Definition did_open (s : api_state) : api_state :=
  {| opened := true;
     version := version s;
     pending_change := pending_change s |}.

Definition edit (s : api_state) : api_state :=
  if opened s
  then {| opened := opened s;
          version := S (version s);
          pending_change := true |}
  else s.

Definition flush_change (s : api_state) : api_state :=
  {| opened := opened s;
     version := version s;
     pending_change := false |}.

Theorem did_open_preserves_inv :
  forall s, api_inv s -> api_inv (did_open s).
Proof.
  intros s _.
  unfold api_inv, did_open. simpl.
  intros _. reflexivity.
Qed.

Theorem edit_without_open_is_noop :
  forall s,
    opened s = false ->
    edit s = s.
Proof.
  intros [is_open v pending] Hclosed.
  simpl in Hclosed. unfold edit. simpl.
  rewrite Hclosed. reflexivity.
Qed.

Theorem edit_preserves_inv :
  forall s, api_inv s -> api_inv (edit s).
Proof.
  intros [is_open v pending] Hinv.
  unfold api_inv, edit in *. simpl in *.
  destruct is_open.
  - intros _. reflexivity.
  - exact Hinv.
Qed.

Theorem pending_change_after_edit_requires_open :
  forall s,
    api_inv s ->
    pending_change (edit s) = true ->
    opened (edit s) = true.
Proof.
  intros s Hinv Hpending.
  apply edit_preserves_inv; assumption.
Qed.

Theorem initialized_public_lsp_is_connected :
  forall s,
    initialized_state s = true ->
    connected_state s = true.
Proof.
  apply initialized_implies_connected.
Qed.
