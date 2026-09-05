# 22 — Stop one preference read or one failed effect from swallowing a delivery

Type: task
Status: resolved
Blocked by: —

## Question

Global review finding (reliability axis, high, number 3), `Prefs.java` and the
effect runner in `ReminderManager.kt`. See
`../reviews/2026-09-05-global-review-reliability.md`.

The notification effect reads boolean display preferences with a raw
`SharedPreferences.getBoolean`, which throws `ClassCastException` when the
stored value is of another type, as a restored backup or a hand-edited file can
make it. By then Deliver has already committed `NOTIFIED`, so the alarm is
consumed and no notification appears; the Nag effect that follows in the same
list is skipped; and every later Reconcile throws at the same read, so the
alarms of every other reminder in the store are never restored either. This
is the exact class of bug `CLAUDE.md` calls the most serious: a reminder that
silently does not fire, and then all of them.

Fix:

- Every preference the delivery path reads goes through a type-tolerant read
  in the pattern of ticket 14's `NagIntervalSetting`: the read is passed in as
  a function, a `ClassCastException` on it is one more unusable value, the
  default is used, the fact logged once, and the stored value repaired. Find
  them all: grep `Prefs.java` for every `getBoolean`, `getInt`, `getString`
  reached from `ReminderManager`, the notification builder, the receiver and
  the boot receiver.
- The effect runner isolates failures: an effect that throws is logged at
  error level and the remaining effects still run, so one broken notification
  cannot stop alarm restoration for the rest of the store. Reconcile over a
  store of several reminders where one effect throws still schedules the
  others.
- Tests, plain JVM: each tolerant read on a wrong-typed value; the runner with
  a fake effect executor that throws on one effect and records the rest.

Not in scope: recovering a delivery whose notification could not be shown
(that is ticket 29's territory — the number this finding was given once ticket 18
had decided how it is tested), and
the settings screens' own reads, which ticket 15 already made tolerant for the
time display size.

**Done when** no read on the delivery path can throw on a wrong-typed
preference, a throwing effect does not stop the effects after it, and the
tests above pass.

## Findings recorded, not fixed here

The Codex review of this ticket (job `review-mtohwk7v-i9g48x`) returned two
findings. Neither is a regression, both are gaps this ticket leaves standing,
and both were sent elsewhere rather than widening it:

- **A failed effect is logged, and the caller is still told the command
  succeeded** (high). An Add whose `SetAlarm` throws stores a `SCHEDULED`
  reminder with an empty alarm slot while the dialog reports success. Closing
  it means putting the failed effects into `CommandResult` and teaching the
  dialogs to say so, which reopens the result-type contract tickets 10, 12 and
  24 settled. Charted as ticket 31.
- **The three `Prefs` accessors have no test, only the pure
  `booleanFromStored` does** (medium). They are Java over
  `PreferenceManager.getDefaultSharedPreferences` and cannot be driven on a
  plain JVM at all — the same gap tickets 14 and 15 have. Added as step 5 of
  ticket 27, which builds the Robolectric harness that can reach them.
