# 27 — Test the alarm-to-notification round trip with Robolectric

Type: task
Status: open
Blocked by: —

## Question

Implements the first half of ticket 18's decision
(`18-how-to-test-alarm-delivery.md`, read its *Resolution* first). The pure
layer is tested; the Android glue in `app/src/main/java/app/ding/ReminderManager.kt`
is not. This ticket adds Robolectric to the JVM unit-test stage G3 and writes
the first test of that glue: the round trip from an added reminder to a
notification, through a real `PendingIntent` and the real receiver.

Every change, nothing more:

1. **Dependencies.** Add `org.robolectric:robolectric` as `testImplementation`
   at the newest release that supports the SDK level the tests run at, and
   `org.junit.vintage:junit-vintage-engine` so the existing JUnit Platform run
   also picks up JUnit 4 classes. Set `unitTests.includeAndroidResources true`
   in `testOptions`. Kotest is untouched; the pure tests stay Kotest specs.
   The gate's Google guard walks the runtime classpath only, so a test
   dependency is not checked by it, but keep it free of `com.google.android.gms`
   all the same.
2. **The test**, a plain JUnit 4 class under `app/src/test/java/app/ding/`
   run with `RobolectricTestRunner`, pinned with `@Config` to one SDK level of
   31 or above that this Robolectric release supports (prefer the highest):
   - Add a reminder due one minute after the fixed test clock through
     `ReminderManager.addReminder`.
   - Assert the alarm shadow holds exactly one alarm, at the due time, and that
     its pending intent was created with request code `reminder.id` and
     carries a serialized `Deliver` action whose `expectedDueTime` is the due
     time.
   - Advance the clock to the due time and send that pending intent, so it is
     delivered to `ReminderBroadcastReceiver` the way `AlarmManager` would.
   - Assert the notification shadow holds one notification under id
     `reminder.id`, that its delete intent uses request code `reminder.id + 1`,
     and that the stored reminder is now `NOTIFIED`.
   - One more case: delivering the same pending intent a second time changes
     nothing (the stale-alarm and status guards, seen end to end).
3. **The runner's clock.** `ReminderCommandRunner` defaults its clock to
   `System::currentTimeMillis`; the test needs the runner that `ReminderManager`
   holds to read a clock the test controls. The smallest seam wins — a
   test-visible way to supply the clock to the process-wide runner, or setting
   Robolectric's system clock if the runner reads it — and it must not change
   production behaviour.
4. **CLAUDE.md.** One paragraph under *The gate*: G3 now runs Kotest specs and
   Robolectric JUnit 4 classes together, what each is for, and that the glue
   in `ReminderManager.kt` is tested under Robolectric.

Not in this ticket: the device harness (ticket 28), the permission findings
(tickets 29 and 30), any refactor of `ReminderManager.kt` beyond the clock
seam.

**Done when** the gate is green with the new test in G3, and the test fails if
the Deliver request code, the due-time extra or the notification id is
changed by hand.
