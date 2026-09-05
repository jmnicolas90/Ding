# 25 — Fix the docs that describe behaviour the code does not have

Type: task
Status: open
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
