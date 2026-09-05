# 14 — Fix the nag-interval overflow and bound the input

Type: task
Status: resolved
Blocked by: —

## Question

Review finding (medium), `Reminder.kt:90-93`. Confirmed in the source:

```kotlin
val naggingRepeatIntervalInMillis: Long
    get() = (60 * 1000 * naggingRepeatInterval).toLong()
```

`60 * 1000 * naggingRepeatInterval` is `Int` arithmetic, converted to `Long` only afterwards. From 35,792 minutes upward it overflows, and both the settings input and the `NumberPicker` accept values up to `Int.MAX_VALUE`. The resulting negative or zero duration can schedule an alarm in the past — or make `% d` in `calculateNextNagTime()` throw division by zero.

Fix:
- `60_000L * naggingRepeatInterval`.
- Enforce a documented upper bound in the model **and** in both input paths. Pick a bound that means something to a user — a nag interval of 24 days is not a real use case, and a small bound is easier to defend than `Int.MAX_VALUE`.
- Boundary tests at 35,791 and 35,792, and at whatever maximum is chosen.

Small, self-contained, independent of the state machine. Good ticket for a session with little context.

**Done when** the multiplication is in `Long`, the bound is enforced at every entry point, and the boundary tests pass.

## Resolution (2026-09-05)

The multiplication is in `Long` and the interval a reminder may hold is bounded, at
the model and at both ways a value gets in.

**The conversion.** `Reminder.nagIntervalInMillis(minutes)` is `60_000L * minutes`,
and `naggingRepeatIntervalInMillis` is that function applied to the reminder's own
interval. It is a named function rather than only a property so the two overflow
boundaries can be tested directly on values the model refuses to hold.

**The bound: 1 to 1440 minutes**, `Reminder.MIN_NAGGING_REPEAT_INTERVAL` and
`Reminder.MAX_NAGGING_REPEAT_INTERVAL` in the model's companion. 24 hours, because a
nag that repeats less often than once a day is not a nag, it is a second reminder.
0 still means nagging is disabled; what is now refused is a negative value, which
used to be a second way of writing "disabled", and anything above the bound.

**Enforced in four places**, so a value outside it cannot reach the arithmetic:

- `Reminder`'s `init`, next to the id check. Construction fails, as it does for a
  bad id.
- The settings preference for the default interval (`SettingsFragment`): the change
  listener refuses anything that is not a whole number in range, with the existing
  toast, whose text now states the range.
- The number picker in the reminder dialog (`ReminderDialogActivity`): `minValue`
  and `maxValue` come from the constants instead of 1 and `Int.MAX_VALUE`.
- Reading that preference (`Prefs.getNaggingRepeatInterval`): a stored value an
  older build wrote, or one edited by hand, that is not a number or is outside the
  bound is passed over — the default of 1 minute is used and the fact logged — so
  old preferences cannot trip the model's `require`.

**A stored reminder outside the bound is quarantined**, not clamped: ticket 13's
decoding turns the failed construction into `Unreadable(INVALID_REMINDER, raw)`, and
the reminders list offers the raw JSON to share or discard. That is the right
outcome for this field as well. The store is the source of truth and the bound is
part of the model, so a value the model refuses is not something to rewrite behind
the user's back; the raw value still holds the text and the due time.

**Arithmetic checked.** `nextNagTime` in `ReminderTransition.kt` is the only place
the interval is computed with — there is no `calculateNextNagTime` left, ticket 10
replaced it — and its `Math.floorMod` divides by the interval, so a zero or negative
one was a division by zero or an alarm in the past. It already requires the reminder
to be nagging; with the bound, the divisor is now between one minute and 24 hours,
and 24 hours of milliseconds is far inside `Long`. Alarm scheduling never touches
the interval: it is handed an absolute time.

**Tests.** New `app/src/test/java/app/ding/data/ReminderTest.kt`: the conversion at
35,791 and 35,792 minutes (the last Int-safe product and the first that used to
wrap), the model accepting 0, 1 and 1440 and refusing -1, 1441 and `Int.MAX_VALUE`,
and 1440 minutes as 86,400,000 milliseconds. `ReminderTransitionTest` gains the
next-nag computation at 1440, and `StoredReminderDecodingTest` a stored reminder
whose interval is past the bound, quarantined rather than crashing.

Left out: the `EditTextPreference` keeps its plain number input rather than gaining
an input filter, since the change listener already refuses out-of-range values, and
its summary still shows the current interval rather than the range.

## Review findings (2026-09-05)

- **The stored nag interval could still throw on read, and an unusable one stayed in
  storage** (medium) — fixed. `Prefs.getNaggingRepeatInterval` read the preference before
  the try block, so a value of another type threw `ClassCastException` out of the reminder
  dialog and out of the settings summary, unlogged; and an unusable value returned the
  default without replacing what was stored, so the settings editor kept showing text the
  app was not using and every later read logged the same complaint. The decision is now
  `naggingRepeatIntervalFromStored` in `app/src/main/java/app/ding/data/NagIntervalSetting.kt`:
  no Android imports, the read passed in as a function so a `ClassCastException` on it is
  one more unusable value, answering with the interval or null. `Prefs` logs once, writes
  the default back with `commit()` — checking the result and logging when the write fails —
  and returns it, so the summary and the number picker then show one minute. 0 is unusable
  for *this* preference rather than "nagging off": nagging is switched on separately and the
  picker's minimum is one minute, so a stored 0 would arm the switch with an interval that
  never nags. New `app/src/test/java/app/ding/data/NagIntervalSettingTest.kt` covers a value
  inside the bound, a value of another type, nothing stored, empty, not a whole number, "0",
  "-1", "1441" and `Int.MAX_VALUE`, and that the read is asked for once. The write-back
  itself has no JVM seam in this build — there is no Robolectric — so that step is Android
  code covered by inspection, not by a test.
