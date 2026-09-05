# 21 — Keep reminder ids unique in the store and never reused after a quarantine

Type: task
Status: open
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
