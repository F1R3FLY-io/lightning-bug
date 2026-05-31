From Stdlib Require Import Arith.PeanoNat Lists.List.
Import ListNotations.

Definition pending := list nat.
Definition unique_pending (p : pending) := NoDup p.
Definition add_request (id : nat) (p : pending) := id :: p.
Definition remove_request (id : nat) (p : pending) := remove Nat.eq_dec id p.

Theorem add_fresh_preserves_unique :
  forall id p,
    ~ In id p ->
    unique_pending p ->
    unique_pending (add_request id p).
Proof.
  unfold unique_pending, add_request.
  intros id p Hfresh Hnodup.
  constructor; assumption.
Qed.

Theorem remove_request_not_pending :
  forall id p, ~ In id (remove_request id p).
Proof.
  intros id p.
  unfold remove_request.
  apply remove_In.
Qed.

Theorem remove_absent_is_noop :
  forall id p,
    ~ In id p ->
    remove_request id p = p.
Proof.
  intros id p.
  unfold remove_request.
  induction p as [|x xs IH]; simpl; intros Hnotin.
  - reflexivity.
  - destruct (Nat.eq_dec id x) as [Heq | Hneq].
    + subst. exfalso. apply Hnotin. left. reflexivity.
    + f_equal. apply IH. intros Hin.
      apply Hnotin. right. exact Hin.
Qed.

Theorem remove_preserves_subset :
  forall id other p,
    In other (remove_request id p) -> In other p.
Proof.
  intros id other p.
  unfold remove_request.
  induction p as [|x xs IH]; simpl; intros Hin.
  - contradiction.
  - destruct (Nat.eq_dec id x).
    + right. apply IH. exact Hin.
    + destruct Hin as [Heq | Hin].
      * left. exact Heq.
      * right. apply IH. exact Hin.
Qed.
