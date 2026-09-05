# 23 — No nag before the due time, and Reconcile cleans up after DONE

Type: task
Status: resolved
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

## Resolution (2026-09-05)

Both fixes are in `app/src/main/java/app/ding/state/ReminderTransition.kt`, which
still has no Android imports, so both are pinned by plain JVM tests with a fixed
clock.

**A nag is never earlier than the due time plus one interval.** `nextNagTime` now
answers `dueTime + interval` when `now` is before the due time, and counts from the
due time as before otherwise. The case only arises after the clock is set back: a
reminder that has already been delivered then has a due time ahead of the clock the
app is running on, and `Math.floorMod` on a negative difference picked the
occurrence *before* the due time — due 10:00, now 09:00 and a ten minute interval
gave a nag at 09:10, repeating until the clock caught up. The rule reads the same
way from either side: the first nag is one interval after the due time, whatever
the clock says. Six values are checked in one test — an hour and a millisecond
before the due time, at it, a millisecond after it, and on two exact interval
boundaries, where the answer is the following occurrence rather than the instant
the alarm fires — plus a reconciliation of a delivered reminder whose due time is
an hour ahead, which is what a rollback looks like in the store.

**Reconcile over a `DONE` reminder emits both cancels.** The runner persists before
it runs effects, so a process that dies between the write that marks a reminder
done and its cancels — or a cancel that fails — leaves a done reminder with a nag
alarm still in its slot or a notification still on screen. Reconcile answering
`Unchanged` with no effects meant nothing would ever repair that, which contradicted
invariant 3 of `docs/reminder-state-machine.md` ("a `DONE` reminder has an empty
slot and no notification" after any command *and after Reconcile*). The code and
the transition table were the ones that were wrong; the invariant stands. Both
cancels are idempotent, so doing them for every done reminder on every start costs
nothing. The row in the transition table now reads `CancelAlarm; CancelNotification`
and a paragraph under it says why.

**Tests.** Three new ones in `ReminderTransitionTest` — the nag boundary values, the
reconciliation after a rollback, and a reconciliation of a done reminder with a due
time on each side of now — and one in `ReminderCommandRunnerTest` that plays the
restart out through the runner: a mark done run with an effect executor that does
nothing at all, standing for the process dying before the cancels, then a second
runner over the same store whose `reconcileAll` produces exactly `CancelAlarm` and
`CancelNotification`. Test 11, the mixed-store reconciliation, pinned the old
behaviour and now expects the cleanup. All four were written first and failed for
the right reason.

**Left out.** The cost of cancelling for every done reminder at every start is two
binder calls each, and done reminders accumulate forever because retention is still
unspecified; that is the retention item in the map, not this ticket. Nothing in the
Android half changed: `cancelAlarm` builds a pending intent to cancel a slot that is
usually already empty, which is what it already did for MarkDone and Delete.

## Review findings (2026-09-05)

- **DONE cleanup can cancel a live duplicate's freshly restored alarm** (high) —
  accepted, fixed by ticket 21. With two stored reminders sharing an id, say a
  future `SCHEDULED` and a `DONE` one both with id 2, `reconcileAll` runs
  `SetAlarm(2)` and then the new `CancelAlarm(2)`, since alarms and
  notifications share the id and effects run in store order. That store is
  exactly what ticket 21 makes unreadable (`DUPLICATE_ID`, quarantined before
  any effect runs), so the fix belongs there rather than in a second guard
  here; ticket 21 also carries the runner-level regression the reviewer asked
  for, a store with a duplicate id producing no `SetAlarm` followed by a
  `CancelAlarm`. Until 21 lands, `main` has that window; nothing is released
  from it.
