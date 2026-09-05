# 23 — No nag before the due time, and Reconcile cleans up after DONE

Type: task
Status: open
Blocked by: —

## Question

Global review findings (reliability axis, medium, numbers 6 and 7),
`ReminderTransition.kt`. See
`../reviews/2026-09-05-global-review-reliability.md`.

**Clock rollback.** For a `NOTIFIED` nagging reminder, setting the clock back
makes `now < dueTime`. `nextNagTime` uses `floorMod` on `now - dueTime`, which
is negative, so it picks a nag time before the due time: due 10:00, now 09:00,
ten-minute interval, Reconcile arms a nag at 09:10, and the reminder nags
repeatedly before its own due time on the corrected clock. Rule: a nag is never
earlier than the due time plus one interval.

**Partial MarkDone.** The runner persists `DONE` before it runs the cancel
effects. If the process dies in that gap, or a cancel fails, the next Reconcile
sees `DONE`, emits nothing, and the old notification or alarm stays. Invariant 3
of the model document says a `DONE` reminder has an empty alarm slot and no
notification after Reconcile, and the current mixed-store test pins the
opposite. Fix the code and the transition table, not the invariant: Reconcile
over `DONE` emits `CancelAlarm` and `CancelNotification`. Both cancels are
idempotent, so the cost of doing it on every start is nil.

Fix:

- `nextNagTime`: when `now < dueTime`, answer `dueTime + interval`; otherwise
  as today. Tests with now before due, at the due time, one millisecond after,
  and on an exact interval boundary.
- Reconcile over a `DONE` reminder emits both cancels; update the row in
  `docs/reminder-state-machine.md` and the mixed-store test that pinned the old
  behaviour; add the restart-after-persist-before-effects case as a runner test
  with a fake effect executor.

**Done when** the four rollback tests and the DONE cleanup tests pass, and the
transition table matches the code.
