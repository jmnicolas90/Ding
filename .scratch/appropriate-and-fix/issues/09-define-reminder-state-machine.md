# 09 — Define the reminder state machine and its single transition boundary

Type: grilling
Status: open
Blocked by: —

## Question

**The heart of this map.** The review's recurring-patterns section names the root cause plainly: reminder state ownership is spread across `Main`, `ReminderManager` and `ReminderStorage`, and three or more paths can reconcile, deliver, edit or persist the same reminder with no single idempotent transition API. Four separate findings are symptoms of that one gap. Fixing them individually would paper over it; the decision has to come first.

Use `/grilling` and `/domain-modeling`. The output is a **written model**, not code — implementation is tickets 10 through 13.

What has to be decided:

**The states and the legal transitions.** Today `Reminder.Status` is `SCHEDULED`, `NOTIFIED`, `DONE`, and `status` is a `var` on a `@Serializable` data class that any caller can reassign. Are three states enough? What transitions are legal, and what must happen to the alarm and the notification at each one?

**Idempotency, and what identifies a delivery.** The cold-start bug: an alarm starting a dead process runs `Main.onCreate()` first, whose `scheduleAndReshowAllReminders()` already finds the due `SCHEDULED` reminder, notifies, marks it `NOTIFIED` and schedules nagging — and *then* the receiver processes the original `Notify` action, which calls `showReminder` unconditionally. Duplicate alert, duplicate write. Guarding on "status is still `SCHEDULED`" is the minimum. The review argues for more: an expected due timestamp or generation token, so a **stale** alarm cannot act on a reminder that has since been rescheduled. Decide which, and what a generation token would cost in the stored format.

**Where the boundary lives.** One API through which every transition passes, or something looser? What do `Main`'s startup reconciliation, `ReminderBroadcastReceiver`, and the edit dialog each get to call?

**What happens to a reminder that is not there.** `ReminderAction.run` opens with `ReminderStorage.getReminder(context, reminderId)`, which throws when the ID is missing — reachable today via the failed-commit path in ticket 12. Is a missing reminder an error, or an expected outcome of a race?

**The nagging chain.** `Nag` reschedules itself from the original due date. Where does that chain terminate, and what stops a nag chain from surviving its reminder?

Constraint from charting: **Room is out of scope.** The model must work over the existing `SharedPreferences` JSON store. Do not let the discussion drift into a store rewrite.

**Done when** there is a written state machine — states, transitions, guards, and the idempotency rule — in `docs/` that tickets 10 through 13 can be implemented against, and that a test can be written from directly.
