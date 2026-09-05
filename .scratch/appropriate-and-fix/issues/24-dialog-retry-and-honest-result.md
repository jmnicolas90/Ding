# 24 — Let the reminder dialog retry, and report an honest result

Type: task
Status: resolved
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

## Resolution (2026-09-05)

**The button no longer dies.** `OneTimeClickListener` is deleted and
`app/src/main/java/app/ding/util/SubmissionGuard.kt` takes its place. The guard is
armed when a submission starts and released again when that submission returns with
the dialog still open, which is exactly what a past-due refusal and a
`PersistenceFailed` do. So correcting the input and pressing Add or OK again is a
real retry, which is what map.md and ticket 12 had been claiming since ticket 12
landed. The listener it replaced set `clicked` on the first press and never reset it,
so after the first refusal the visible button was dead and only the keyboard's action
key — which never went through the listener — could still submit.

**The button and the keyboard action now share the guard.**
`ReminderDialogActivity` has one `private fun submit()`, the Add/OK click listener and
the `IME_ACTION_DONE` editor action both call it, and `onDone` is documented as
something to call through `submit`, never directly. That keeps the property upstream
added the one-shot listener for in 0.9.14, "prevent reminders being added twice by
accidental double-clicks on Add": the guard stays armed once a submission has closed
the dialog, because the dialog is still on screen for a moment after it is told to
finish and a second press can still reach the button.

**The guard is pure logic and is tested on the JVM.** It holds no Android type; it
takes the "is the dialog closing" question as a function, and the activity passes
`isFinishing`. `app/src/test/java/app/ding/util/SubmissionGuardTest.kt` has five tests:
a submission runs, one that left the dialog open can be run again, one already running
is not started a second time, nothing runs once a submission has closed the dialog, and
one that throws leaves the guard released rather than the dialog dead.

**Red first.** Written with the guard still behaving like the one-shot listener — armed
and never released — two of the five fail on the finding:

```
a submission that left the dialog open can be run again
    expected:<true> but was:<false>
a submission that throws leaves the guard released
    expected:<true> but was:<false>
```

Releasing the guard when the dialog did not close makes all five green.

**The result is honest now.** `completeActivity`, which always set `RESULT_OK`, is
replaced by two methods that say which one they are: `finishAfterChange` sets
`RESULT_OK` and `finishWithoutChange` sets `RESULT_CANCELED`. `RESULT_OK` is reached
from exactly two places, the add dialog and the edit dialog after
`TransitionOutcome.Updated`. Everything else cancels: the back callback, the edit
dialog's `Removed`/`Unchanged` branch — the reminder was deleted while the dialog was
open, so nothing was written — and the edit dialog opening on a reminder that is not in
the store. A refused due time and a failed write set no result at all, because they do
not finish; the dialog stays open with the input in it.

Both ways out still call `finishAndRemoveTask`, not plain `finish`. That is not about
the result code: the two dialogs are `singleTask` with their own `taskAffinity`, so each
is the root of its own task, and leaving the task behind would put a used dialog under
recent tasks. It is also why the back callback exists at all. Only the result code
changed.

**Every result reader was checked, and there was only one, which could never work.**
The quick tile, the launcher shortcut, the settings "show the dialog" preference, the
list's add button and the notification's content intent all `startActivity` and read
nothing. The one reader was `RemindersListFragment.startEditReminderDialogActivityAndReloadOnOK`,
private, called from nowhere and left as a commented-out line at the two places that
open the edit dialog. It is deleted, with the two imports it alone needed. It could not
have worked: a `singleTask` activity with a different task affinity always starts in its
own task, and the system answers a result request from another task with
`RESULT_CANCELED` immediately. A plain comment at both call sites now says that, and
says what really keeps the list current — the reminders-updated broadcast the fragment
already listens for.

### Seen on the emulator

`bench-pixel6-aosp`, API 36, pure AOSP, headless, fresh install of the debug APK.

- **A past-due add, corrected, resubmitted with the button.** The dialog opened at
  11:28 with the clock at 11:29, so its own opening minute was already past. Typed
  "retry test", pressed ADD: the toast "Invalid date: must be in the future" appeared
  and the dialog stayed open with the text and the pickers untouched. Tapped 13 on the
  clock (13:28, today) and pressed the same ADD button again: "Reminder due in 1 hour
  and 58 minutes", the dialog closed, and the list showed "retry test" at 13:28 under
  "Today — Saturday, Sep 5" in green, which is `SCHEDULED`. The store held one reminder,
  id 0, due 1788607680000 = 13:28 today, and `dumpsys alarm` had one `RTC_WAKEUP` for
  `app.ding/.ReminderBroadcastReceiver` with `origWhen 1788607680000`. On the previous
  build the second press did nothing at all.
- **Back leaves the list unchanged.** Opened the add dialog from the list, typed
  "abandoned", pressed back: the dialog went away, the list still held exactly the one
  reminder, and the store was byte-for-byte the same, `nextid` still 2. (The first back
  press closed the soft keyboard, the second closed the dialog.)
- **A double tap still submits once.** With a valid time (tomorrow, via the date "+"
  button), three taps on ADD in a row produced one reminder, id 2, and `nextid` went
  2 → 4, not 2 → 8.
- **The edit dialog is on the same path.** Opened it on the first reminder — it restored
  the stored 13:28 — moved the time to 16:28 and pressed OK twice: one reschedule, due
  1788618480000 = 16:28 today, still two reminders and `nextid` still 4.

The result codes themselves were not observed on the device: nothing in the app reads
them any more, and there is no adb way to see an activity result. They are covered by
inspection and by the fact that `RESULT_OK` is now set in exactly two places.

### Consciously left out

- **Saving the dialog draft across rotation** (UI finding 3) is untouched, as the ticket
  says. The guard is activity state and is lost with the activity on a configuration
  change, which is harmless: a recreated dialog is a fresh one that has not submitted.
- **No test on the device.** The module still has no Android test harness, so the
  wiring — which listener calls `submit`, which branch calls which finish — is covered
  by the emulator run above and by reading the code, not by an automated test.
