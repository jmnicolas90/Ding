# 30 — Reconcile when exact-alarm access is granted again

Type: task
Status: open
Blocked by: 27

## Question

Global review finding (reliability axis, high, number 2),
`app/src/main/java/app/ding/util/AlarmManagerUtil.kt`. See
`../reviews/2026-09-05-global-review-reliability.md`. Ticket 18 decided this
is tested under Robolectric on ticket 27's harness; no API 31 or 32 emulator
image is installed here and the map rules a device test for it out of scope.

On API 31 and 32 the user can revoke `SCHEDULE_EXACT_ALARM`. Android then
stops the process and deletes the app's exact alarms, and documents that the
app must reschedule when it receives
`ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED`. Ding registers
nothing for it, so every `SCHEDULED` reminder is silently unscheduled until
the next process start. From API 33 `USE_EXACT_ALARM` makes the grant
permanent, so this is a 31–32 path, but those are supported versions.

Every change, nothing more:

1. **The test first**, a Robolectric JUnit 4 class beside ticket 27's, pinned
   to SDK 31 or 32: a stored `SCHEDULED` reminder, the alarm shadow emptied to
   model the revocation, then the permission-state broadcast delivered to the
   new receiver with exact alarms allowed again; assert the reminder's alarm is
   back in its slot for its due time with request code `reminder.id`.
2. **The fix.** A manifest-declared receiver for
   `ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED` that runs
   `ReminderManager.reconcileAllReminders`. Reconcile already re-sets every
   future reminder's alarm; when access is granted the runner's existing
   `scheduleExact` path takes the exact branch.
3. **Not in this ticket**: the review's further recommendation of a
   Doze-capable fallback or a persistent warning while access is absent. That
   is a product decision about what the app says to the user, and goes to the
   map's *Not yet specified* if it is wanted.

**Done when** the gate is green, and the new test fails with the receiver
removed from the manifest.
