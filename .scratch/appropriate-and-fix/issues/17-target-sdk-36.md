# 17 — Raise targetSdk to 36 and verify on the AOSP emulator

Type: task
Status: open
Blocked by: 02

## Question

`targetSdk` is 34 while `compileSdk` is 36. On an Android 16 phone the app is opting *out* of two years of platform behaviour — edge-to-edge enforcement, background and foreground-service restrictions, notification changes.

Deliberately separate from ticket 02. Raising `minSdk` **deletes** code and cannot change runtime behaviour on a supported device. Raising `targetSdk` does the opposite: it opts the app into new platform behaviour and can visibly break layout and background work. Bundled, a breakage would be impossible to attribute.

Set `targetSdk 36`, then verify — this one genuinely needs looking at, not just a green gate:

- Edge-to-edge: the reminder dialog and the list activity both draw their own backgrounds and are the likely casualties.
- Exact alarms under the current permission model.
- Notification posting and the notification actions.
- The Quick Settings tile.

Verify on `bench-pixel6-aosp` — API 36, pure AOSP system image, Pixel 6 profile, already configured on this machine. That image is the closest available proxy for the GrapheneOS Pixel 6 this app must keep working on.

**Done when** the gate is green and the four areas above have been exercised on the AOSP emulator with a note on what was checked.
