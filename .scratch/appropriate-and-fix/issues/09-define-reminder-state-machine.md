# 09 — Define the reminder state machine and its single transition boundary

Type: grilling
Status: resolved
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

## Resolution (2026-09-04)

Written model: `docs/reminder-state-machine.md`. Glossary: `CONTEXT.md`. Architecture record: `docs/adr/0001-pure-transition-function.md`.

- **States:** the three stay, tightened. `SCHEDULED` = not delivered, alarm pending. `NOTIFIED` = delivered, not dealt with. `DONE` = finished, resting not terminal. "Overdue" is not a state.
- **Transitions:** Add, Deliver, Nag, MarkDone, Reschedule (legal from any state, lands `SCHEDULED`), Edit (never changes state), Delete, Reconcile. Full table with guards and effects in the doc.
- **Idempotency:** status guard plus the due time carried in every Deliver and Nag alarm and compared to the store on arrival. A mismatch is stale and ignored. No stored-format change; a generation counter was rejected.
- **Boundary:** one pure `transition(stored, command, now) -> outcome × effects` with no Android imports, wrapped by one runner: lock, read, transition, write with checked commit, then effects. Persist first; a failed write runs no effects and returns a typed failure. `Reminder.status` becomes a `val`; storage mutation goes private.
- **Missing reminder:** an expected outcome, not an error. Every command on an absent reminder is `Unchanged` plus cancel-alarm and cancel-notification. The receiver never throws on it.
- **Nag chain:** ends by guard. Nag is legal only in `NOTIFIED` with a matching due time; MarkDone, Reschedule and Delete empty the alarm slot. No time or count bound in this map.
- **Past-due invariant:** Reschedule and Add to a time not after now are refused with `PastDue`. Reconcile delivers a past-due `SCHEDULED` reminder on sight, with a full alert.
- **Recurrence:** deferred to the feature map, but the doc records the extension point. Leaning: the same reminder cycles, MarkDone on a reminder with a rule acts as Reschedule to the next occurrence after now.

Fourteen tests are listed in the doc, the first being ticket 10's cold-start regression.
