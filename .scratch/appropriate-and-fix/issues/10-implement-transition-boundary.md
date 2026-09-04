# 10 — Implement the transition boundary and prove the cold-start bug is dead

Type: task
Status: open
Blocked by: 03, 09

## Question

Implement the state machine decided in ticket 09, and route every existing mutation path through it: `Main`'s `scheduleAndReshowAllReminders()`, `ReminderAction.run` (`Notify`, `Nag`, `MarkDone`), and the edit dialog.

Review finding this closes: **"A cold alarm can deliver the same reminder twice"** (high). `ReminderManager.kt:122-127` — `Notify` calls `showReminder` with no check of the stored status, and the notification builder does not set `setOnlyAlertOnce(true)`, so the duplicate produces real duplicate sound and vibration on the ordinary cold-start delivery path.

The regression test is the point of this ticket, not an afterthought. It must execute application reconciliation **first**, then the delivered `Notify` action, and assert exactly one alert and one state transition. That ordering is the bug; a test that does not reproduce it is not a test of this.

`setOnlyAlertOnce(true)` is worth adding regardless — it is defence in depth, not the fix. The fix is the guard.

**Done when** the ordering above is covered by a passing test that fails against the current code, and the gate is green.
