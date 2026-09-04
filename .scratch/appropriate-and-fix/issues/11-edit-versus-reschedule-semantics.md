# 11 — Decide and implement edit versus reschedule for every status

Type: task
Status: open
Blocked by: 09

## Question

Review finding: **"Saving an already-notified or done reminder creates an unscheduled past-due reminder"** (medium), `EditReminderDialogActivity.kt:71-91`.

Only `SCHEDULED` reminders restore their original due date. A `NOTIFIED` or `DONE` reminder keeps the dialog's initial current-minute value, and `buildReminderWithTimeTextNagging()` produces a builder whose status defaults to `SCHEDULED`. By the time the user presses OK that minute is normally already past, so `updateReminder(..., true)` cancels the current notification but schedules no replacement. The record sits `SCHEDULED` and past due until some later startup sweep surprises the user with it.

The bug is that "edit" and "reschedule" are the same code path with different intent. Decide the semantics — under ticket 09's state machine — and make them explicit:

- Editing the *text* of a `DONE` reminder should presumably leave it `DONE` and unscheduled.
- Rescheduling should require a future time and deliberately reset the status.
- **A reminder must never be saved `SCHEDULED` with a due date in the past.** That is the invariant worth encoding, wherever it belongs.

Tests for editing a reminder in each of the three statuses without touching the time picker.

**Done when** each status has defined, tested behaviour, and the invariant above is enforced at the boundary rather than in the dialog.
