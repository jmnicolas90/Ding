# 13 — Make stored-reminder decoding versioned and recoverable

Type: task
Status: open
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
