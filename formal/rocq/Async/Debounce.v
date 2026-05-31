Inductive mount_state : Type :=
| Mounted
| Unmounted.

Definition fire_callback (m : mount_state) (writes : nat) : nat :=
  match m with
  | Mounted => S writes
  | Unmounted => writes
  end.

Definition safe_to_mutate (m : mount_state) : bool :=
  match m with
  | Mounted => true
  | Unmounted => false
  end.

Theorem unmounted_callback_no_write :
  forall writes,
    fire_callback Unmounted writes = writes.
Proof.
  intros writes. reflexivity.
Qed.

Theorem mounted_callback_writes_once :
  forall writes,
    fire_callback Mounted writes = S writes.
Proof.
  intros writes. reflexivity.
Qed.

Theorem callback_mutates_only_when_mounted :
  forall m writes,
    fire_callback m writes <> writes ->
    safe_to_mutate m = true.
Proof.
  destruct m; simpl; intros writes H.
  - reflexivity.
  - exfalso. apply H. reflexivity.
Qed.
