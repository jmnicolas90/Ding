# 28 — Prove a real alarm fires, on a Gradle-managed AOSP device

Type: task
Status: resolved
Blocked by: 27

## Question

Implements the second half of ticket 18's decision
(`18-how-to-test-alarm-delivery.md`, read its *Resolution* first). Robolectric
cannot say what a real `AlarmManager` does; this ticket adds the one test that
can, runs it on a Gradle-managed emulator, and pairs a local script with a CI
workflow so the two never drift.

Every change, nothing more:

1. **First, establish the fact ticket 18 left open.** The hand-launched
   `bench-pixel6-aosp` emulator dies silently under `-gpu swiftshader_indirect`
   and inside the agent's Bash sandbox (see the emulator recipe in memory and
   Loquace's `docs/testing.md`). Declare the managed device and try to boot it
   before writing anything else. If it dies the same way, record what was
   tried in this ticket and fall back to `connectedDebugAndroidTest` against
   `bench-pixel6-aosp` started with the known-good flags; the script and the
   workflow then run that instead, and the map's decision line is amended.
2. **The managed device**, in `app/build.gradle` under
   `testOptions.managedDevices.localDevices`: a plain-language name such as
   `pixel6aosp`, `device = "Pixel 6"`, `apiLevel = 36`,
   `systemImageSource = "aosp"` — pure AOSP, no Google APIs, the same proxy for
   the GrapheneOS Pixel 6 that ticket 17 uses. Add the `androidx.test` runner,
   rules and JUnit dependencies as `androidTestImplementation`;
   `testInstrumentationRunner` is already set.
3. **The test**, under `app/src/androidTest/java/app/ding/`: grant
   `POST_NOTIFICATIONS` with a `GrantPermissionRule` (exact alarms are granted
   by `USE_EXACT_ALARM` on API 33+), add a reminder due a few seconds from now
   through `ReminderManager.addReminder`, poll `NotificationManager`'s active
   notifications for up to a bounded number of seconds, and assert one under
   `reminder.id`. Clean up the reminder and the notification afterwards. No
   UiAutomator, no reading of the shade.
4. **The local script**, `scripts/check-device.sh`: the same G0 preflight as
   `check.sh`, then the managed-device task
   (`./gradlew :app:<name>DebugAndroidTest`). Document in its header that it
   runs before every `--no-ff` merge to `main`, whatever the ticket touched.
5. **The workflow**, `.github/workflows/device.yml`: on push to `main` and on
   `workflow_dispatch`, `ubuntu-latest`, KVM enabled the way GitHub documents
   for its Linux runners, same JDK and Gradle setup as `ci.yml`, then the same
   Gradle task the script runs. Not on every branch: ticket branches run
   `ci.yml`, the device pair runs where merges land.
6. **CLAUDE.md.** Under *The gate*, a short section for the device pair: the
   script, the workflow, when each runs, the drift rule extended to them, and
   the fallback if the managed device cannot boot on this machine. Under
   *Working conventions*, add the device script to the merge step.

Not in this ticket: waking a dead process (an adb script, in the map's *Not
yet specified*), Doze, reboot, and ticket 17's `targetSdk` change — it is
sequenced after this one precisely so it can use this harness.

**Done when** the device task is green locally and in CI on `main`, and the
test fails if the reminder is added with a due time in the past.

## Resolution (2026-09-05)

**Step 1 first: the managed device boots on this machine, so the fallback was
not needed.** `./gradlew :app:pixel6aospSetup` and then
`:app:pixel6aospDebugAndroidTest`, both with
`-Pandroid.testoptions.manageddevices.emulator.gpu=host` and outside the agent's
Bash sandbox, ran green at the first attempt: the emulator booted, the app and
the test APK installed, and the one test passed, the whole task in 37 seconds.
Two facts explain why it does not die the way the hand-launched
`bench-pixel6-aosp` does. Gradle keeps its own AVD under
`~/.android/avd/gradle-managed` (`dev36_default_x86_64_Pixel_6`), which another
project on this machine had already created, so nothing was downloaded or
first-booted; and the renderer is the same `-gpu host` the emulator recipe uses,
passed as a Gradle property rather than an emulator flag. AGP's default renderer
was not tried — it is what segfaults here — and neither was the sandbox, since
the recipe already says the sandbox kills emulators. So
`connectedDebugAndroidTest` stays documented as the fallback and is not what
runs.

What landed:

- **The device** `pixel6aosp` under `testOptions.managedDevices.localDevices` in
  `app/build.gradle` — Pixel 6, API 36, `systemImageSource = "aosp"`, plus
  `testedAbi = "x86_64"`, which AGP 8.13 asks for by name on every run because
  AGP 9 will otherwise default it to `arm64-v8a` and this host's image cannot
  translate that. The `androidx.test` runner, rules, core and JUnit extension go
  in as `androidTestImplementation` beside `junit:junit`; none is a Play Services
  group, so the Google guard is untouched.
- **The test** `app/src/androidTest/java/app/ding/AlarmFiresOnDeviceTest.kt`. It
  grants `POST_NOTIFICATIONS` with a `GrantPermissionRule`, adds a reminder due
  five seconds out, and fires nothing itself: the platform's `AlarmManager` wakes
  the receiver. The notification is polled out of
  `NotificationManager.getActiveNotifications` for up to a minute and asserted
  under `reminder.id`; the reminder is deleted and the notification cancelled
  afterwards. The device logcat the run captures shows the whole trip — `Set
  alarm ("exact and allow while idle") for 18:10:50` and, five seconds later,
  `Deliver(reminderId=0, expectedDueTime=…): Updated(… status=NOTIFIED)`.
- **The pair**: `scripts/check-device.sh` and `.github/workflows/device.yml`,
  running the same Gradle task. The script's preflight is now the gate's own G0,
  moved into `scripts/check-sdk-platform.sh` and called by both, because a
  preflight copied into two files is one that will eventually check two different
  things. The one deliberate difference between script and workflow is the
  renderer and not the task: `-gpu host` locally, AGP's default on a runner with
  no GPU.
- **`CLAUDE.md`** gained a *The device pair* section after the gate — the two
  commands, when each runs, the drift rule, the fallback, and where the report
  and the per-test logcat are — and the merge step in *Working conventions* now
  names the device script.

**Done when, checked.** The device task is green locally. The test is not
vacuous: with the due time moved into the past it fails with
`Refused(reason=PastDue)`, the mutation the ticket names, and with the poll
looking for `id + 1` — a notification that never arrives, which is what a
failure to fire looks like — it fails with the "the alarm did not fire" message
after its full minute of patience. Green in CI on `main` is the half only the
merge can establish.
