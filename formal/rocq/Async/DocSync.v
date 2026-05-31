From Stdlib Require Import Bool.Bool Lists.List.
Import ListNotations.

Inductive pane : Type :=
| PaneA
| PaneB.

Definition pane_eq_dec : forall (a b : pane), {a = b} + {a <> b}.
Proof.
  decide equality.
Defined.

Definition pane_eqb (a b : pane) : bool :=
  if pane_eq_dec a b then true else false.

Lemma pane_eqb_refl :
  forall p, pane_eqb p p = true.
Proof.
  intros p. unfold pane_eqb. destruct (pane_eq_dec p p); congruence.
Qed.

Lemma pane_eqb_false_neq :
  forall a b, pane_eqb a b = false -> a <> b.
Proof.
  intros a b H.
  unfold pane_eqb in H.
  destruct (pane_eq_dec a b); congruence.
Qed.

Definition recipients (origin : pane) (subscribers : list pane) : list pane :=
  filter (fun p => negb (pane_eqb p origin)) subscribers.

Theorem no_origin_echo :
  forall origin subscribers,
    ~ In origin (recipients origin subscribers).
Proof.
  intros origin subscribers Hin.
  unfold recipients in Hin.
  apply filter_In in Hin as [_ Hpred].
  rewrite pane_eqb_refl in Hpred.
  discriminate.
Qed.

Definition apply_remote_delta (origin target : pane) (local_text : nat) : nat :=
  if pane_eq_dec origin target then local_text else S local_text.

Theorem origin_not_updated_by_remote_echo :
  forall origin text,
    apply_remote_delta origin origin text = text.
Proof.
  intros origin text.
  unfold apply_remote_delta.
  destruct (pane_eq_dec origin origin); congruence.
Qed.

Theorem peer_receives_remote_delta :
  forall origin target text,
    origin <> target ->
    apply_remote_delta origin target text = S text.
Proof.
  intros origin target text Hneq.
  unfold apply_remote_delta.
  destruct (pane_eq_dec origin target); congruence.
Qed.
