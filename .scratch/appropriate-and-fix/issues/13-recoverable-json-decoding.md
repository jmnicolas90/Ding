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
`KNOWN_STORED_REMINDERS_FORMAT_VERSION` is 1 and is now the single source of
`Main.REMINDERS_LIST_FORMAT_VERSION`, which stops being a `var`. There is no
migration to write yet; the `when` on the version says where the first one goes.
`Reminder.fromJson` is deleted, so there is no second way in.

**The quarantine rule.** When the store is unreadable, `ReminderStorage` moves the
raw value and its format version to `reminders_unreadable`,
`reminders_unreadable_format_version` and `reminders_unreadable_at` (the time it
happened) and empties `reminders`, in one commit, before anything writes to the
normal keys — so a reminder added afterwards can never land on top of it. Only one
value is kept: `quarantineToKeep` keeps the one already there, because a second
failure is usually a consequence of the first (the empty store the app then ran
on), and a dropped second value is logged. The runner sees an empty store and
carries on; `reconcileAll` returns `Reconciled` and does not throw. A Deliver or
Nag alarm arriving meanwhile finds no reminder and takes ticket 10's
missing-reminder cleanup path — `Unchanged` plus both cancels — instead of
crashing the receiver. Every read the app makes goes through the store now,
`ReminderStorage.getReminders` included, and the id counter and the rollback read
in `write` are read defensively for the same reason.

**What the user sees.** When a value has been set aside, the reminders list
activity shows a dialog, last of the startup dialogs so it is the one on top:
"Stored reminders could not be read", saying they were set aside, that nothing has
been deleted, and that the message comes back until the data is discarded. It is
not cancelable by a stray tap or a back press, and has two actions — **Share raw
data**, an `ACTION_SEND` `text/plain` chooser carrying the raw JSON so the user can
keep it or attach it to an issue, and **Discard**, which deletes it only after a
second confirmation. Seven new strings, all resources. No new screen.

**Tests**, all plain JVM. `app/src/test/java/app/ding/state/StoredReminderDecodingTest.kt`
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

**No change to the model document.** `docs/reminder-state-machine.md` already says
what test 13 asks for — a store that fails to decode does not throw out of
Reconcile and preserves the raw value — and the implementation matches it. Nothing
in the transition table or the invariants contradicted the recovery, so there was
nothing to correct. `ReminderTransition.kt` and the runner are still free of
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
