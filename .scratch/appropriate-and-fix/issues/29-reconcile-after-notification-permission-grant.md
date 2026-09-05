# 29 — Reconcile when the notification permission is granted

Type: task
Status: open
Blocked by: 27

## Question

Global review finding (reliability axis, high, number 4),
`app/src/main/java/app/ding/ReminderManager.kt` and
`app/src/main/java/app/ding/ui/reminderslist/RemindersListActivity.kt`. See
`../reviews/2026-09-05-global-review-reliability.md`. Ticket 18 decided this
is tested under Robolectric on ticket 27's harness.

When `POST_NOTIFICATIONS` is denied, Deliver still persists the reminder as
`NOTIFIED` and `sendNotification` only logs that nothing was shown. The
reminders list asks for the permission on start, but the startup Reconcile
has already run by then, and nothing reconciles after the grant. A non-nagging
reminder has no future alarm, so it stays invisible until the next process
start.

Every change, nothing more:

1. **The test first**, a Robolectric JUnit 4 class beside ticket 27's: deny
   `POST_NOTIFICATIONS`, deliver a reminder (fire its Deliver intent as ticket
   27 does), assert no notification and status `NOTIFIED`; grant the permission
   and drive the same code path the activity's permission result takes;
   assert a notification under `reminder.id` now exists.
2. **The fix.** In the list activity's permission result, when
   `POST_NOTIFICATIONS` was granted, run `ReminderManager.reconcileAllReminders`.
   Reconcile on a `NOTIFIED` reminder produces a silent re-show, which is the
   behaviour every other re-show has; keep it silent. If you think a delivery
   that was suppressed should alert when it finally appears, say so in the
   ticket rather than changing it here.
3. **CLAUDE.md** does not need a change unless the reconcile triggers gain a
   list; if they do, the two are process start and this grant.

**Done when** the gate is green, and the new test fails with the fix reverted.
