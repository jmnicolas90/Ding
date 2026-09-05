# 32 — The exact-alarm broadcast arrives on the grant only

Type: task
Status: resolved
Blocked by: —

## Question

Review finding against ticket 30 (medium, `ExactAlarmPermissionReceiver.kt`).
Ticket 30 shipped the receiver believing the broadcast says the state changed
without saying which way, and wrote that belief into the code comments, four
documents and one test. Android's own documentation of
`ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED` says the opposite, in
one sentence:

> When the user revokes the `SCHEDULE_EXACT_ALARM` permission, all alarms
> scheduled with `setExact`, `setExactAndAllowWhileIdle` and `setAlarmClock`
> will be deleted. When the user grants the `SCHEDULE_EXACT_ALARM`, this
> broadcast will be sent. Applications can reschedule all the necessary alarms
> when receiving it. **This broadcast will not be sent when the user revokes
> the permission.** Note: Applications are still required to check
> `canScheduleExactAlarms()` before using the above APIs after receiving this
> broadcast, because it's possible that the permission is already revoked again
> by the time applications receive this broadcast.

So the receiver's behaviour is right and its explanation is wrong: on a real
device the "reconcile on the revocation too, and take the inexact fallback"
path can never run, because nothing wakes the app to run it. The revocation is
silent, and the alarms are already gone; the app's first opportunity is the
next process start, whose Reconcile is what puts inexact alarms back.

The one thing the platform *does* ask for on this broadcast — do not assume the
access is still there — the code already satisfies, because
`AlarmManagerUtil.scheduleExact` checks `canScheduleExactAlarms` at the moment
it sets each alarm.

Every change, nothing more:

1. **The KDoc on `ExactAlarmPermissionReceiver`** and the comment above its
   manifest entry: the broadcast is the grant, and only the grant; the receiver
   still does not ask which way the state went, but the reason is the revoked-
   again race the platform names, not a revocation that would arrive here.
2. **The second test case in `ExactAlarmPermissionGrantTest`** — "the
   revocation itself leaves an inexact alarm rather than none" — asserts a
   sequence a device cannot produce. It becomes the case the platform
   documents: the broadcast arrives after the access has been taken back again,
   and the reconciliation leaves an inexact alarm rather than none or a
   `SecurityException`. The body barely moves; what it claims to be about does.
3. **The four places that carry the Reconcile trigger list** —
   `docs/reminder-state-machine.md` command table and Reconcile section,
   `CONTEXT.md`, the KDoc on `ReminderManager.reconcileAllReminders` — say
   "exact-alarm access granted", not "changed". `CLAUDE.md`'s note on the
   `@Config(sdk = [31, 32])` pin and the map's ticket 30 entry are corrected
   the same way.
4. **The gap that is now visible** goes to the map's *Not yet specified*
   beside the existing Doze item: between a revocation and the next process
   start, a `SCHEDULED` reminder holds nothing at all, and the app is not told.
   Making it visible to the user is the persistent-warning product decision
   ticket 30 already left open.

Not in this ticket: any behaviour change. The receiver keeps doing exactly
what it does.

**Done when** the gate is green, no document or comment claims the app hears
about a revocation, and the test suite claims only what a device can produce.
