# Code Review — SimpleReminder — 02-09-2026

## Summary

- Commit: `d34bf2f`, branch: `develop`
- Coverage: 139 total files / 121 reviewed files / 18 skipped files / 87.05% coverage rate
- Findings: Critical 0 · High 2 · Medium 9 · Low 3
- SimpleReminder has a compact Android architecture built around a `SharedPreferences` JSON store, a manager that owns alarms and notifications, and activity/fragment UI code. The main risk is that reminder state transitions are split across application startup, alarm delivery, persistence, and UI editing without an idempotent boundary. Storage is simple and understandable, but failed writes and invalid persisted JSON are not handled, and every mutation rewrites the full collection synchronously. The manifest generally limits component exposure appropriately and no embedded secrets or direct injection vulnerabilities were found. The two existing unit suites cover date math and text parsing well (65 tests passed), but none exercise persistence, notification state, process cold-start behavior, or Android lifecycle behavior. `:app:lintDebug` currently fails with one API-level error and reports 33 warnings. The license-generation utility is also not fail-fast and presently returns success after its patch step fails, so release artifacts can silently remain stale.

## Critical & High

### A cold alarm can deliver the same reminder twice

- **File:** `app/src/main/java/felixwiemuth/simplereminder/ReminderManager.kt:122-127`
- **Category:** bug  **Severity:** high
- **Rule:** OCR rule group 4 (`**/*.kt`) — correctness and lifecycle/state checks
- **Problem:** `Notify.run` always calls `showReminder` without validating the reminder's current state. When an alarm starts a dead process, `Main.onCreate()` runs first and `scheduleAndReshowAllReminders()` already finds the due `SCHEDULED` reminder, sends its non-silent notification, marks it `NOTIFIED`, and schedules nagging. The receiver then processes the original `Notify` action and sends the same non-silent notification again. Because the notification builder does not use `setOnlyAlertOnce(true)`, this can produce duplicate sound/vibration and duplicate writes on the normal cold-start delivery path.
- **Fix:** Make actions idempotent. At minimum, let `Notify` proceed only when the stored status is `SCHEDULED`; preferably include and validate an expected due timestamp or generation token so stale alarms cannot act on a rescheduled reminder. Add a cold-process test that executes application reconciliation before the delivered `Notify` action and asserts one alert and one state transition.

### Failed persistence is reported as success

- **File:** `app/src/main/java/felixwiemuth/simplereminder/ReminderStorage.kt:68-74`
- **Category:** bug  **Severity:** high
- **Rule:** OCR rule group 4 (`**/*.kt`) — error handling and correctness
- **Problem:** `SharedPreferences.Editor.commit()` returns `false` when the durable write fails, but the result is ignored. The method still broadcasts a successful change and returns the newly added or updated reminder; callers then schedule its alarm even though the reminder is absent from durable storage. On process death the reminder is lost, and an already-scheduled alarm later crashes while looking up the missing ID.
- **Fix:** Check the result of `commit()`, do not broadcast on failure, and propagate a typed persistence exception so callers do not schedule or report success. Longer term, use a transactional store such as Room for reminder records.

## Medium

### Saving an already-notified or done reminder creates an unscheduled past-due reminder

- **File:** `app/src/main/java/felixwiemuth/simplereminder/ui/EditReminderDialogActivity.kt:71-91`
- **Category:** bug  **Severity:** medium
- **Rule:** OCR rule group 4 (`**/*.kt`) — correctness and boundary conditions
- **Problem:** Only `SCHEDULED` reminders restore their original due date. `NOTIFIED` and `DONE` reminders keep the dialog's initial current-minute value, and `buildReminderWithTimeTextNagging()` creates a builder whose status defaults to `SCHEDULED`. By the time the user presses OK that minute value is normally in the past, so `updateReminder(..., true)` cancels the current notification but does not schedule a replacement. The record remains `SCHEDULED` and past due until a later startup sweep unexpectedly shows it.
- **Fix:** Explicitly define edit versus reschedule semantics. Preserve the original date/status for a pure edit, or require a future time and deliberately reset the status when rescheduling; never save `SCHEDULED` with a past due date. Add tests for editing each status without touching the time picker.

### The advertised locked-boot path cannot run

- **File:** `app/src/main/AndroidManifest.xml:99-108`
- **Category:** bug  **Severity:** medium
- **Rule:** OCR rule group 2 (`default`) — Android configuration correctness
- **Problem:** The receiver declares `LOCKED_BOOT_COMPLETED`, but neither it nor the application is marked `android:directBootAware="true"`. Even if enabled, the component therefore cannot run before user unlock. In addition, startup immediately reads the ordinary credential-protected `SharedPreferences`, which are unavailable during Direct Boot. This contradicts the changelog's claim that reminders are scheduled before login.
- **Fix:** Either remove `LOCKED_BOOT_COMPLETED` and the before-unlock claim, or implement Direct Boot end to end: mark the required components direct-boot aware, keep the needed reminder state in device-protected storage, migrate/unlock safely, and test a reboot-before-unlock scenario.

### Android lint fails on the Quick Settings service API level

- **File:** `app/src/main/AndroidManifest.xml:86-97`
- **Category:** bug  **Severity:** medium
- **Rule:** OCR rule group 2 (`default`) — Android configuration correctness
- **Problem:** The project has `minSdkVersion 21`, while `QuickTileService` extends API-24 `TileService`. The Kotlin class is annotated, but the manifest declaration is not version-gated, and `:app:lintDebug` fails with `NewApi` at this service. This prevents a clean lint quality gate and leaves pre-24 component loading dependent on platform behavior.
- **Fix:** Disable the service by default and enable it from a `values-v24` boolean resource, or otherwise version-gate it; add `tools:targetApi="24"` once the runtime gating is explicit. Keep lint in CI so this remains enforced.

### Large nag intervals overflow before conversion to `Long`

- **File:** `app/src/main/java/felixwiemuth/simplereminder/data/Reminder.kt:90-93`
- **Category:** bug  **Severity:** medium
- **Rule:** OCR rule group 4 (`**/*.kt`) — numeric correctness
- **Problem:** `60 * 1000 * naggingRepeatInterval` is evaluated as `Int` and only then converted to `Long`. Values from 35,792 minutes upward overflow, while both the settings input and `NumberPicker` allow values up to `Int.MAX_VALUE`. The resulting negative or zero duration can schedule immediate/past alarms or cause `% d` in `calculateNextNagTime()` to throw division by zero.
- **Fix:** Multiply in `Long` (`60_000L * naggingRepeatInterval`) and enforce a documented upper bound in the model and both input paths. Add boundary tests around 35,791/35,792 and the chosen maximum.

### Invalid stored JSON can put the app in a startup crash loop

- **File:** `app/src/main/java/felixwiemuth/simplereminder/ReminderStorage.kt:86-88`
- **Category:** bug  **Severity:** medium
- **Rule:** OCR rule group 4 (`**/*.kt`) — error handling and serialization correctness
- **Problem:** Persisted reminder JSON is decoded without handling `SerializationException`, incompatible schema, a wrong preference type, or a null/corrupt value. `Main.onCreate()` reads this data on every process start, so one invalid state value prevents every app component from starting. A stored format-version key exists but is not used here to select migration or recovery.
- **Fix:** Centralize versioned decoding and migration, catch expected persistence/serialization failures, preserve the raw value for recovery, and present a controlled repair/export path rather than repeatedly crashing or silently deleting reminders.

### Every reminder action performs full-store JSON I/O on the main thread

- **File:** `app/src/main/java/felixwiemuth/simplereminder/ReminderStorage.kt:108-115`
- **Category:** performance  **Severity:** medium
- **Rule:** OCR rule group 4 (`**/*.kt`) — hot-path performance and resource use
- **Problem:** Each update reparses the complete list, copies it, reserializes it, and synchronously commits the entire JSON value. Alarm actions call this from `BroadcastReceiver.onReceive()` on the main thread, and completed reminders have no retention policy, so latency grows with the app's lifetime. This can eventually exceed the receiver execution window or cause UI/application-startup stalls.
- **Fix:** Store reminders as individually addressable rows in Room (indexed by ID and status), execute storage work off the main thread with `goAsync()` or structured lifecycle-aware work, and add a retention/archive policy. Until migration, measure and cap list size and avoid redundant parse/write cycles.

### Custom time text size is converted twice

- **File:** `app/src/main/java/felixwiemuth/simplereminder/ui/ReminderDialogActivity.kt:675-693`
- **Category:** bug  **Severity:** medium
- **Rule:** OCR rule group 4 (`**/*.kt`) — Android UI correctness
- **Problem:** The preference value is converted from dp to pixels with `TypedValue.applyDimension`, then assigned through `TextView.textSize`; that setter interprets the number as sp and scales it again. On high-density devices the configured time header can therefore be several times larger than requested. The same issue affects the AM/PM label.
- **Fix:** Call `setTextSize(TypedValue.COMPLEX_UNIT_SP, configuredSize)` directly (or use px with the two-argument `COMPLEX_UNIT_PX` overload), choose one documented unit for the preference, and convert the raw `16` bottom margin to dp as well.

### License generation continues after failed mandatory steps

- **File:** `generateOpenSourceLicensesFile.sh:1-5`
- **Category:** maintainability  **Severity:** medium
- **Rule:** OCR rule group 2 (`default`) — build/release correctness and error handling
- **Problem:** The script has no fail-fast mode. The current `OPEN_SOURCE_LICENSES.md.patch` no longer applies to `LICENSE.md`; a direct run prints that failure, then continues. If `pandoc` is also absent it still reaches the final `cp` and exits with status 0, so automation can publish a stale or incorrect `open_source_licenses.html` while reporting success.
- **Fix:** Add a shell declaration and `set -eu`, use a temporary working directory with cleanup via `trap`, validate required tools first, repair/regenerate the patch, and atomically replace generated assets only after every step succeeds. Add a CI check that regeneration leaves the tree unchanged.

### Core reminder state and persistence paths have no automated tests

- **File:** `app/src/test/java/felixwiemuth/simplereminder/util/DateTimeUtilTest.kt:23-101`
- **Category:** test  **Severity:** medium
- **Rule:** Beyond OCR rules — missing tests for risky logic
- **Problem:** The repository's 65 tests cover only date-duration calculations and `TimeMatcher`. There are no tests for `ReminderStorage`, ID allocation, failed commits, malformed JSON, `ReminderAction` state transitions, stale/cold-start alarms, notification permission behavior, edit/reschedule semantics, boot reconciliation, or interval overflow. Those are the highest-risk parts of the application and several defects above would be caught by modest state-machine tests.
- **Fix:** Add storage tests with injectable persistence, pure tests for an explicit reminder state machine, and Robolectric/instrumented tests for cold process alarm delivery, notification actions, Direct Boot decisions, and edit flows. Make unit tests and lint mandatory in CI.

## Low

| File:Line | Category | Issue | Fix |
|---|---|---|---|
| `app/src/main/java/felixwiemuth/simplereminder/ui/util/HtmlDialogFragment.java:207-221` | performance | The raw-resource reader is closed only on the success path; an `IOException` leaks it, and cancellation is not checked while reading. | Use try-with-resources, propagate/log the error, and replace the deprecated fragment-retaining `AsyncTask` with lifecycle-aware loading. |
| `app/src/main/res/values/strings.xml:54-59` | maintainability | Singular/plural duration text is split into ordinary strings and selected with English-only `value == 1` logic, which does not support languages with other plural categories. | Replace each pair with `<plurals>` resources and call `getQuantityString`. |
| `.idea/kotlinc.xml:3-5` | maintainability | IDE metadata pins Kotlin `2.1.20` while the Gradle build uses `2.2.20`, allowing IDE analysis/JPS behavior to diverge from the real build. | Align or remove the project-specific JPS Kotlin version and let Gradle import define it. |

## Recurring patterns

- Reminder state ownership is spread across `Main`, `ReminderManager`, and `ReminderStorage`. Three or more paths can reconcile, deliver, edit, or persist the same reminder without a single idempotent transition API.
- Synchronous full-store work occurs from application startup, broadcast delivery, and UI refresh. The design assumes a permanently small list even though done reminders accumulate indefinitely.
- Platform-version and lifecycle assumptions appear in boot handling, the Quick Settings manifest entry, and the legacy HTML loader. These need executable API-level/lifecycle tests rather than comments and suppressions.

## Recommended next steps

1. Make `ReminderAction.Notify` and all reminder transitions idempotent, then add the cold-start delivery regression test.
2. Treat persistence failure as failure: check commits, introduce typed errors, and add recovery for invalid/versioned JSON.
3. Define and test edit/reschedule behavior for every reminder status and bound/fix nag interval arithmetic.
4. Decide whether pre-unlock reminders are supported; either implement device-protected Direct Boot storage or remove the ineffective manifest action and claim.
5. Move reminder records to a transactional, row-based store and move receiver I/O off the main thread.
6. Restore a green quality gate by version-gating the tile service, repairing the fail-fast license generator, and addressing the UI sizing conversion.

## Coverage

### Skipped files with reasons

The following 18 files were skipped because they are binary artifacts for which a line-based source audit is not meaningful. Their file types were verified; no text/source file was skipped.

- `app/src/main/ic_launcher-playstore.png` — PNG image
- `app/src/main/res/mipmap-hdpi/ic_launcher.png` — PNG image
- `app/src/main/res/mipmap-hdpi/ic_launcher_round.png` — PNG image
- `app/src/main/res/mipmap-mdpi/ic_launcher.png` — PNG image
- `app/src/main/res/mipmap-mdpi/ic_launcher_round.png` — PNG image
- `app/src/main/res/mipmap-xhdpi/ic_launcher.png` — PNG image
- `app/src/main/res/mipmap-xhdpi/ic_launcher_round.png` — PNG image
- `app/src/main/res/mipmap-xxhdpi/ic_launcher.png` — PNG image
- `app/src/main/res/mipmap-xxhdpi/ic_launcher_round.png` — PNG image
- `app/src/main/res/mipmap-xxxhdpi/ic_launcher.png` — PNG image
- `app/src/main/res/mipmap-xxxhdpi/ic_launcher_round.png` — PNG image
- `gradle/wrapper/gradle-wrapper.jar` — ZIP/JAR binary
- `metadata/en-US/images/icon.png` — PNG image
- `metadata/en-US/images/phoneScreenshots/1_AddReminder_v0.9.10.png` — PNG image
- `metadata/en-US/images/phoneScreenshots/2_RemindersList_v0.9.10.png` — PNG image
- `metadata/en-US/images/phoneScreenshots/3_Notification_v0.9.10.png` — PNG image
- `metadata/en-US/images/phoneScreenshots/4_AddReminderCalendar_v0.9.10.png` — PNG image
- `metadata/en-US/images/phoneScreenshots/5_AddReminderDaysAhead_v0.9.10.png` — PNG image

### Excluded files from OCR preview

OCR preview compared a synthetic empty commit with the complete tracked tree and reported 130 files: 83 reviewable and 47 excluded. All 29 text exclusions were manually reviewed despite OCR's exclusion; the 18 binary exclusions are the skipped files listed above. OCR also omitted 9 tracked `.idea` XML files through its built-in path filtering; all 9 were manually reviewed and are included in the repository-level coverage totals.

- `default_path` (manually reviewed): `app/src/test/java/felixwiemuth/simplereminder/util/DateTimeUtilTest.kt`, `app/src/test/java/felixwiemuth/simplereminder/util/TimeMatcherTest.kt`
- `unsupported_ext` (manually reviewed): `.github/ISSUE_TEMPLATE/bug_report.md`, `.github/ISSUE_TEMPLATE/feature_request.md`, `CONTRIBUTING.md`, `CONTRIBUTORS.md`, `LICENSE.md`, `LICENSES/APACHE-2.0`, `OPEN_SOURCE_LICENSES.md.patch`, `README.md`, `app/proguard-rules.pro`, `app/src/main/assets/LICENSES/APACHE-2.0`, `gradlew.bat`, `metadata/de/title.txt`, all 12 files under `metadata/en-US/changelogs/`, `metadata/en-US/full_description.txt`, `metadata/en-US/short_description.txt`, and `metadata/en-US/title.txt`
- `binary` (skipped): the 18 PNG/JAR artifacts listed under “Skipped files with reasons”
- OCR built-in path omission (manually reviewed): `.idea/assetWizardSettings.xml`, `.idea/codeStyles/Project.xml`, `.idea/codeStyles/codeStyleConfig.xml`, `.idea/copyright/GPL3.xml`, `.idea/copyright/profiles_settings.xml`, `.idea/inspectionProfiles/Project_Default.xml`, `.idea/kotlinc.xml`, `.idea/migrations.xml`, `.idea/vcs.xml`

### File checklist

Every tracked file is accounted for below. `reviewed` means the full text was read and assessed with its OCR rule group plus repository context; exact duplicate asset license copies were byte-compared with the fully read root copies.

```text
reviewed | .github/FUNDING.yml
reviewed | .github/ISSUE_TEMPLATE/bug_report.md
reviewed | .github/ISSUE_TEMPLATE/config.yml
reviewed | .github/ISSUE_TEMPLATE/feature_request.md
reviewed | .gitignore
reviewed | .idea/assetWizardSettings.xml
reviewed | .idea/codeStyles/Project.xml
reviewed | .idea/codeStyles/codeStyleConfig.xml
reviewed | .idea/copyright/GPL3.xml
reviewed | .idea/copyright/profiles_settings.xml
reviewed | .idea/inspectionProfiles/Project_Default.xml
reviewed | .idea/kotlinc.xml
reviewed | .idea/migrations.xml
reviewed | .idea/vcs.xml
reviewed | CONTRIBUTING.md
reviewed | CONTRIBUTORS.md
reviewed | LICENSE.md
reviewed | LICENSES/APACHE-2.0
reviewed | LICENSES/GPL3
reviewed | LICENSES/MIT
reviewed | OPEN_SOURCE_LICENSES.md.patch
reviewed | README.md
reviewed | app/.gitignore
reviewed | app/build.gradle
reviewed | app/proguard-rules.pro
reviewed | app/src/main/AndroidManifest.xml
reviewed | app/src/main/assets/LICENSES/APACHE-2.0
reviewed | app/src/main/assets/LICENSES/GPL3
reviewed | app/src/main/assets/LICENSES/MIT
reviewed | app/src/main/assets/open_source_licenses.html
skipped  | app/src/main/ic_launcher-playstore.png | binary PNG
reviewed | app/src/main/java/felixwiemuth/simplereminder/BootReceiver.kt
reviewed | app/src/main/java/felixwiemuth/simplereminder/Main.kt
reviewed | app/src/main/java/felixwiemuth/simplereminder/Prefs.java
reviewed | app/src/main/java/felixwiemuth/simplereminder/QuickTileService.kt
reviewed | app/src/main/java/felixwiemuth/simplereminder/ReminderBroadcastReceiver.kt
reviewed | app/src/main/java/felixwiemuth/simplereminder/ReminderManager.kt
reviewed | app/src/main/java/felixwiemuth/simplereminder/ReminderStorage.kt
reviewed | app/src/main/java/felixwiemuth/simplereminder/data/Reminder.kt
reviewed | app/src/main/java/felixwiemuth/simplereminder/ui/AddReminderDialogActivity.kt
reviewed | app/src/main/java/felixwiemuth/simplereminder/ui/EditReminderDialogActivity.kt
reviewed | app/src/main/java/felixwiemuth/simplereminder/ui/NotificationSettingsFragment.kt
reviewed | app/src/main/java/felixwiemuth/simplereminder/ui/ReminderDialogActivity.kt
reviewed | app/src/main/java/felixwiemuth/simplereminder/ui/SettingsActivity.kt
reviewed | app/src/main/java/felixwiemuth/simplereminder/ui/SettingsFragment.kt
reviewed | app/src/main/java/felixwiemuth/simplereminder/ui/UISettingsFragment.kt
reviewed | app/src/main/java/felixwiemuth/simplereminder/ui/actions/DisplayChangeLog.java
reviewed | app/src/main/java/felixwiemuth/simplereminder/ui/actions/DisplayWelcomeMessage.java
reviewed | app/src/main/java/felixwiemuth/simplereminder/ui/actions/DisplayWelcomeMessageUpdate.java
reviewed | app/src/main/java/felixwiemuth/simplereminder/ui/reminderslist/DisplayType.java
reviewed | app/src/main/java/felixwiemuth/simplereminder/ui/reminderslist/FullDateReminderViewHolder.kt
reviewed | app/src/main/java/felixwiemuth/simplereminder/ui/reminderslist/HeaderViewHolder.kt
reviewed | app/src/main/java/felixwiemuth/simplereminder/ui/reminderslist/ReminderViewHolder.kt
reviewed | app/src/main/java/felixwiemuth/simplereminder/ui/reminderslist/RemindersListActivity.kt
reviewed | app/src/main/java/felixwiemuth/simplereminder/ui/reminderslist/RemindersListFragment.kt
reviewed | app/src/main/java/felixwiemuth/simplereminder/ui/reminderslist/TemplatesFragment.java
reviewed | app/src/main/java/felixwiemuth/simplereminder/ui/reminderslist/TimeOnlyReminderViewHolder.kt
reviewed | app/src/main/java/felixwiemuth/simplereminder/ui/util/HtmlDialogFragment.java
reviewed | app/src/main/java/felixwiemuth/simplereminder/ui/util/UIUtils.kt
reviewed | app/src/main/java/felixwiemuth/simplereminder/util/AlarmManagerUtil.kt
reviewed | app/src/main/java/felixwiemuth/simplereminder/util/DateSerializer.kt
reviewed | app/src/main/java/felixwiemuth/simplereminder/util/DateTimeUtil.java
reviewed | app/src/main/java/felixwiemuth/simplereminder/util/ImplementationError.java
reviewed | app/src/main/java/felixwiemuth/simplereminder/util/OneTimeClickListener.kt
reviewed | app/src/main/java/felixwiemuth/simplereminder/util/TextBasedTimeInput.kt
reviewed | app/src/main/res/drawable/ic_launcher_foreground.xml
reviewed | app/src/main/res/layout/activity_reminder_dialog.xml
reviewed | app/src/main/res/layout/activity_reminders_list.xml
reviewed | app/src/main/res/layout/activity_settings.xml
reviewed | app/src/main/res/layout/dialog_number_picker.xml
reviewed | app/src/main/res/layout/fragment_reminders_list.xml
reviewed | app/src/main/res/layout/fragment_templates.xml
reviewed | app/src/main/res/layout/html_dialog_fragment.xml
reviewed | app/src/main/res/layout/reminder_card.xml
reviewed | app/src/main/res/layout/reminder_card_datefield_full_date.xml
reviewed | app/src/main/res/layout/reminder_card_datefield_time_only.xml
reviewed | app/src/main/res/layout/reminder_section_header.xml
reviewed | app/src/main/res/menu/menu_reminders_list.xml
reviewed | app/src/main/res/menu/menu_reminders_list_actions.xml
reviewed | app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml
reviewed | app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml
skipped  | app/src/main/res/mipmap-hdpi/ic_launcher.png | binary PNG
skipped  | app/src/main/res/mipmap-hdpi/ic_launcher_round.png | binary PNG
skipped  | app/src/main/res/mipmap-mdpi/ic_launcher.png | binary PNG
skipped  | app/src/main/res/mipmap-mdpi/ic_launcher_round.png | binary PNG
skipped  | app/src/main/res/mipmap-xhdpi/ic_launcher.png | binary PNG
skipped  | app/src/main/res/mipmap-xhdpi/ic_launcher_round.png | binary PNG
skipped  | app/src/main/res/mipmap-xxhdpi/ic_launcher.png | binary PNG
skipped  | app/src/main/res/mipmap-xxhdpi/ic_launcher_round.png | binary PNG
skipped  | app/src/main/res/mipmap-xxxhdpi/ic_launcher.png | binary PNG
skipped  | app/src/main/res/mipmap-xxxhdpi/ic_launcher_round.png | binary PNG
reviewed | app/src/main/res/raw/about.html
reviewed | app/src/main/res/raw/help.html
reviewed | app/src/main/res/values-w820dp/dimens.xml
reviewed | app/src/main/res/values/arrays.xml
reviewed | app/src/main/res/values/colors.xml
reviewed | app/src/main/res/values/dimens.xml
reviewed | app/src/main/res/values/ic_launcher_background.xml
reviewed | app/src/main/res/values/prefkeys.xml
reviewed | app/src/main/res/values/strings.xml
reviewed | app/src/main/res/values/styles.xml
reviewed | app/src/main/res/xml/changelog_master.xml
reviewed | app/src/main/res/xml/preferences.xml
reviewed | app/src/main/res/xml/preferences_notifications.xml
reviewed | app/src/main/res/xml/preferences_ui.xml
reviewed | app/src/main/res/xml/shortcuts_add_reminder.xml
reviewed | app/src/main/res/xml/shortcuts_main.xml
reviewed | app/src/test/java/felixwiemuth/simplereminder/util/DateTimeUtilTest.kt
reviewed | app/src/test/java/felixwiemuth/simplereminder/util/TimeMatcherTest.kt
reviewed | build.gradle
reviewed | generateOpenSourceLicensesFile.sh
reviewed | gradle.properties
skipped  | gradle/wrapper/gradle-wrapper.jar | binary JAR
reviewed | gradle/wrapper/gradle-wrapper.properties
reviewed | gradlew
reviewed | gradlew.bat
reviewed | metadata/de/title.txt
reviewed | metadata/en-US/changelogs/11.txt
reviewed | metadata/en-US/changelogs/12.txt
reviewed | metadata/en-US/changelogs/7.txt
reviewed | metadata/en-US/changelogs/9.txt
reviewed | metadata/en-US/changelogs/908700.txt
reviewed | metadata/en-US/changelogs/909700.txt
reviewed | metadata/en-US/changelogs/910700.txt
reviewed | metadata/en-US/changelogs/911700.txt
reviewed | metadata/en-US/changelogs/912700.txt
reviewed | metadata/en-US/changelogs/913700.txt
reviewed | metadata/en-US/changelogs/914700.txt
reviewed | metadata/en-US/changelogs/915700.txt
reviewed | metadata/en-US/full_description.txt
skipped  | metadata/en-US/images/icon.png | binary PNG
skipped  | metadata/en-US/images/phoneScreenshots/1_AddReminder_v0.9.10.png | binary PNG
skipped  | metadata/en-US/images/phoneScreenshots/2_RemindersList_v0.9.10.png | binary PNG
skipped  | metadata/en-US/images/phoneScreenshots/3_Notification_v0.9.10.png | binary PNG
skipped  | metadata/en-US/images/phoneScreenshots/4_AddReminderCalendar_v0.9.10.png | binary PNG
skipped  | metadata/en-US/images/phoneScreenshots/5_AddReminderDaysAhead_v0.9.10.png | binary PNG
reviewed | metadata/en-US/short_description.txt
reviewed | metadata/en-US/title.txt
reviewed | settings.gradle
```
