# 12 — Treat a failed write as a failure

Type: task
Status: open
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
