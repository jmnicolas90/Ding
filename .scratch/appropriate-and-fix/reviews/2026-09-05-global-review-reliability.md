# Global Codex review, 2026-09-05 — Reminder reliability

Adversarial review by Codex (GPT-5.4) of the whole diff from the fork commit `d34bf2f` to the tip of `main` on 2026-09-05, before the history rewrite of the same day. Job `review-mto3zx01-vob8vq`. One of three reviews run in parallel, each with its own axis; the other two are in this directory.

**Verdict:** needs-attention

**Summary:** Do not ship: the end-to-end Android boundary still contains multiple paths that lose, cross-wire, delay, or permanently suppress reminders.

## Findings

### 1. [high] Quarantine resets IDs while old notifications remain live

File: `app/src/main/java/app/ding/ReminderStorage.kt`

A corrupt store is replaced with an empty store whose next ID is 0, but empty reconciliation cancels none of the old OS notifications or intents. Concrete sequence: a non-nagging NOTIFIED reminder with id 0 remains on screen; startup quarantines the store; the next Add reuses id 0; swiping the old notification sends MarkDone(0), marks the new reminder DONE, and cancels its alarm. The user silently loses the new reminder.

Recommendation: Never reset the durable ID generation while old artifacts may exist. Preserve a valid nextId across quarantine, and block new IDs or clean/tombstone old IDs when the counter cannot be trusted.

### 2. [high] Exact-alarm revocation can leave every reminder unscheduled

File: `app/src/main/java/app/ding/util/AlarmManagerUtil.kt`

On API 31–32, revoking SCHEDULE_EXACT_ALARM stops the process and deletes existing exact alarms. This code only falls back to `set()` when it later gets an opportunity to schedule; no receiver reconciles alarms when access is granted again. The fallback is also not allowed through Doze, so newly scheduled reminders can be substantially late. Android explicitly requires rescheduling after `ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED` in [its alarm guidance](https://developer.android.com/develop/background-work/services/alarms).

Recommendation: Register for the exact-alarm permission-state broadcast and reconcile on grant. When access is absent, provide a Doze-capable independent fallback or make the unscheduled/degraded condition persistent and unmistakable to the user.

### 3. [high] A wrong-typed display preference consumes delivery and crashes reconciliation

File: `app/src/main/java/app/ding/Prefs.java`

The notification effect reads Boolean preferences with raw `SharedPreferences.getBoolean`, which throws `ClassCastException` for a restored or damaged value of another type. Deliver has already committed NOTIFIED before this read; the consumed alarm produces no notification, and a following Nag effect is skipped. Every later Reconcile can crash at the same read and prevent effects for subsequent reminders too.

Recommendation: Make every delivery-path preference read type-tolerant and repair invalid values to defaults. Also isolate effect failures so one notification cannot abort alarm restoration for the rest of the store.

### 4. [high] Granting notification permission does not recover suppressed reminders

File: `app/src/main/java/app/ding/ReminderManager.kt`

When POST_NOTIFICATIONS is denied, delivery is persisted as NOTIFIED and this path merely logs that nothing was shown. If the user then opens the list and grants permission, Application reconciliation has already run before the permission request and no grant callback reconciles again. A non-nagging reminder has no future alarm, so it remains invisible until a later process restart.

Recommendation: Detect notification permission or channel restoration and immediately run Reconcile. Add an Android-level test covering denied delivery followed by a grant in the same process.

### 5. [high] Duplicate reminder IDs decode as valid and share one alarm slot

File: `app/src/main/java/app/ding/state/StoredReminderDecoding.kt`

Decoding validates each Reminder separately but never checks ID uniqueness. With two otherwise valid records sharing an even ID, future reconciliation schedules both into one PendingIntent slot, so the last alarm replaces the first and one reminder never fires. If both are delivered during reconciliation, the runner's ID map also replaces both stored entries with the last updated reminder.

Recommendation: Validate uniqueness across the decoded list and classify duplicates as an unreadable store so the existing quarantine path preserves the raw data instead of silently cross-wiring it.

### 6. [medium] Clock rollback schedules nags before the original due time

File: `app/src/main/java/app/ding/state/ReminderTransition.kt`

For a previously delivered reminder, setting the clock backward can make `now < dueTime`. `floorMod` then selects a negative multiple of the interval: for due 10:00, now 09:00, and a ten-minute interval, Reconcile schedules a nag at 09:10. The reminder can nag repeatedly before its stored due time on the corrected clock.

Recommendation: When now precedes the original due time, schedule no earlier than dueTime plus one interval. Add rollback tests with now before due, including exact interval boundaries.

### 7. [medium] Reconcile cannot repair partially completed DONE transitions

File: `app/src/main/java/app/ding/state/ReminderTransition.kt`

A MarkDone write is persisted before its cancel effects. If the process dies or cancellation fails in that gap, the next Reconcile sees DONE and emits no cleanup, leaving the old notification or alarm behind. This directly contradicts invariant 3, which says a DONE reminder has an empty slot and no notification after Reconcile; the existing mixed-store test pins the contradictory behavior.

Recommendation: Make Reconcile for DONE emit CancelAlarm and CancelNotification, align the transition table with invariant 3, and test restart after persistence but before effects.

## Briefing given to the reviewer

```
Global adversarial review of everything Ding changed since it forked from felixwiemuth/SimpleReminder at d34bf2f (2025-10-20). Axis 1 of 3: reminder reliability. The app's whole value is firing a notification at the due time in the background, so anything that can make a reminder silently not fire, fire twice, fire at the wrong time, or lose the stored reminders is the most serious class of defect. Tickets 09 to 15 in .scratch/appropriate-and-fix/issues/ were each reviewed in isolation; this review is about how they behave together, end to end, on a real device.

Spec: docs/reminder-state-machine.md (states, commands, transition table, invariants, runner steps, numbered tests), docs/adr/0001-pure-transition-function.md, CONTEXT.md, and the domain vocabulary section of CLAUDE.md. Decisions log: .scratch/appropriate-and-fix/map.md.

Scope: app/src/main/java/app/ding/state/*, ReminderManager.kt, ReminderStorage.kt, ReminderBroadcastReceiver.kt, BootReceiver.kt, Main.kt, Prefs.java, QuickTileService.kt, data/Reminder.kt, data/NagIntervalSetting.kt, util/AlarmManagerUtil.kt, util/DateSerializer.kt, AndroidManifest.xml, and every test under app/src/test.

Decisions fixed (do not re-litigate): three states; one pure transition function with no Android imports; a runner that locks, reads, transitions, persists with commit() and rollback, then runs effects; every alarm carries its due time as the stale token; a missing reminder is cleanup, never an exception; PersistenceFailed is its own result and nothing happens on that path; an unreadable store is set aside under separate keys, never deleted; even ids allocated from PREF_STATE_NEXTID, id+1 for the mark-done action; nag interval bounded 1..1440 minutes; no Room, no crash reporter, no async runner yet (moving storage off the main thread is a known open item, only report it if you find a concrete way it drops a reminder).

Look hard for, tracing the actual code on Android 12 through 15:
- Any path that reads or writes the reminder list or the id counter outside the runner and its lock, in Kotlin or Java, including Prefs.java, the list fragment, the quick tile, the boot receiver and the welcome/changelog actions.
- Exact alarms on API 31+: which permission the manifest declares (SCHEDULE_EXACT_ALARM versus USE_EXACT_ALARM), what happens when canScheduleExactAlarms() is false or revoked while reminders exist, which AlarmManager set method is used and whether it survives doze and app standby buckets, and what the boot receiver actually does after a reboot, a package update, and a time or timezone change.
- The notification path: setOnlyAlertOnce combined with notify(reminder.id, ...) for nags, whether a nag re-post still makes a sound and vibration when the previous notification is still on screen, and the POST_NOTIFICATIONS runtime permission on API 33+ when it is denied.
- PendingIntent flags (FLAG_IMMUTABLE and FLAG_UPDATE_CURRENT versus CANCEL_CURRENT) and whether Deliver, Nag and MarkDone request codes can cross-wire, especially after the id counter is recomputed from stored reminders following a quarantine, or after Discard.
- Interactions between tickets: an alarm firing while the store is being set aside; Reconcile on a store whose format version is fine but one reminder is invalid; Edit versus Reschedule of a NOTIFIED reminder with nagging turned off or on; a Reconcile that finds a SCHEDULED reminder past due while its alarm is also pending; a due time equal to now; clock set backwards.
- The stale-alarm rule when the due time is edited to the same value, and the transition table rows whose effects contradict an invariant.
- Tests that pass for the wrong reason: a fake store that behaves differently from SharedPreferences, a fixed clock that hides a boundary, a test that asserts on the fake rather than the outcome.
- Anything the model document promises that the code does not do, and anything the code does that the document forbids.

Non-goals: the build, the gate, the rename and attribution (review axis 2); dialog and settings UI, doc prose and the review ledger (axis 3); reminder feature work; Room migration; style.

Report each finding with file and line, the concrete sequence of events that triggers it, and its severity for a user whose reminder must fire.
```
