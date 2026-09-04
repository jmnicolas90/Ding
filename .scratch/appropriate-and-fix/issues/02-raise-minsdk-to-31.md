# 02 — Raise minSdk to 31 and delete the pre-31 compatibility branches

Type: task
Status: open
Blocked by: —

## Question

`minSdkVersion 21` (Android 5.0, 2014) is inherited ambition, not a requirement. Both target devices run Android 16 and 17. Android 12 (API 31) still serves a broad public audience.

This is the highest-leverage ticket on the map because it **deletes** problems rather than fixing them:

- `:app:lintDebug` currently fails with exactly one error — `AndroidManifest.xml:86: QuickTileService requires API level 24 (current min is 21) [NewApi]`. Raising `minSdk` to 31 makes it vanish; no version-gating, no `values-v24` boolean, no `tools:targetApi` suppression needed. This is the whole of the review's "Android lint fails on the Quick Settings service API level" finding.
- Three separate `_API31` / `_API33` variants of the battery-optimization advice strings collapse.
- Notification channels and `POST_NOTIFICATIONS` become unconditional.
- `SCHEDULE_EXACT_ALARM android:maxSdkVersion="32"` and its `USE_EXACT_ALARM` counterpart can be reasoned about without a 21-to-32 branch.

Set `minSdkVersion 31`, then sweep for now-dead code: `Build.VERSION.SDK_INT` comparisons below 31, `@RequiresApi` / `@TargetApi` annotations below 31, and the string variants above. Delete rather than leave unreachable.

Do **not** touch `targetSdk` here — that is ticket 17, and bundling them would make any breakage impossible to attribute.

**Done when** `./gradlew :app:lintDebug :app:testDebugUnitTest :app:assembleDebug` is green, and no `SDK_INT` check below 31 remains.
