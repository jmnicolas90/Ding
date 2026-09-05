# 21 — Keep reminder ids unique in the store and never reused after a quarantine

Type: task
Status: resolved
Blocked by: —

## Question

Global review findings (reliability axis, high, numbers 1 and 5). See
`../reviews/2026-09-05-global-review-reliability.md`.

**The id counter restarts after a quarantine.** When ticket 13's set-aside
replaces an unreadable store with an empty one, the next id starts again from
0 (or from whatever a corrupt counter is recomputed to over an empty list),
while the notifications and alarms of the reminders that were in the old store
are still live in the OS. Sequence: a notified reminder with id 0 is on screen;
startup quarantines the store; the next Add allocates id 0 again; the user
swipes the old notification, its delete intent sends `MarkDone(0)`, and the new
reminder is marked done and its alarm cancelled. A reminder silently never
fires. An id is an identity across three Android subsystems at once
(`CLAUDE.md`), so an id must never be reused within an install.

**Duplicate ids decode as valid.** `decodeStoredReminders` validates each
reminder alone and never checks that ids are unique across the list. Two
reminders sharing an even id share one alarm slot and one notification id, so
the last one scheduled replaces the first, and the runner's id-keyed map
collapses them on the next write.

Fix:

- The counter `PREF_STATE_NEXTID` is durable on its own and a quarantine leaves
  it alone; the set-aside moves the reminder list, not the counter. If the
  counter itself is unreadable, rebuild it as 2 plus the largest even id that a
  best-effort scan of the quarantined raw text finds (`"id":<n>` occurrences),
  and never lower than the counter's previous value if that was readable. Write
  it in the same commit as the set-aside, through `writeWithRollback`.
- On every quarantine, cancel all of the app's notifications
  (`NotificationManager.cancelAll()`), because the reminders behind them are no
  longer in the store; alarms that fire for them take ticket 10's
  missing-reminder cleanup path.
- Decoding answers `Unreadable(DUPLICATE_ID, raw)` when two stored reminders
  share an id, so the existing quarantine path preserves the raw data instead
  of cross-wiring it.
- Tests, plain JVM: a quarantine followed by an Add allocates an id above every
  id the old store held, with a readable counter and with a corrupt one; a
  duplicate id is unreadable with its reason; a decoded list has unique ids.

**Done when** no path can hand out an id that an earlier reminder of this
install held, a store with duplicate ids is quarantined, and the tests above
pass.

## Resolution (2026-09-05)

An id is never handed out twice within an install, and a store that already holds a
duplicate one is set aside instead of run on.

**The counter survives a quarantine.** `PREF_STATE_NEXTID` is durable on its own now:
the set-aside moves the reminder list to its own keys and leaves the counter where it
was, written in the same rollback-protected commit. The Javadoc on the key says so.
What made this a reliability bug rather than an accounting one is that the reminders
leave the *store* but not the *OS*: their notifications are still on screen, their
alarms and pending intents still in `AlarmManager`, all keyed by the ids the counter
handed out. Starting again from 0 gave one of those ids to the next new reminder, and
swiping the old notification away then sent a mark-done for the new one, which was
cancelled and never fired.

**A counter that cannot be used is rebuilt, not reset.**
`nextIdAfterQuarantine(storedNextId, quarantinedRaw)` in `StoredReminderDecoding.kt`,
pure and android-free like the rest of that file. It answers the largest of the stored
counter and 2 plus the largest even id in range that an `"id":<number>` occurrence in
the raw text names, rounded up to an even number. The scan is best effort by
construction — the text did not parse, which is why it is being set aside — and it errs
upwards on purpose: a match that is not really an id (one inside a reminder's own text,
say) only skips an id that was never used, while missing a real one hands it out twice.
An id outside `0..MAX_REMINDER_ID` is not counted, because the app could never have
allocated it. The one case with nothing to go on — a counter of another type together
with a value that could not even be read as text — starts again from 0. Both the read
(`readStore`, for the empty view the app runs on until the set-aside commits) and the
set-aside itself go through that one function, so they cannot disagree.

**A quarantine cancels every notification of the app's**, in
`ReminderStorage.setAsideUnreadable`, once the commit has gone through and not before.
The reminders behind those notifications are no longer in the store, so nothing would
ever take them off the screen; an alarm that fires for one of them afterwards takes
ticket 10's missing-reminder cleanup path. Only reminder notifications are ever posted,
so `cancelAll` is exactly the set that has gone stale.

**Duplicate ids are an unreadable store.** `decodeStoredReminders` answers
`Unreadable(DUPLICATE_ID, raw)` when two decoded reminders share an id. Uniqueness is a
property of the list, so `Reminder`'s own `require` cannot check it. The store is set
aside whole rather than half-repaired by picking a winner: each reminder is valid on its
own, and there is no honest way to choose which of the two the user meant to keep, while
the raw JSON the quarantine dialog offers still holds both.

**Tests**, all plain JVM. `StoredReminderDecodingTest`: a duplicate id anywhere in the
list is `Unreadable(DUPLICATE_ID, raw)`, and a list whose ids all differ decodes with
its ids still distinct. `StoreReadingTest`: an unreadable store keeps a usable counter
and rebuilds an unusable one from the raw value, plus nine cases on
`nextIdAfterQuarantine` — a usable counter, a counter that cannot be read, a counter
behind the ids in the raw value, an odd one, a negative one, an id out of range, spacing
in the JSON, a raw value with no ids, nothing to go on at all — and a property check
that whatever it is given, the answer is even and not negative. `ReminderCommandRunnerTest`:
an Add after a quarantine takes an id above every id the old store held, with a readable
counter and with a corrupt one; a second quarantine does not give back the ids the first
one moved past; and a store holding a future `SCHEDULED` reminder and a `DONE` one that
share an id is set aside before any effect runs, so `reconcileAll` never emits a
`SetAlarm(2)` that a `CancelAlarm(2)` then undoes — which is what ticket 23's review
asked for, since Reconcile over `DONE` now emits both cancels. Two existing tests that
pinned the counter reset were rewritten to the new answer, and the quarantining fake
store carries the counter over the same way the shared-preferences adapter does.

Left out: `NotificationManagerCompat.cancelAll()` has no JVM seam in this build — there
is no Robolectric — so that one line is Android code covered by inspection rather than
by a test, the same way ticket 14's preference write-back is. The scan is a regular
expression over the raw text rather than a lenient JSON parse; a lenient parse would be
more precise and is not worth a dependency for a value that is being thrown out of the
store anyway. Nothing was done about an install that actually exhausts the id space:
`nextIdAfterQuarantine` can answer `MAX_REMINDER_ID + 2` for a store that held the
largest allowed id, exactly as `nextIdToUse` already could, and the next Add would fail
`Reminder`'s own `require`. That takes 500,001 reminders in one install to reach and is
not this ticket's problem.
