# 28 — Prove a real alarm fires, on a Gradle-managed AOSP device

Type: task
Status: open
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
