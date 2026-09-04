# 15 — Fix the time header being scaled twice

Type: task
Status: open
Blocked by: —

## Question

Review finding (medium), `ReminderDialogActivity.kt:675-693`.

The configured time-text size is converted from dp to pixels with `TypedValue.applyDimension`, then assigned through `TextView.textSize` — whose setter interprets the number as **sp** and scales it again. On a high-density device the time header comes out several times larger than the user asked for. The AM/PM label has the same bug.

Fix:
- Call `setTextSize(TypedValue.COMPLEX_UNIT_SP, configuredSize)` directly, or use the two-argument `COMPLEX_UNIT_PX` overload with a genuine pixel value. One or the other, not both.
- Pick and document **one** unit for the preference. The bug exists because the unit is ambiguous.
- The hardcoded `16` bottom margin is a raw pixel value and should be converted to dp too.

Visual, so it needs looking at rather than only testing. The `bench-pixel6-aosp` emulator (API 36, pure AOSP, Pixel 6) is on this machine; check the rendered size at more than one density and at more than one configured value.

Independent of everything else on this map.

**Done when** the header renders at the configured size on at least two densities, and the preference documents its unit.
