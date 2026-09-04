# 11 — Decide and implement edit versus reschedule for every status

Type: task
Status: resolved
Blocked by: 09

## Question

**Ticket 09 is resolved.** Implement against `docs/reminder-state-machine.md` (transition table, effects, invariants, and the numbered test list); use the vocabulary in `CONTEXT.md`.

Review finding: **"Saving an already-notified or done reminder creates an unscheduled past-due reminder"** (medium), `EditReminderDialogActivity.kt:71-91`.

Only `SCHEDULED` reminders restore their original due date. A `NOTIFIED` or `DONE` reminder keeps the dialog's initial current-minute value, and `buildReminderWithTimeTextNagging()` produces a builder whose status defaults to `SCHEDULED`. By the time the user presses OK that minute is normally already past, so `updateReminder(..., true)` cancels the current notification but schedules no replacement. The record sits `SCHEDULED` and past due until some later startup sweep surprises the user with it.

The bug is that "edit" and "reschedule" are the same code path with different intent. Decide the semantics — under ticket 09's state machine — and make them explicit:

- Editing the *text* of a `DONE` reminder should presumably leave it `DONE` and unscheduled.
- Rescheduling should require a future time and deliberately reset the status.
- **A reminder must never be saved `SCHEDULED` with a due date in the past.** That is the invariant worth encoding, wherever it belongs.

Tests for editing a reminder in each of the three statuses without touching the time picker.

**Done when** each status has defined, tested behaviour, and the invariant above is enforced at the boundary rather than in the dialog.

## Resolution (2026-09-05)

Edit and Reschedule were already distinct commands in the transition table
(ticket 10). What was missing is that the edit dialog could not tell them apart
honestly, because for a `NOTIFIED` or `DONE` reminder it never restored the
stored due time.

**The dialog now restores the stored due time for every state.**
`EditReminderDialogActivity.setupActivityWithReminder` called
`setSelectedDateTimeAndSelectionMode(reminder.calendar)` only when the reminder
was `SCHEDULED`; a notified or done one kept the minute the dialog happened to
open on. Pressing OK without touching the time therefore compared the picker's
current minute against the stored due time, found them different, and asked for
a Reschedule to a minute that had normally already passed. The `if` is gone.
What "untouched" is compared against is `dueTimeWhenOpened`, read from the new
`ReminderDialogActivity.selectedDueTime` right after the restore, so it is the
value the pickers actually took rather than the stored date before the pickers
cut its seconds off.

**The dialog's intent is a named function.** `editOrReschedule(reminderId,
dueTimeWhenOpened, chosenDueTime, text, naggingRepeatInterval)` in
`app/src/main/java/app/ding/state/ReminderTransition.kt` returns the command,
which is what makes the decision testable on a plain JVM: the dialog itself has
no test harness in this module (no Robolectric, no `androidTest`).

**The semantics, per state.**

- *Edit* — the due time is untouched. Text and nag settings change, the state
  never does, and the due time is not written. A `DONE` reminder stays `DONE`
  with an empty slot; a `NOTIFIED` reminder stays `NOTIFIED`, its notification
  is re-shown silently, and its nag alarm is reset from the new settings — or
  the slot is emptied when the edit turned nagging off.
- *Reschedule* — the due time moved. It lands `SCHEDULED` from any state,
  cancels the notification and sets the Deliver alarm, and a time that is not
  in the future is `Refused(PastDue)`. The dialog keeps its existing "Invalid
  date: must be in the future" toast as the user-facing message and stays open.

**Where the invariant lives.** In the transition function, not in the dialog.
Add and Reschedule are the only commands that write a due time and ticket 10
already refuses a past-due one on both, so Reschedule to the same past due time
was closed there too. The one path left open was Edit on a `SCHEDULED` reminder
already past due that Reconcile has not yet delivered: the doc's row had no
effects, so the edit rewrote a scheduled record while possibly leaving its slot
empty, and invariant 1 ("a `SCHEDULED` reminder has a Deliver alarm in its slot
for its due time") did not hold after that command. That row now emits
`SetAlarm(due, Deliver)`. An alarm set for a past time fires at once, so the
delivery happens then rather than at the next process start, and the status
guard means it still happens only once. Refusing the edit instead was rejected:
it would stop the user fixing the text of a reminder whose due time just passed.

**Tests** (`app/src/test/java/app/ding/state/ReminderTransitionTest.kt`, plain
JVM, fixed clock — 14 tests in the class, all green):

- 8. an edit that leaves the due time alone keeps the state in each of the three
  states, with the per-state effects.
- 9. an edit that turns nagging off empties a notified reminder's alarm slot.
- an edit never leaves a scheduled reminder whose due time has passed without a
  delivery.
- the edit dialog asks for an edit when the due time is untouched and a
  reschedule when it moved.
- saving a notified or done reminder with the time untouched no longer schedules
  it in the past — the reported bug, end to end from the dialog's decision.

Reschedule from every state to a future time and the past-due refusal are
already test 7 from ticket 10, which loops over `Status.entries` and so covers
Reschedule from `DONE`. Not duplicated.

**Red first.** With the new Edit row disabled and `editOrReschedule` forced down
the reschedule branch (the old dialog's behaviour), three of the new tests fail:

```
FAILED: an edit never leaves a scheduled reminder whose due time has passed without a delivery
   expected:<[SetAlarm(reminderId=2, at=1788609540000, kind=DELIVER, expectedDueTime=1788609540000)]> but was:<[]>
FAILED: the edit dialog asks for an edit when the due time is untouched and a reschedule when it moved
   expected:<Edit(reminderId=2, text=Water the ferns, naggingRepeatInterval=10)> but was:<Reschedule(reminderId=2, dueTime=1788613200000, ...)>
FAILED: saving a notified or done reminder with the time untouched no longer schedules it in the past
   NOTIFIED: class TransitionOutcome$Refused cannot be cast to class TransitionOutcome$Updated
```

The third is the reported bug in today's tree: an untouched save of a notified
reminder asks for a reschedule to a past minute. Ticket 10's guard turns that
into `Refused`, which is why the symptom is now a refused save rather than an
unscheduled past-due record — the record is no longer corrupted, but the user
still could not save an edit. Both halves are closed here.

**Change to the model document.** One contradiction found, and fixed in
`docs/reminder-state-machine.md`: the `Edit` on `SCHEDULED` row had no effects
unconditionally, which invariant 1 does not survive for a past-due scheduled
reminder. The row is split on `due > now` / `due ≤ now`, the paragraph under the
table says why, the Commands section now records that the due time decides which
command the dialog issues (it said this was ticket 11's decision), and a
paragraph under the invariants says where invariant 1 is enforced and that a
dialog never checks the time itself.

**Consciously left out.** The `makeToast` "Reminder due in the past" message
still fires when an edit to a `DONE` or `NOTIFIED` reminder succeeds, because
its due time genuinely is in the past. It reads oddly for a done reminder, but
changing the copy is a UI decision and this ticket is not a UI redesign. No
Robolectric or instrumentation test was added for the dialog: the module has no
Android test harness, and adding one is its own ticket.
