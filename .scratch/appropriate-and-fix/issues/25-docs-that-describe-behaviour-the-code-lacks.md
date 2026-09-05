# 25 — Fix the docs that describe behaviour the code does not have

Type: task
Status: resolved
Blocked by: —

## Question

Global review findings (UI and docs axis, high number 8 and mediums 5 and 9).
See `../reviews/2026-09-05-global-review-ui-docs.md`. Three places where the
words are wrong and following them hurts the user or misleads an agent.

**The time syntax is documented backwards.** `README.md` line 7 says "absolute
(`12:30`) or relative (`+1:30`)", and `app/src/main/res/raw/help.html` says
the same. In `TextBasedTimeInput.kt` the dot is the absolute separator and the
colon the relative one, so `12:30` means twelve and a half hours from now. A
user following the README schedules a reminder hours late. Decision: the docs
change, not the parser; the parser is what existing users' fingers know and
what the upstream help taught. Read the parser and its tests first and state
the real syntax, including what `+` does, in both places.

**The in-app test instruction now guarantees a refusal.** The help page says
to add a reminder without changing the date or time and it will show
immediately. The dialog opens on the current minute and ticket 10's Add refuses
a due time that is not in the future, so the instruction produces the "must be
in the future" toast. Tell the user to pick a minute a little ahead.

**The notification action button does not exist.** `CLAUDE.md` line 136,
`CONTEXT.md` line 44 and the command table in `docs/reminder-state-machine.md`
say a reminder is marked done by swiping the notification, "its action button",
or the list. The notification has a content intent that opens the edit dialog
and a delete intent for the swipe, and no action. Decision: the docs say what
exists, swipe or list; a visible mark-done action is a feature for the next
map, note it in `docs/upstream-planned-features.md` or a new list next to it.

Also check `about.html`, `help.html` and the README for any other sentence
that describes a behaviour ticket 10 to 15 changed; fix what you find and list
it in the resolution.

**Done when** the three passages match the code, an agent reading `CLAUDE.md`
and `CONTEXT.md` is not told about an action that is not there, and G1 to G6
are green (docs only, but the gate is the rule).

## Resolution (2026-09-05)

The docs now say what the code does. Nothing under `app/src/main/java` changed,
and no user-visible string changed either: this is `README.md`, the two HTML
pages, `CLAUDE.md`, `CONTEXT.md`, the state machine document, and one new file.

**The time syntax, read off `TextBasedTimeInput.kt` and `TimeMatcherTest.kt`.**
The dialog builds its matcher with `separatorAbsoluteTime = "."`,
`separatorRelativeTime = ":"` and `prefixRelativeTime = "\\+"`, so there are
five accepted forms, tried in this order: `+h[hh]:mm` and `+m[mm]` are counted
from now; `h[hh]:mm` and `:m[mm]` are the same two without the plus and are also
counted from now; `h[h].mm` is a clock time, hours 0 to 23. The minutes are two
digits whenever hours are given, which is why `1:2` and `12.3` match nothing,
and the match has to start the text and end at a space or the end of the input.

- `README.md` step 2 said: "Enter a message and pick a time — absolute
  (`12:30`) or relative (`+1:30`)". Both examples were relative, so a user who
  wanted half past twelve got a reminder twelve and a half hours late. The steps
  now say to set the time on the clock, and a paragraph after them gives the
  typed syntax with the rule in one line — the dot is a clock time, the colon is
  a delay from now — and the four examples `12.30`, `12:30`, `+90`, `:90`. It
  also says the feature is off until "Enable text-based time input" is switched
  on in the settings, which the README never mentioned.
- `help.html`, "Specifying time via text", listed three formats and gave the
  colon to the absolute one: "h[h]:mm for setting the time (24 hour format)".
  All five forms are now listed with an example each, the colon form is marked
  as *not* half past twelve, and the two-digit-minute rule is stated. The
  sentence about combining widgets and text said to "independently specify the
  time via the "hh:mm" format" and that only "+..." resets previous choices; it
  now names `hh.mm` for that and says that any colon form, plus or no plus, sets
  the date as well.
- `README.md` step 3 said "Tap OK". The add dialog's button is "Add"
  (`button_add_reminder`); OK is the edit dialog's. Corrected, along with
  "Add Reminder" in step 1 and in the feature list, where the launcher label is
  "Add reminder".

**The test instruction.** `help.html` said: "To **test** how a reminder is
shown, add a reminder without changing the date or time. The reminder will be
shown immediately." The dialog opens on the current minute with the seconds cut
off, so its due time is a few seconds in the past, and ticket 10's Add refuses a
due time that is not in the future. The tip now says to set the time one or two
minutes ahead, and names the refusal ("Invalid date: must be in the future") so
a user who hits it knows what happened.

**The notification action button.** Confirmed in `ReminderManager.sendNotification`
before touching the words: the builder sets a content intent (the edit dialog), a
delete intent (the `MarkDone` action, which is what a swipe sends) and no
`addAction` at all — there is no `addAction` call anywhere in the app's Kotlin or
Java. Three places claimed otherwise:

- `CLAUDE.md`: "**Mark done** is the transition to `DONE`, whether by swiping the
  notification away, its action button, or the list." Now: by swiping the
  notification away or from the reminders list, plus one sentence saying the
  notification has no such button and clicking it opens the edit dialog, and a
  pointer to the feature list.
- `CLAUDE.md`, the id paragraph: "the mark-done action from a notification uses
  `id + 1`". True but readable as the button; now "the mark-done broadcast that a
  swiped-away notification sends".
- `CONTEXT.md`, the **Mark done** entry: same false clause, same fix, with the
  edit click named so the entry says what clicking does instead.
- `docs/reminder-state-machine.md`: the command table's MarkDone row said
  "Notification swipe or action, list multi-select" and now says "Notification
  swipe, list multi-select"; "Swipe-away and explicit done are the same
  transition" now names the two paths that exist; and "Mark done from a
  notification uses request code id + 1" takes the same wording as `CLAUDE.md`.

**The feature is written down rather than dropped.** `docs/planned-features.md`
is new: the fork's own list, next to `upstream-planned-features.md`, which is
upstream's verbatim text and could not take an entry of ours. Its first and only
entry is a mark-done button on the notification, with what already exists
(`ReminderAction.MarkDone`, request code `id + 1`, the runner) and what is
missing (`addAction`, a string, a test).

**Also found while re-reading the pages.**

- `help.html` gave no bound for the nag interval; ticket 14 made it 1 to 1440
  minutes and the number picker enforces it, so the sentence about long-clicking
  the switch now says the range.
- `help.html` never said how to mark a reminder done. A short "Marking a reminder
  done" section now says swipe or the list's "Mark done", and that clicking the
  notification edits instead.
- `README.md`'s feature list gained a line for marking done, and its "Reschedule
  or edit a reminder from its notification" line now says tapping it, which is
  the gesture.

Left alone, deliberately:

- The parser. The decision was that the docs move, not the code; `12:30` still
  means twelve and a half hours from now.
- `about.html`. Every sentence in it still holds: the fork statement, the licence,
  the known problem about late or missed reminders on some devices, and the
  contact channel. Its planned-features link is commented out and stays that way.
- `preference_reminder_dialog_timepicker_show_keyboard_button_descr` in
  `strings.xml` still says "Soon it will be possible to type the time directly in
  the description field", which has been possible for some time. It is a
  user-visible string rather than documentation, so changing it is a string
  change with a translation side to it, and this ticket touched no strings.
- The 24-hour claim in the help is correct today only because the dialog forces
  `setIs24HourView(true)`. That is review finding 7 and its own open finding; if
  the picker ever follows the device's preference, the help's "24 hour format"
  and the parser's `0..23` hour range both have to be revisited.

## Review findings (2026-09-05)

- **`TimeMatcher` left the escaping to its callers, and production did not do it**
  (high) — fixed. Inherited from upstream: the class arrived with the text-based time
  input feature in `fbe4fe4`, before the fork, already interpolating
  `separatorAbsoluteTime`, `separatorRelativeTime` and `prefixRelativeTime` straight
  into its regexes, with a "this is used in a [Regex] and must be escaped accordingly"
  note on each of the three constructor parameters. `TimeMatcherTest` honoured the
  note and passed `"\\."`; `ReminderDialogActivity` did not and passed `"."`. An
  unescaped dot in a pattern means "any character", so with typed time input switched
  on, the absolute form accepted *any* single character as its separator. `12345 call`
  was read as 12:45, `12a30 call` and `12 30 call` as 12:30, and because the match ran
  from the start of the text the first five characters were cut out of the message in
  each case. The tests could not catch it: they were escaping a separator the app never
  used, so they exercised a matcher the user never got.

  This ticket's first round wrote docs saying the dot is the clock-time separator. The
  fix makes the code true to those words rather than the other way round. **The
  quoting moved inside `TimeMatcher`**: three private `Regex.escape` values feed the
  patterns, so the constructor now takes the literal text to look for and the "must be
  escaped" contract — and the trap it set for the one caller that took it at face
  value — are gone. The doc comments say "as the literal text to look for" with an
  example each, and the second parameter's comment no longer says "absolute" where it
  means relative. `ReminderDialogActivity` passes `"."`, `":"` and `"+"`; the tests
  pass the same plain characters.

  Written red first: three tests in a new "Production separators are literal
  characters" context, built with exactly the separators the dialog passes, asserting
  that `12345 call`, `12a30 call` and `12 30 call` are not times. All three failed
  against `TimeMatch(rangeLast=4, isRelative=false, hour=12, minute=45)` and
  `...minute=30)`, which is the bug in the review's words. Four positives sit next to
  them — `12.30 call`, `12:30 call`, `+90 call`, `:90 call` — so the negatives are read
  against the four documented forms the same matcher must still accept. 176 unit tests
  green, the 169 that existed before included and unchanged in meaning.

  Nothing else about the parser changed: the accepted forms, the hour and minute
  ranges, the three-digit limit, the end-of-match rule and the order the forms are
  tried are all as they were. The "same separators" case still turns off the two
  unprefixed relative forms, and the comparison that decides it is on the literal
  strings, so it is unaffected. The separators are still hardcoded at the construction
  site behind upstream's `// TODO initialize with user-chosen symbols`; making them a
  setting is a feature, not this fix, and it is now safe to do, since a user-chosen
  symbol can no longer be read as a pattern.
