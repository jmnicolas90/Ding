# 14 — Fix the nag-interval overflow and bound the input

Type: task
Status: open
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
