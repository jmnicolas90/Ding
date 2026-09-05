# Ding — agent context

A single-module Android reminder app (Kotlin, some legacy Java). You set a
reminder, it fires a notification at the due time, it can nag until you deal
with it. Its whole value is firing reliably in the background, so anything
that can make a reminder silently not fire is the most serious class of bug
in this repo.

**Hard fork** of `felixwiemuth/SimpleReminder` at commit `d34bf2f`
(2025-10-20), GPL-3.0-or-later. Upstream is never merged again — anything
worth having gets cherry-picked by hand. That is what buys the freedom to
raise `minSdk` and restructure at will. Keep the GPL attribution
(`LICENSE.md`, `CONTRIBUTORS.md`); it is a legal obligation, not a leftover.

**The rename is done** (ticket 05). `applicationId` and `namespace` are both
`app.ding`, the source package is `app.ding.*`, and the app name the user sees
is "Ding". There is one build identity for every build type: upstream's
release-channel scheme is gone, including the digit it took out of the version
code. What still says SimpleReminder is deliberate and stays: the GPL
copyright headers, `CONTRIBUTORS.md`, the changelog entries that record
upstream's release history, and the historical record under `.scratch/`.

## Hard constraints — do not break these

- **GrapheneOS / AOSP compatible, always.** No Google Play Services, GMS or
  Firebase dependency may ever enter the graph. The app is free of them today
  and has run on GrapheneOS for years; this is a property to keep, not to
  build. It is enforced, not just documented: gate G4 below runs
  `checkNoGoogleDependencies` in `app/build.gradle`, which walks the full
  runtime classpath of every variant — transitive dependencies included — and
  fails on the groups `com.google.android.gms` and `com.google.firebase`, or
  on any module whose name contains `play-services`. The match is on
  coordinates, never on the string "google", because
  `com.google.android.material` is deliberately allowed: it is AndroidX
  Material Components, open source, runs on AOSP with no Google services, and
  the app already depends on it.
- **`minSdk 31`.** `targetSdk` is the constraint's other half and is still
  `34` in `app/build.gradle`; ticket 17 raises it to `36`. `compileSdk` is
  already `36`.
- **No personal email address anywhere** — not in the tree, not in commit
  metadata, not in published artifacts. Commits use the GitHub no-reply
  address configured repo-locally. **This holds today** (ticket 06): the
  crash reporter that mailed reports to the upstream author's personal
  address is gone, along with the funding file and every upstream feedback
  link, and no address was substituted in its place. The app has no crash
  reporting at all, and GitHub issues on
  `https://github.com/jmnicolas90/Ding` are the only contact channel. It is
  enforced, not just documented (ticket 20): gate G1 below greps every tracked
  file for an address, greps the staged index as well, checks the author and
  committer identity the next commit would carry, and walks every commit this
  fork authored — author, committer, the whole message including trailers, and
  the commit's own tree, so an address that was committed and redacted later is
  still caught. It fails on the commit, file and line and never prints the
  address. An identity git cannot report is a failure too, the one exception
  being a GitHub Actions runner, where the identity check is skipped and says
  so. Allowed are `LICENSE.md`, `LICENSES/`, `CONTRIBUTORS.md`, the licences
  page generated from `LICENSE.md`, and no-reply addresses — which means a local
  part of exactly `noreply` or the domain `users.noreply.github.com`, not the
  word appearing somewhere in an otherwise deliverable address. Attribution the
  GPL requires stays; anything else is a leak. Upstream's commits are excluded by commit range (`^d34bf2f
  ^62ef3e8`), never by naming an address, and the historical trees also pass
  over an address upstream's own trees already hold — the fork's early commits
  carry upstream's files unchanged and a clone gets that address from upstream's
  commits anyway. Full history is required: a shallow clone or a missing
  exclusion commit is a failure, which is why CI checks out with
  `fetch-depth: 0`.
- **Never commit a red gate.**

## The gate

One command, from the repo root, and it must be green before every commit:

```
scripts/check.sh
```

It runs seven stages, fail-fast, in this order. The individual invocations:

| Stage | Command |
|---|---|
| G0 preflight | not Gradle — `scripts/check-sdk-platform.sh` (is `$ANDROID_HOME/platforms/android-36` there?) |
| G1 email guard | not Gradle — `scripts/check-no-personal-email.sh` |
| G2 lint | `./gradlew :app:lintDebug :app:lintRelease` |
| G3 unit tests | `./gradlew :app:testDebugUnitTest` |
| G4 Google guard | `./gradlew :app:checkNoGoogleDependencies` |
| G5 debug APK | `./gradlew :app:assembleDebug` |
| G6 release APK | `./gradlew :app:assembleRelease` |

`.github/workflows/ci.yml` runs the same tasks in the same order on every
branch push and pull request. If the two ever drift, one of them is lying
about whether the tree is good — fix the drift, don't pick a winner.

Notes that save time: G0 exists because `compileSdk 36` needs the
`platforms;android-36` SDK package specifically, and Android Studio installs
`android-36.1`, which is a different package and does not substitute. Lint
fails on errors only; there are pre-existing warnings and making them fatal
is a separate cleanup. G6 is separate from G5 because the debug build is
debuggable and therefore skips R8's optimization and obfuscation passes, so
release-only breakage is invisible until that stage. The first run in a
fresh worktree is slow (several minutes); later runs are much faster.

G3 runs two kinds of test in one JVM run. The pure layer — the transition
function, the command runner, the settings decoders — stays Kotest specs, with
no Android on the classpath at all. The Android half is plain JUnit 4 classes
under Robolectric, which the JUnit vintage engine runs beside the specs:
`AlarmToNotificationRoundTripTest` takes an added reminder through the alarm
`AlarmManager` is holding, its pending intent's request code and due-time
extra, `ReminderBroadcastReceiver`, and out to the notification on screen, so
the glue in `ReminderManager.kt` — the four numbers that are one identity
across alarms, notifications and pending intents — is checked on every commit
instead of only on a device; `SettingsRepairTest` does the same for the reads
in `Prefs.java` that replace a stored value of the wrong type.
`NotificationPermissionGrantTest` and `ExactAlarmPermissionGrantTest` cover the
two permissions whose loss silently costs a delivery.

The three that drive reminders share `RobolectricReminderHarness`, a base class
holding the driving and none of the asserting: the fixed clock, the
notification channel, the add, the alarm readers, and the fired pending intent
handed to the receiver. It grants nothing, so a test about a permission being
absent starts where it needs to. Three things to know when writing more of
them: Robolectric needs the app's resources, hence
`unitTests.includeAndroidResources` in `app/build.gradle`; each concrete class
pins its own SDK level with `@Config`, which the harness deliberately does not
carry, because the level is part of what a test is about — `@Config(sdk = [36])`
for most, `@Config(sdk = [31, 32])` for the exact-alarm permission path that
exists only there; and Robolectric delivers a broadcast only to a receiver a
test registered, never to one the manifest declares, so a fired pending intent
is handed to the receiver by the test, after checking which class the intent
names. When the manifest entry is itself the thing under test, the test asks
`queryBroadcastReceivers` which class would get the broadcast and instantiates
that answer, so deleting the entry fails the test. What Robolectric cannot answer — whether a real
`AlarmManager` fires at all — is the device pair below.

Not every script under `scripts/` is a gate.
`scripts/generate-open-source-licenses.sh` regenerates the in-app licences
page, `app/src/main/assets/open_source_licenses.html`, from the "Included
work" section of `LICENSE.md`. Run it by hand whenever that section changes
and commit the result; nothing runs it for you. It needs `pandoc 3.11`
specifically — the version is pinned in the script because different pandoc
versions render the same markdown differently — and it is not installed by
default. The script checks the version and tells you how to install that
exact one user-locally when it is missing or wrong.

### The device pair

The gate above runs entirely in a JVM, so the one thing this app exists to do —
fire — is the one thing it cannot see. A second pair covers that, and it is a
pair for the same reason the gate is: a local script and a workflow running the
same Gradle task, so neither can quietly stop being true.

| | Command | When |
|---|---|---|
| `scripts/check-device.sh` | `./gradlew :app:pixel6aospDebugAndroidTest` (plus `-Pandroid.testoptions.manageddevices.emulator.gpu=host`) | before every `--no-ff` merge to `main`, whatever the ticket touched |
| `.github/workflows/device.yml` | the same Gradle task | every push to `main`, and manual dispatch |

The drift rule of the gate applies here unchanged: if the script and the
workflow ever run different work, one of them is lying — fix the drift, don't
pick a winner. **The Gradle task is the same; what differs is the renderer.**
The dev desktop segfaults under the SwiftShader renderer AGP defaults to, so the
script asks for `-gpu host`; the GitHub runner has no host GPU to hand out, so
the workflow takes the default. That flag is therefore on the command and never
in `gradle.properties`, which both share. The script also runs the gate's G0
preflight first and the workflow does not, for the same reason `ci.yml` skips
it: the runner image is what installs the SDK there.

`pixel6aosp` is declared under `testOptions.managedDevices` in
`app/build.gradle`: Pixel 6, API 36, `systemImageSource = "aosp"` — pure
AOSP, no Google APIs, the same proxy for the GrapheneOS Pixel 6 the manual
checks use. Gradle creates, boots and tears the emulator down itself, under
`~/.android/avd/gradle-managed`, so a run never touches the hand-made
`bench-pixel6-aosp` AVD and never uninstalls anything from an attached
device, which `connectedDebugAndroidTest` would.
`./gradlew :app:cleanManagedDevices` deletes it. Known noise, ignore it: every
run prints *"pixel6aosp has an unspecified `testedAbi`"* although
`app/build.gradle` spells it out — AGP's setup task never copies that one
property onto itself. Nothing in the build file silences it. Verified on
this machine 2026-09-05: the managed device boots and runs the suite in well
under a minute, so the hand-launched emulator's habit of dying silently
under `-gpu swiftshader_indirect` is not inherited. Should that change, the
fallback is `connectedDebugAndroidTest` against `bench-pixel6-aosp` started
with the flags in the emulator recipe (`-gpu host -feature -GnssGrpcV1`,
outside the agent's sandbox) — same test, hand-started device, and the
script and the workflow both change to it together.

The suite is `app/src/androidTest/`, and today it is one test.
`AlarmFiresOnDeviceTest` adds a reminder due a few seconds out and then fires
nothing itself: the platform's own `AlarmManager` wakes the receiver, and the
notification is read back from `NotificationManager.getActiveNotifications`,
not from the shade with UiAutomator. What it cannot cover is a *dead* process
being woken — a test cannot survive its own process being killed — which is
charted in the map's *Not yet specified*.

The verdict is the task's exit code, and the explanation is not the scrollback:
`app/build/reports/androidTests/managedDevice/debug/allDevices/index.html` is
the report, and
`app/build/outputs/androidTest-results/managedDevice/debug/pixel6aosp/` holds a
per-test logcat capture, which is where a device-side failure actually says what
happened.

## Working conventions

- **Plain language, no invented jargon.** Name things by what they do. No
  codenames for concepts, components, plans or workflows. This applies to
  code, comments, docs, tickets, commit messages and agent reports alike.
- **A ticket must fit one fresh agent at low context**: one narrow change, a
  handful of files, one sitting. If it looks bigger, split it before
  starting.
- **One long-lived branch, `main`.** Code changes happen in short-lived
  worktrees on ticket branches, one ticket each, merged back into `main`
  with `--no-ff` once the gate is green — never committed directly on
  `main`. Before the merge, `scripts/check-device.sh` too, whatever the
  ticket touched. Worktrees live under `.claude/worktrees/` (gitignored);
  delete the worktree and its branch after the merge. Releases are marked by
  tags, not by a branch. (Upstream's git-flow `develop`/`main` split is
  retired.)
- **Never commit red gates**, and don't leave finished green work
  uncommitted at the end of a turn.
- **Commit trailer names the model that actually wrote the code**, at its own
  vendor's no-reply address — `Co-authored-by: Opus 5 <noreply@anthropic.com>`
  for this one. A non-Anthropic model uses its own name and its own vendor's
  address, e.g. `Co-authored-by: Codex GPT-5 <noreply@openai.com>`. It never
  signs as an Anthropic model. An honest trailer is the only thing that keeps
  `git log` usable as a record of who wrote what.

## Domain vocabulary

The full language is `CONTEXT.md` (glossary) and `docs/reminder-state-machine.md`
(states, commands, transition table, invariants), with the architectural
choice behind it in `docs/adr/0001-pure-transition-function.md`. Read those
before touching reminder state. The short version:

- **A reminder is in exactly one of three states.** `SCHEDULED` — not yet
  delivered, holds an alarm at its due time. `NOTIFIED` — delivered, the user
  has not dealt with it, notification on screen. `DONE` — finished, but a
  resting state rather than a terminal one, since a reschedule re-arms it.
  "Overdue" is not a state: a `SCHEDULED` reminder whose due time has passed
  is simply one that the startup sweep delivers on sight.
- **Deliver** is the transition that shows the notification and moves
  `SCHEDULED` to `NOTIFIED`. **Mark done** is the transition to `DONE`, by
  swiping the notification away or from the reminders list. The notification
  has no mark-done button: clicking it opens the edit dialog. Adding one is on
  the fork's own feature list, `docs/planned-features.md`.
- **Nagging** is repeat delivery of a `NOTIFIED` reminder at a fixed
  interval, counted from its original due time, until it is dealt with.
  Counting from the due time rather than from the last nag is deliberate: a
  nag delayed past several intervals fires once, not once per missed
  occurrence.
- **Reminder ids are even, and allocated by a counter in shared
  preferences.** The runner reads `PREF_STATE_NEXTID` under its lock, gives it
  to the new reminder, and writes back `nextId + 2` in the same commit as the
  reminder list.
- **The id doubles as the notification id and as the `PendingIntent` request
  code**, which is what the even-numbered allocation is for. A reminder's
  Deliver and Nag alarms both use request code `id`, so they share one alarm
  slot and setting either one replaces the other; the mark-done broadcast that
  a swiped-away notification sends uses `id + 1`, which the gap between even
  ids keeps free. The notification is posted with `notify(reminder.id, ...)`,
  so a nag replaces the previous notification instead of stacking.
  Consequence: an id is an identity across three different Android subsystems
  at once, and reusing or re-deriving one silently cross-wires alarms,
  notifications and intents.
- **Every reminder state change goes through one pure function** (ticket 10).
  `app/src/main/java/app/ding/state/ReminderTransition.kt` holds
  `transition(stored, command, now)`, which returns an outcome and a list of
  effects and has no Android imports at all, so it is tested on a plain JVM
  with a fixed clock. `ReminderCommandRunner` in the same package wraps it:
  lock, read, transition, write, then effects — persist first, always.
  `ReminderManager` is the Android half (alarms, notifications, pending
  intents) and exposes `run`, `addReminder` and `reconcileAllReminders`;
  `ReminderStorage` reads publicly and hands its writes to the runner alone.
  `Reminder.status` is a `val`. Reading never throws (ticket 13): decoding is
  `decodeStoredReminders` in the same package, and a stored value it cannot read
  is moved to `reminders_unreadable`, `reminders_unreadable_format_version` and
  `reminders_unreadable_at` — one value, the first — before anything writes to
  `reminders` again, so the app starts on an empty list and the user is offered
  the raw data to share or discard instead of a startup crash loop.
- **The stale-alarm rule and the missing-reminder rule both hold now.** Every
  Deliver and Nag alarm carries the due time it was set for, and a mismatch
  with the stored due time makes the alarm stale: ignored, not an error. A
  reminder that is not in the store is cleanup — `Unchanged` plus cancel alarm
  and cancel notification — never an exception, so `ReminderAction.run` no
  longer calls a `getReminder` that throws. Together with the status guard,
  that is what stops a cold-start sweep and the alarm that woke the process
  from alerting the same reminder twice.

## Tickets and bookkeeping

Tickets live as markdown under `.scratch/appropriate-and-fix/` (committed,
not gitignored). `.scratch/appropriate-and-fix/map.md` is the map: hard
constraints, settled decisions, what is out of scope, and a *Decisions so
far* log.

Routing is per ticket, from the `Type:` line — do not re-enter the map's
skill for the whole map:

- `Type: task` → `/implement`. The decision was already made while charting.
- `Type: grilling` (09, 16, 18) → `/wayfinder`. Genuine fog; the answer does
  not exist yet. A grilling ticket resolves a decision, and implementing it
  is a separate ticket.

After finishing a ticket, do the bookkeeping by hand in the same commit: set
`Status: resolved` on the ticket file, and append one line to *Decisions so
far* in `map.md` in the format of the lines already there.

## Environment

Android SDK at `/home/skynet/dev/android/sdk` (`$ANDROID_HOME`), and it needs
the `platforms;android-36` package. Gradle JDK is Temurin 21 at
`/home/skynet/dev/jdk/jdk-21.0.12+8` (`$JAVA_HOME`), matching the
`sourceCompatibility`/`targetCompatibility` in `app/build.gradle` and the
`java-version` in CI. Host is Fedora with a French locale, so Gradle and git
output may come back in French.

The 02-09-2026 code review of the upstream code is at the repo root
(`simple-reminder-code-review-02-09-2026.md`); every one of its findings is
tracked as a ticket, fixed or consciously ruled out of scope.
