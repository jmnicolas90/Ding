# 13 — Make stored-reminder decoding versioned and recoverable

Type: task
Status: resolved
Blocked by: 09

## Question

**Ticket 09 is resolved.** Implement against `docs/reminder-state-machine.md` (transition table, effects, invariants, and the numbered test list); use the vocabulary in `CONTEXT.md`.

Review finding: **"Invalid stored JSON can put the app in a startup crash loop"** (medium), `ReminderStorage.kt:86-88`.

Reminder JSON is decoded with no handling for `SerializationException`, an incompatible schema, a wrong preference type, or a null value. `Main.onCreate()` reads it on **every process start**, so a single bad value stops every component of the app from starting — including the alarm receiver. The app becomes unlaunchable and unfixable from inside.

A format-version key already exists (`Main.REMINDERS_LIST_FORMAT_VERSION = 1`) but is not consulted here, so there is a version field with no migration or recovery attached to it.

Fix:
- Centralise decoding in one place that reads the version and selects migration or recovery.
- Catch the expected serialization and persistence failures rather than letting them escape into `onCreate`.
- **Preserve the raw value** — never silently delete a user's reminders because they failed to parse.
- Offer a controlled repair or export path instead of a crash loop.

Note `Reminder`'s `init` block also throws: `require(id in 0..MAX_REMINDER_ID && id % 2 == 0)`. A stored odd or out-of-range ID therefore fails construction, not just deserialization — the recovery path has to cover that too.

**Done when** a corrupt, truncated, wrong-typed and future-versioned store each start the app without crashing, and the raw value survives for recovery.

## Resolution (2026-09-05)

Reading the stored reminders no longer throws, so a single bad value can no longer
stop every component of the app from starting. What used to be an exception out of
`Main.onCreate` is now an answer, and the value that caused it is kept.

**The decoding, in one place.**
`app/src/main/java/app/ding/state/StoredReminderDecoding.kt` holds
`decodeStoredReminders`, a pure function with no Android imports next to the
transition function and the runner, so every stored value the app can meet is
decided in a plain JVM test. It reads the format version first and answers with a
sealed `DecodeResult`:

- `Readable(reminders)` — this build's format, decoded.
- `Empty` — nothing stored yet. A first run, not damage.
- `Unreadable(reason, raw)` — with `raw` the value exactly as it was read.
  `UnreadableReason` distinguishes `NEWER_FORMAT_VERSION` (a version this build
  does not understand), `MALFORMED_JSON` (not JSON at all, or truncated),
  `SCHEMA_MISMATCH` (JSON, but not a list of reminders of this shape),
  `INVALID_REMINDER` (`Reminder`'s own `require` on an odd or out-of-range id) and
  `WRONG_TYPE` (the preference holds a value of another type).

Parsing and construction are two steps on purpose — `parseToJsonElement`, then
decode the element — because that is what separates "not JSON" from "JSON that is
not a reminder", and `SerializationException` is caught before
`IllegalArgumentException` because it is itself one. Reading is done through two
functions rather than two values, since the read itself throws
`ClassCastException` when shared preferences hold another type; both forms are
public, and the value form is the one a test calls.
`KNOWN_STORED_REMINDERS_FORMAT_VERSION` is 1 and is the only version constant left:
`Main.REMINDERS_LIST_FORMAT_VERSION` and `Prefs.getStoredRemindersListFormatVersion`
are both deleted, since a typed read of the version outside the decoding is a
`ClassCastException` out of `Application.onCreate` (review finding 1). The version is
written with the reminders, in the same commit, so nothing writes it on a read either.
There is no migration to write yet; the `when` on the version says where the first one
goes. `Reminder.fromJson` is deleted, so there is no second way in.

**The quarantine rule.** When the store is unreadable, the raw value and its format
version are moved to `reminders_unreadable`,
`reminders_unreadable_format_version` and `reminders_unreadable_at` (the time it
happened), `reminders` is emptied and the id counter is reset, in one commit, before
anything writes to the normal keys — so a reminder added afterwards can never land on
top of it. That commit belongs to the runner, not to a read: reading reports an
unreadable store and changes nothing, and the set-aside is the first step of the next
command, under the runner's lock (review finding 3). At process start that command is
always Reconcile. It goes through `writeWithRollback` like any other write, so a
set-aside whose commit fails leaves the store exactly as unreadable as it was and the
command it was the first step of returns `PersistenceFailed`; the next command tries
the set-aside again (review finding 4). Only one
value is kept: `quarantineToKeep` keeps the one already there, because a second
failure is usually a consequence of the first (the empty store the app then ran
on), and a dropped second value is logged. The runner then sees an empty store and
carries on; `reconcileAll` returns `Reconciled` and does not throw. A Deliver or
Nag alarm arriving meanwhile finds no reminder and takes ticket 10's
missing-reminder cleanup path — `Unchanged` plus both cancels — instead of
crashing the receiver. Every read the app makes goes through the store now,
`ReminderStorage.getReminders` included. Every preference the store reads is read
through a function that is allowed to throw `ClassCastException`, and `readStore` in
`app.ding.state` decides what each failure means, the id counter included: a counter
that cannot allocate an id no stored reminder has is recomputed from the reminders
rather than substituted with 0 (review finding 5). The value set aside is read the
same way (review finding 2).

**What the user sees.** When a value has been set aside, the reminders list
activity shows a dialog, last of the startup dialogs so it is the one on top:
"Stored reminders could not be read", saying they were set aside, that nothing has
been deleted, and that the message comes back until the data is discarded. It is
not cancelable by a stray tap or a back press, and has two actions — **Share raw
data**, an `ACTION_SEND` `text/plain` chooser carrying the raw JSON so the user can
keep it or attach it to an issue, and **Discard**, which deletes it only after a
second confirmation. Seven new strings, all resources. No new screen.

**Tests**, all plain JVM. `app/src/test/java/app/ding/state/StoreReadingTest.kt`
(16 tests) covers the adapter's own reading: every preference of the wrong type, the
id counter's four corrupt forms, and the value set aside with each of its three keys
unreadable. `app/src/test/java/app/ding/state/StoredReminderDecodingTest.kt`
(19 tests): a valid store, an empty list, nothing stored, a value that is not JSON,
a truncated value, an empty string, JSON that is not a list of reminders, a missing
field, a field of the wrong JSON type, an odd id, a negative id, an id past
`MAX_REMINDER_ID`, a newer format version, a version this build never wrote, a
missing version, a raw value of the wrong type, a format version of the wrong type,
and both halves of the keep-the-first rule. Four tests added to
`ReminderCommandRunnerTest` over a fake store that decodes and quarantines the way
the adapter does, sharing the two pure decisions with it: test 13 of the doc's list
(Reconcile over an unreadable store throws nothing, does nothing and preserves the
raw value), an alarm that arrives while the store is unreadable taking the cleanup
path, a reminder added afterwards leaving the set-aside value alone, and the
quarantine keeping the first value when a second unreadable store appears.

**Red first.** The decoding was written first as today's behaviour — a straight
`Json.decodeFromString` with no version check and nothing caught — and 13 of the 19
tests failed on exactly the finding, each one an exception escaping where
`Main.onCreate` would have taken it:

```
FAILED: a value that is not JSON at all is unreadable
    kotlinx.serialization.json.internal.JsonDecodingException: Unexpected JSON token at offset 0: Expected start of the array '['
FAILED: a truncated value is unreadable
    kotlinx.serialization.json.internal.JsonDecodingException: Unexpected JSON token at offset 43: Expected quotation mark
FAILED: a reminder missing a field the schema requires is a schema mismatch
    kotlinx.serialization.MissingFieldException: Field 'date' is required for type with serial name 'app.ding.data.Reminder'
FAILED: an odd id is an invalid reminder, not a crash
    java.lang.IllegalArgumentException: Id must be even, >= 0 and <= 1000000.
FAILED: a stored value of the wrong type is unreadable
    java.lang.ClassCastException: Integer cannot be cast to String
FAILED: nothing stored yet is empty rather than unreadable
    java.lang.NullPointerException
FAILED: a format version from a newer build is not decoded at all
    expected:<Unreadable(reason=NEWER_FORMAT_VERSION, ...)> but was:<Readable(...)>
```

The version was read by nothing at all, which is the second half of the finding:
the key existed with no migration and no recovery attached to it.

**One change to the model document.** `docs/reminder-state-machine.md` already says
what test 13 asks for — a store that fails to decode does not throw out of
Reconcile and preserves the raw value — and the implementation matches it. Nothing
in the transition table or the invariants contradicted the recovery. The runner's
numbered steps gained the set-aside as step 2, since the recovery is now a step of
the command rather than something a read does on its own.
`ReminderTransition.kt`, the runner and the decoding are still free of
Android imports.

**Consciously left out.** No migration: version 1 is the only version there has ever
been, so writing a migration now would be writing it for an imaginary format. No
import path — the dialog can only share or discard the raw value, not read it back,
because deciding what a partial repair means is a product decision and not this
ticket. No automatic repair of a partially valid list either: one bad reminder makes
the whole value unreadable, which is deliberate, since silently keeping the reminders
that happened to parse is how a reminder disappears without anyone noticing. The
dialog is untested — the module still has no Android test harness, as tickets 11 and
12 recorded — so everything it decides is pushed into the pure functions it calls.
The reason a value could not be read is logged but not stored, so the shared text is
the raw JSON alone. `Reconciled(emptyList())` is what Reconcile returns over an
unreadable store rather than a list of `Unchanged` outcomes: there are no reminders
to have an outcome for, and the alarm path's `Unchanged` is asserted in its own test.

## Review findings (2026-09-05)

Five findings from the review of the work above, all accepted and all fixed in the
same worktree. The theme: every read of a preference is defensive, every mutation of
reminder data goes through the runner's lock and the rollback, and no corruption is
papered over with a default that loses data.

1. (high) **A typed read of the format version in `Main.onCreate`** — fixed. The read
   and `Prefs.getStoredRemindersListFormatVersion` are both deleted: the decoding owns
   the version, and a wrong-typed version key is now an unreadable store instead of a
   `ClassCastException` out of `Application.onCreate`. The version is written with the
   reminders in the same commit, so nothing has to write it on a read to keep the store
   self-describing. Test: *a format version of the wrong type is an unreadable store,
   not an exception*.
2. (high) **`getQuarantinedReminders` read its three keys unchecked** — fixed. The
   reading is `readQuarantine` in `app.ding.state`, taking one function per key so it is
   decided on a plain JVM: each is caught, the raw text is what matters, and a version or
   a time that cannot be read is unknown rather than fatal. `quarantinedAt` is a `Long?`
   for that reason, and a value set aside is recognised by either of its keys, so one
   whose own text cannot be read is still offered to the user. Tests: *a raw value of the
   wrong type leaves the rest of the value set aside readable*, *a format version of the
   wrong type is unknown rather than a crash*, *a time of the wrong type is unknown
   rather than a crash*, *a value set aside with no metadata at all is still offered to
   the user*, *nothing set aside is nothing to offer the user*.
3. (high) **The set-aside was a write done by a read**, so a public `getReminders` could
   empty `reminders` on top of an Add the runner had just committed — fixed. `read`
   returns the empty view and the reason it could not read anything, and writes nothing;
   `ReminderStore.setAsideUnreadable` is the write, and the runner alone calls it, under
   its lock, as the first step of every command. There is one lock, the runner's: the
   adapter has none of its own and now has nothing to take one for. Test: *a read reports a
   store it cannot read, and the add after it sets that value aside first* — the set-aside
   and the write land in that order, and the added reminder survives.
4. (medium) **A failed quarantine commit was only logged** — fixed. The set-aside
   snapshots all six keys it touches and goes through `writeWithRollback`, and a commit
   that does not go through returns `PersistenceFailed` from the command it was the first
   step of. Reconcile then runs no effects, and the next command retries the set-aside
   before doing anything else. Test: *a set-aside that does not commit is a typed failure,
   and the next command tries it again*.
5. (high) **A wrong-typed id counter was substituted with 0** — fixed. It is not
   substituted at all: `nextIdToUse` accepts a counter only when it is even, within range
   and past every stored id, and otherwise recomputes it as the largest stored id plus two
   (0 for an empty list), logs the repair and lets the next successful write persist it.
   An unreadable store takes its counter with it, reset alongside the reminders. Tests: *an
   id counter that cannot be read gives an id no stored reminder has* (wrong-typed, odd,
   out of range and larger than the maximum), *an id counter of the wrong type is
   recomputed from the stored reminders*, *a counter a stored reminder has already reached
   is recomputed past it*, *a counter that can still allocate is left alone*, *an id
   counter that cannot be read starts again from 0 when there are no reminders*, and
   through the runner *an id counter that cannot be read does not let the next add replace
   a stored reminder*.

**Red first here too.** *a read of a store that cannot be read sets nothing aside on its
own* failed on exactly finding 3 before any of this was written:

```
FAILED: a read of a store that cannot be read sets nothing aside on its own
    org.opentest4j.AssertionFailedError: Expected null but actual was
    QuarantinedReminders(raw=this is not JSON, formatVersion=1, quarantinedAt=1788609600001)
```
