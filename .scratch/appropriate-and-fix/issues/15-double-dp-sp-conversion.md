# 15 — Fix the time header being scaled twice

Type: task
Status: resolved
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

## Resolution (2026-09-05)

**The unit is sp.** It is text, so it scales with the user's system font size, which is
what a text size preference should do. The number is now applied with
`setTextSize(TypedValue.COMPLEX_UNIT_SP, ...)` for the hour, minute and separator and for
the AM/PM labels, and nothing on that path calls `applyDimension` any more. The unit is
said out loud in three places: the preference title is "Time display size (sp)", the
Javadoc on `Prefs.getReminderDialogTimePickerTextSize` says sp and says why, and the
comment at the read site in `ReminderDialogActivity` says what the double scaling was.

**The range is 8..96 sp**, `MIN_TIME_PICKER_TEXT_SIZE_SP` and
`MAX_TIME_PICKER_TEXT_SIZE_SP` in the new `app/ding/data/TimePickerTextSizeSetting.kt`.
8 because the digits are also the buttons that switch between hour and minute selection,
so a too-small header is a too-small touch target; 96 because the platform's own header
is 60sp and 96 still fits a small screen at the largest system font size. The decision is
one pure function, `timePickerTextSizeFromStored`, with nine JVM tests, used by both the
settings validation and the read — so a size the editor accepts is a size the dialog
uses. A stored value that is of another type, absent, empty, not a whole number or out of
range falls back to the default and is repaired in storage once, the same defensive shape
ticket 14 gave the nag interval. The old read was `Integer.parseInt` of whatever was
there, which threw `NumberFormatException` out of the opening dialog.

**The default moved from 12 to 30 sp.** 12 was never a size: under the double scaling it
came out to 12 x density sp, about 31sp on this 420 dpi screen, and something different on
every other screen. 30 is half the platform's 60sp header, which is what the old
recommendation actually rendered as on a typical phone, so the dialog looks the same as
before on a normal device while the number now means the same thing everywhere.

**The 16 pixel bottom margin is now 16dp**, converted once with
`applyDimension(COMPLEX_UNIT_DIP, 16f, displayMetrics)`. No dimen resource: this margin is
set on a platform view found by id, not from a layout of ours, so there is no XML side to
keep it consistent with.

### Measured on the emulator

`bench-pixel6-aosp`, API 36, pure AOSP, physical density 420, system font size 1.0.
Bounds are the `android:id/hours` node from `adb shell uiautomator dump`, in pixels.

| Build | Density | Stored size | Bounds | Height |
|---|---|---|---|---|
| before (base `fcdf7d7`) | 420 | 12 | `[346,944][524,1055]` | 111 |
| before (base `fcdf7d7`) | 280 | 12 | `[413,1054][531,1103]` | 49 |
| after | 420 | 30 | `[347,934][525,1040]` | 106 |
| after | 420 | 60 | `[337,882][515,1092]` | 210 |
| after | 280 | 30 | `[411,1037][529,1108]` | 71 |
| after | 280 | 60 | `[405,1002][523,1142]` | 140 |
| after, customization off (platform's own 60sp header) | 420 | — | `[337,817][515,1027]` | 210 |

The ratios are the point:

- **Before, the bug is visible as a squared density.** 111 / 49 = 2.27 between 420 and 280
  dpi, where the density ratio is 420 / 280 = 1.5. 1.5 squared is 2.25.
- **After, doubling the size doubles the header.** 210 / 106 = 1.98 at 420 dpi,
  140 / 71 = 1.97 at 280 dpi.
- **After, the same size across densities is the density ratio, not its square.**
  71 / 106 = 0.670 and 140 / 210 = 0.667, against 280 / 420 = 0.667.
- **60 sp lands exactly on the platform's own header**, 210 px both ways, which is the
  independent check that the conversion is now the one the platform does.

An unusable stored value was tried on the device as well (`ui_time_picker_text_size` set
to `not-a-number`): the dialog opened at 106 px — the default — `Prefs` logged "Stored
time display size is not a whole number of 8..96 sp ...", and the preferences file held
`30` afterwards. Before this change that value crashed the dialog.

Screenshots: `/tmp/ding-review/shot-420-30.png`, `shot-420-60.png`, `shot-280-30.png`,
`shot-280-60.png`, with `shot-before-420-12.png`, `shot-before-280-12.png` and
`shot-stock-420.png` for comparison. Density was reset with `adb shell wm density reset`
afterwards.

### Left alone

- **The AM/PM labels could not be seen.** The dialog calls `setIs24HourView(true)`
  unconditionally (`ReminderDialogActivity.kt:236`), so the picker never shows them,
  whatever the system 12/24-hour setting says. They get the same `COMPLEX_UNIT_SP` fix as
  the header, verified by reading the code rather than by looking at it. That the dialog
  ignores the user's 12/24-hour preference is a separate finding, not this ticket's.
- **The clock height preference** (`getReminderDialogTimePickerHeight`) is still a bare
  `Integer.parseInt` with no range, and so can still throw on a hand-edited preferences
  file. It is a genuine dp length applied to a layout parameter, so it has no unit bug;
  bounding it is the same shape of work as this and belongs in its own ticket.

