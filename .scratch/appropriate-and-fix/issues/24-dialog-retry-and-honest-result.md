# 24 — Let the reminder dialog retry, and report an honest result

Type: task
Status: open
Blocked by: —

## Question

Global review findings (UI axis, high number 1 and medium number 2),
`OneTimeClickListener.kt` and `ReminderDialogActivity.kt`. See
`../reviews/2026-09-05-global-review-ui-docs.md`.

**The button dies after the first press.** `OneTimeClickListener` sets
`clicked` and never resets it. Tickets 10 and 12 made the add and edit dialogs
stay open after a past-due refusal and after `PersistenceFailed`, on the promise
that a retry is one press. It is not: after correcting the input, the Add or OK
button does nothing, and only the keyboard's action key, which does not go
through the listener, still submits. Map.md and ticket 12 both claim the
opposite.

**Back reports success.** The back callback calls `completeActivity()`, which
always sets `RESULT_OK`, so abandoning the dialog after a failed write, or
without submitting at all, tells the caller an add or edit succeeded.

Fix:

- Replace the one-shot listener with a submission guard that is armed when a
  submission starts and released when `onDone` returns without finishing the
  activity; route the button and the keyboard action through the same guard so
  a double tap still submits once. If the guard is pure logic, test it on the
  JVM; otherwise say so in the resolution.
- `RESULT_OK` only after a transition outcome that changed something; back and
  every other abandonment `finish()` with `RESULT_CANCELED`. Check every caller
  that reads the result (the list, the quick tile, shortcuts) still behaves.
- Verify on the emulator (`bench-pixel6-aosp`, recipe in the ticket loop notes):
  a past-due add, corrected and resubmitted with the button, lands `SCHEDULED`.

Not in scope: saving the dialog draft across rotation (UI finding 3), which is
its own ticket.

**Done when** a refused or failed submission can be corrected and resubmitted
with the button, a second tap while a submission is running does nothing,
and the dialog's result is `RESULT_OK` only when a reminder changed.
