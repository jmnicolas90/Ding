# 12 — Treat a failed write as a failure

Type: task
Status: resolved
Blocked by: 09

## Question

**Ticket 09 is resolved.** Implement against `docs/reminder-state-machine.md` (transition table, effects, invariants, and the numbered test list); use the vocabulary in `CONTEXT.md`.

Review finding: **"Failed persistence is reported as success"** (high), `ReminderStorage.kt:68-74`.

`SharedPreferences.Editor.commit()` returns `false` when the durable write fails; the result is discarded. The method broadcasts a successful change and returns the reminder anyway, so callers schedule an alarm for a reminder that is not in storage. On process death the reminder is gone — and the alarm that outlives it later crashes in `ReminderAction.run`, whose first line is `ReminderStorage.getReminder(context, reminderId)`.

That crash path is why this ticket and ticket 09 are linked: "what happens when the reminder is missing" is a state-machine question, and the answer here has to match it.

Fix:
- Check the result of `commit()`.
- Do not broadcast a change that did not happen.
- Propagate a typed persistence failure so callers cannot schedule or report success by accident.
- Decide what the *user* sees. Silently losing a reminder is the worst outcome for an app whose entire value is not forgetting things.

Tests need injectable persistence — a store that can be made to fail on demand. Loquace does this by making a directory unwritable; note its CI needed `setpriv --bounding-set=-dac_override` because root ignores file permissions. Prefer a seam in the code over a filesystem trick if the design allows one.

**Done when** a forced commit failure produces no broadcast, no scheduled alarm, and a typed error the caller must handle.

## Resolution (2026-09-05)

A write that does not commit is now a failure of its own, distinct from
"nothing needed doing", and every caller of the runner has to answer for it.

**The failure type.** `app/src/main/java/app/ding/state/ReminderCommandRunner.kt`
gains `sealed interface CommandResult`, which `TransitionOutcome` now extends,
and `data object PersistenceFailed : CommandResult, ReconcileResult`. So
`ReminderCommandRunner.run` and `.add` return `CommandResult` — the four
transition outcomes plus `PersistenceFailed` — and `.reconcileAll` returns
`ReconcileResult`, either `Reconciled(outcomes)` or the same `PersistenceFailed`.
The runner's two failed-write branches returned `TransitionOutcome.Unchanged`
and an empty outcome list before, which is the one answer a caller is entitled
to ignore. Both files stay free of Android imports.

Because the hierarchy is sealed and flat, callers match on it with a `when` and
no `else`, so the compiler is what forces the handling. Where the difference
between the outcomes does not matter, the two cases are `is TransitionOutcome`
and `PersistenceFailed`.

**The store already reported the truth; nothing else did.** The shared-preferences
adapter in `ReminderStorage.kt` uses `commit()` and returns its result (ticket
10); a comment now says why `apply()` is wrong here — it writes in the
background and returns nothing, so a failed durable write would be invisible.
`apply()` is used nowhere for reminder data: the remaining calls in `Prefs.java`
are the welcome-message flag, the "don't show again" flags and the run-on-boot
setting, none of which is a reminder.

**What each caller does.**

- *Add dialog* (`AddReminderDialogActivity`) — error toast, and the dialog stays
  open with the text and the pickers as they were, so pressing OK again is a
  real retry. `Removed`/`Unchanged` cannot happen for Add and are logged.
- *Edit dialog* (`EditReminderDialogActivity`) — the same toast, the same open
  dialog. `Removed`/`Unchanged` keep the existing "reminder not found" toast and
  close, which is the reminder having been deleted while the dialog was open;
  that case used to be lumped together with a failed write.
- *Reminders list* (`RemindersListFragment`) — mark done and delete run one
  command per selected reminder through a new `runOnSelection`, which tries every
  selected reminder and shows the toast once if any write did not commit.
- *Alarm receiver* (`ReminderManager.ReminderAction.run`) — no UI, so it logs at
  error level. The reminder keeps its previous stored state and its alarm slot is
  untouched, so the next Reconcile picks the work up again.
- *Startup reconciliation* (`ReminderManager.reconcileAllReminders`, called by
  `Main.onCreate`) — logs at error level for the same reason; the next process
  start reconciles again.
- *Quick tile* (`QuickTileService`) — no runner call at all; it only starts the
  add dialog. Nothing to handle.

No crash reporter exists and none was added.

**What the user sees.** One new string,
`error_msg_reminder_not_saved`: "Could not save the reminder. Nothing was
changed." A failed save is now visible instead of silent, and the input is never
thrown away.

**Tests** (`app/src/test/java/app/ding/state/ReminderCommandRunnerTest.kt`,
plain JVM through the injectable fake store — no filesystem trick; its
`writeSucceeds` switch became a `var` so a test can turn the store back on):

- *a commit that fails is a typed failure with no announcement and no effects* —
  test 12 of the doc's list, over MarkDone.
- *an add whose commit fails allocates nothing and is a typed failure* — the
  reported bug: no alarm for a reminder that is not in the store.
- *a reconciliation whose commit fails is a typed failure and delivers nothing*.
- *a command after a failed write works normally* — and in all four, the store's
  content is asserted unchanged after the failure.

**Red first.** With the failure branches still returning `Unchanged` and an empty
sweep, the four new tests fail on exactly the finding:

```
expected:<PersistenceFailed> but was:<Unchanged>
expected:<PersistenceFailed> but was:<Unchanged>
expected:<PersistenceFailed> but was:<Reconciled(outcomes=[])>
expected:<PersistenceFailed> but was:<Unchanged>
```

Returning `PersistenceFailed` from both branches makes the ten tests in the class
green.

**No change to the model document.** `docs/reminder-state-machine.md` already
says the runner returns a typed persistence failure with no broadcast and no
effects (step 4 of "The runner"); the implementation matches it, so there was no
contradiction to fix.

**Consciously left out.** No retry, no queue and no crash reporter: a failed
commit is reported and left alone, because the store keeps its previous content
and the next Reconcile or the user's next press is the retry. The dialogs are not
tested — the module still has no Android test harness, as ticket 11 recorded.
Nothing was moved off the main thread; that is still open in the map.
