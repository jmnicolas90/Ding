# 18 — Decide how alarm delivery gets tested automatically

Type: grilling
Status: resolved
Blocked by: 09

## Question

**Ticket 09 is resolved.** Implement against `docs/reminder-state-machine.md` (transition table, effects, invariants, and the numbered test list); use the vocabulary in `CONTEXT.md`.

Review finding: **"Core reminder state and persistence paths have no automated tests"** (medium). The 65 existing tests cover date arithmetic and `TimeMatcher` — nothing touches storage, ID allocation, failed commits, malformed JSON, state transitions, cold-start delivery, notification permissions, edit flows, or boot reconciliation. Those are the highest-risk parts of the app.

Tickets 10 through 13 each add tests for their own change. This ticket decides the **approach** for the class of test none of them can write alone: does the alarm actually fire, and does the notification actually appear?

The hard part is that this is a test about *time* and *process lifecycle*. Waiting is not testing. Options to weigh:

- **Robolectric** — fast, runs in the JVM gate, can drive the scheduler and fake the clock. Cannot tell you what a real `AlarmManager` under Doze does.
- **Instrumented tests on `bench-pixel6-aosp`** — real platform, real `AlarmManager`, and the emulator is already on this machine. Slow, and needs a decision about whether CI runs it (GitHub's Linux runners do support KVM).
- **A seam in the app** — put the clock and the scheduler behind interfaces so most of the behaviour is testable as pure logic, and only a thin layer needs a device.

Blocked by ticket 09 because the answer depends on where the transition boundary landed: a good boundary makes most of this pure-logic testing, and a bad one forces everything onto a device.

Worth keeping in proportion: this app has run reliably on GrapheneOS for years. The risk being managed is **regression from our own changes**, not a pre-existing defect. That argues for tests that pin current behaviour before it is touched, rather than an exhaustive device suite.

**Done when** there is a decided approach, a named first test to write, and a note on whether it runs in CI.

## Resolution (2026-09-05)

Decided in conversation. Two layers get tests, each on the tool that can see
it; the pure layer is already covered and is not reopened. The implementation
is tickets 27 and 28, and the two permission findings from the global review
become tickets 29 and 30 on top of ticket 27's harness.

- **What is left to test is two layers above the pure one.** Ticket 10 already
  built the seam: `transition` has 22 JVM tests, and the runner is tested over a
  fake store and a recording effect executor. Untested is the *glue* in
  `ReminderManager.kt` — the effect executor that turns `SetAlarm` into an
  `AlarmManager` call with request code `id` and the due time in the intent,
  the notification posted under `notify(id, …)`, the swipe-away MarkDone intent
  on `id + 1`, the permission check before posting, and the receiver that
  closes the loop — and above it the *platform*: whether a real `AlarmManager`
  wakes the process at the due time and the notification reaches the shade.
- **The glue layer is tested with Robolectric in G3.** Its alarm and
  notification shadows can assert request codes, extras and notification ids,
  and fire a captured pending intent back through the receiver, in seconds, on
  every commit, here and in CI. Instrumented-only would push deterministic
  checks onto a slow stage; hand-written interfaces over `AlarmManager` would
  test the fake, not the wiring the vocabulary section calls the dangerous
  part. Robolectric is `org.robolectric`, so the Google guard is untouched.
  These tests are plain JUnit 4 classes run by the vintage engine alongside the
  Kotest specs, which is the path Robolectric supports; the pure tests stay
  Kotest.
- **The platform layer is tested on a Gradle-managed device**, API 36, pure
  AOSP image source, Pixel 6 profile — the same proxy for the GrapheneOS Pixel 6
  that ticket 17 uses — so one Gradle task boots, runs and tears down the same
  image locally and in CI. `connectedDebugAndroidTest` against the hand-launched
  `bench-pixel6-aosp` is the documented fallback, because the hand-launched
  emulator is known to die under the swiftshader renderer and inside the agent
  sandbox, and whether the managed device suffers the same is a fact to
  establish by trying, first thing in ticket 28.
- **The device stage is a second pair, not a seventh gate stage.** A device
  script under `scripts/` and a device workflow under `.github/workflows/` run
  the same Gradle task; the drift rule applies to that pair exactly as it does
  to `check.sh` and `ci.yml`. Locally it runs before every `--no-ff` merge to
  `main`, whatever the ticket touched; in CI it runs on every push to `main`
  and on manual dispatch. It is not in `check.sh` because an emulator boot on
  every commit would erode the run-the-gate-always habit, and it is not
  local-only because that leaves the one test that answers "does it fire" to
  run only when someone remembers.
- **The first test is the round trip under Robolectric**: add a reminder due in
  the future, assert exactly one alarm at its due time whose pending intent
  carries request code `id`, fire that intent through the receiver at the due
  time, assert a notification under id `id` exists and the reminder is
  `NOTIFIED`. The first device test is the platform smoke: add a reminder due a
  few seconds from now, wait, assert it is among the app's active notifications
  as reported by `NotificationManager` — not by reading the shade with
  UiAutomator, which needs a dependency and breaks on rendering differences.
  Both pin today's behaviour, which is the regression risk being managed.
- **The instrumented smoke proves delivery into a live process.** A test
  cannot survive its own process being killed, so waking a dead process can
  only be driven from outside: an adb script that installs, adds a reminder,
  force-stops the app, waits and reads the notification list. That script, and
  Doze forced with `dumpsys deviceidle force-idle`, are both one adb command
  around the harness once it exists, so they go to the map's *Not yet
  specified* rather than into ticket 28.
- **Out of scope for this map**: automating a reboot and the boot-time
  Reconcile (cannot be driven from a test, and the fork's own bugs there are
  pinned at the pure layer), and the exact-alarm revocation path that exists
  only on API 31 and 32, for which no emulator image is installed. Ticket 17's
  manual look on the emulator stays the only check for those.
- **The two permission findings need none of the above.** Robolectric can deny
  and grant `POST_NOTIFICATIONS` and flip the exact-alarm state, so their tests
  land in G3 on ticket 27's harness: tickets 29 and 30.
- **Order from here**: 22, 27, 28, 29, 30, 17, 26. Ticket 28 goes before 17 so
  that the `targetSdk 36` verification has the managed device to run on.
