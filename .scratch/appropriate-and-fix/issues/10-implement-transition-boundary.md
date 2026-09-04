# 10 — Implement the transition boundary and prove the cold-start bug is dead

Type: task
Status: resolved
Blocked by: 03, 09

## Question

**Ticket 09 is resolved.** Implement against `docs/reminder-state-machine.md` (transition table, effects, invariants, and the numbered test list); use the vocabulary in `CONTEXT.md`.

Implement the state machine decided in ticket 09, and route every existing mutation path through it: `Main`'s `scheduleAndReshowAllReminders()`, `ReminderAction.run` (`Notify`, `Nag`, `MarkDone`), and the edit dialog.

Review finding this closes: **"A cold alarm can deliver the same reminder twice"** (high). `ReminderManager.kt:122-127` — `Notify` calls `showReminder` with no check of the stored status, and the notification builder does not set `setOnlyAlertOnce(true)`, so the duplicate produces real duplicate sound and vibration on the ordinary cold-start delivery path.

The regression test is the point of this ticket, not an afterthought. It must execute application reconciliation **first**, then the delivered `Notify` action, and assert exactly one alert and one state transition. That ordering is the bug; a test that does not reproduce it is not a test of this.

`setOnlyAlertOnce(true)` is worth adding regardless — it is defence in depth, not the fix. The fix is the guard.

**Done when** the ordering above is covered by a passing test that fails against the current code, and the gate is green.

## Resolution (2026-09-05)

The state machine of `docs/reminder-state-machine.md` is implemented, and every
path that changed a reminder now goes through it.

**File layout.**

- `app/src/main/java/app/ding/state/ReminderTransition.kt` — the commands, the
  outcomes, the effects and the pure `transition(stored, command, now)`. No
  `android.*` or `androidx.*` import anywhere in the file, so it runs as a plain
  JVM test with a fixed clock. Times are epoch milliseconds.
- `app/src/main/java/app/ding/state/ReminderCommandRunner.kt` — the runner:
  lock, read, transition, write, announce, then effects, in that order. It takes
  a `ReminderStore` and a `ReminderEffectExecutor` as interfaces and its clock as
  a function, so a test injects fakes. Also android-free.
- `app/src/main/java/app/ding/ReminderManager.kt` — the Android half: the effect
  executor (alarms, notifications, pending intents), the `ReminderAction`
  pending-intent payloads, and the three public entry points `run`,
  `addReminder` and `reconcileAllReminders`.
- `app/src/main/java/app/ding/ReminderStorage.kt` — reading stays public for the
  list and the edit dialog; the shared-preferences write is now a private
  `ReminderStore` implementation handed to the runner. `updateReminder`,
  `updateReminders`, `addReminder` and `removeReminders` are gone.
- `app/src/main/java/app/ding/data/Reminder.kt` — `status` is a `val`.
- Tests: `app/src/test/java/app/ding/state/ReminderTransitionTest.kt` (tests 1
  to 7, 10 and 11 of the doc's list) and
  `app/src/test/java/app/ding/state/ReminderCommandRunnerTest.kt` (the same cold
  start driven end to end through the runner, and id allocation).

**What routes through the runner.** `Main.onCreate` → `reconcileAllReminders`
(Reconcile over every stored reminder, one write). `ReminderAction.run` →
Deliver, Nag or MarkDone; it no longer calls `ReminderStorage.getReminder`, so
it cannot throw `ReminderNotFoundException` on a missing reminder. The add
dialog → Add. The edit dialog → Edit when the due time is unchanged, Reschedule
when it is not. The reminders list → MarkDone and Delete per selected id. No
other code touches `status` or writes the store.

**Alarms carry their due time.** `ReminderAction.Notify` is renamed `Deliver`
(the glossary's word) and both `Deliver` and `Nag` now serialize the due time
they were set for. The transition function compares it to the stored due time
and treats a mismatch as stale: `Unchanged`, no effects, no error. A missing
reminder is `Unchanged` plus cancel-alarm and cancel-notification.
`setOnlyAlertOnce(true)` is on the notification builder as defence in depth; the
guard is the fix.

**Red then green.** The transition function was written first with the Deliver
row deliberately unguarded — the shape of the old `Notify` path, which showed
unconditionally — and test 1 run against it:

```
1  cold start: reconcile then the delivered alarm alerts once and writes once
org.opentest4j.AssertionFailedError: Collection should have size 1 but has size 2. Values: [ShowNotification(reminder=Reminder(id=2, date=Sat Sep 05 13:59:00 CEST 2026, naggingRepeatInterval=0, text=Water the plants, status=NOTIFIED), kind=DELIVER), ShowNotification(reminder=Reminder(id=2, date=Sat Sep 05 13:59:00 CEST 2026, naggingRepeatInterval=0, text=Water the plants, status=NOTIFIED), kind=DELIVER)]
expected:<1> but was:<2>
	at app.ding.state.ReminderTransitionTest$1$1.invokeSuspend(ReminderTransitionTest.kt:46)
```

Two shows and two writes for one reminder: the reported bug, reproduced in the
ordering that causes it (reconciliation first, the alarm that woke the process
second). Adding the guard — deliver only from `SCHEDULED` and only when the
alarm's expected due time is the stored one — turns it green. Eleven tests in
the two new classes pass, 76 in the module, and `scripts/check.sh` is green.

**Behaviour that changed for the user.** A due time that is not in the future is
now `Refused(PastDue)` in the add and edit dialogs, which show the existing
"Invalid date: must be in the future" toast and stay open so the time can be
corrected, instead of storing a reminder whose alarm fires at once. That is the
doc's past-due rule; the dialogs already refused past *days*, so only a time
earlier today reaches it.

**Left to the follow-on tickets.** Ticket 11 owns the edit-versus-reschedule
semantics; the dialog does the simplest correct thing (the due time decides) and
tests 8 and 9 are not written. Ticket 12 owns the typed persistence failure: the
runner already branches on a write that did not commit and runs no effects and
no announcement, but it returns `Unchanged` rather than a failure type, and the
fake store in the runner test has the `writeSucceeds` switch ticket 12 needs.
Ticket 13 owns decode recovery. Test 14 (invariants as a property) is optional
and not written. Nothing was moved off the main thread: the runner is now the
one place to do that, and the map still lists it as open.

**Change to the model document.** One sentence in `docs/reminder-state-machine.md`
saying the implementation uses epoch milliseconds rather than an `Instant`,
matching the `Date` the store already holds. No contradiction was found in the
model itself; the transition table is implemented row for row.
