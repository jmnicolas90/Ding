# The reminder state machine

Decided on 2026-09-04 in ticket 09 of the appropriation map
(`.scratch/appropriate-and-fix/issues/09-define-reminder-state-machine.md`).
Tickets 10 to 13 are implemented against this document, and tests are
written from it directly. Vocabulary is in `CONTEXT.md`; the architectural
choice behind it is `docs/adr/0001-pure-transition-function.md`.

## Why this exists

Reminder state was owned by three places at once: the application's startup
sweep, the alarm receiver, and the storage object. Each could reconcile,
deliver, edit or persist the same reminder, and `status` was a mutable field
any caller could set. The reported bugs (a cold alarm delivering twice, a
failed write reported as success, a saved reminder left scheduled in the
past, a nag chain outliving its reminder) are all symptoms of that. This
document puts every change of reminder state behind one transition
function.

## States

A reminder is in exactly one of three states.

| State | Meaning | Alarm slot | Notification |
|---|---|---|---|
| `SCHEDULED` | Not yet delivered. | Holds a Deliver alarm at the due time. | None. |
| `NOTIFIED` | Delivered. The user has not dealt with it. | Holds a Nag alarm if the reminder nags, otherwise empty. | On screen (or requested). |
| `DONE` | Finished. Resting state, can be re-armed by Reschedule. | Empty. | None. |

"Overdue" is not a state. A `SCHEDULED` reminder whose due time has passed is
one that Reconcile delivers on sight. Swiping the notification away and marking
a reminder done in the list are the same transition.

Each reminder owns one **alarm slot**: the `AlarmManager` pending intent
whose request code is the reminder id. Deliver and Nag both live in that
slot, so setting one replaces the other. The mark-done broadcast that a
swiped-away notification sends uses request code id + 1 and is not an alarm.

## Commands

A command is a request to change one reminder. Every command carries the
reminder id, except Add, which creates one.

| Command | Payload | Issued by |
|---|---|---|
| `Add` | text, due time, nag interval | Add dialog |
| `Deliver` | expected due time | Alarm receiver |
| `Nag` | expected due time | Alarm receiver |
| `MarkDone` | – | Notification swipe, list multi-select |
| `Reschedule` | new due time, text, nag interval | Edit dialog |
| `Edit` | text, nag interval | Edit dialog |
| `Delete` | – | List |
| `Reconcile` | – (applies to every stored reminder) | Application start |

Which of `Edit` or `Reschedule` the edit dialog issues is decided by the due
time, in every starting state (ticket 11). The dialog opens on the reminder's
stored due time whatever its state; leaving it alone is an `Edit`, moving it is
a `Reschedule`. The comparison is at minute precision, because that is
what the dialog's pickers show: a difference the user cannot see is not a
reschedule.

## Transition function

```
transition(stored: Reminder?, command: Command, now: Instant)
    -> Outcome × List<Effect>
```

Pure. No Android imports, no preferences, no clock of its own. `stored` is
the reminder as read from the store under the lock, or absent. Times are epoch
milliseconds in the implementation, matching the `Date` the store already
holds; nothing in the model depends on which of the two it is.

Outcomes:

- `Updated(reminder)` — write this reminder.
- `Removed` — delete the reminder.
- `Unchanged` — write nothing. Effects may still run (cleanup, re-show).
- `Refused(reason)` — write nothing, run nothing. Two reasons: `PastDue`, and
  `IdSpaceExhausted` for an Add when the store's id counter has passed the
  largest id a reminder may hold and there is no id left to give.

Effects, executed by the runner in order after a successful write:

- `SetAlarm(at, action)` — put `action` (Deliver or Nag, with its expected
  due time) in the alarm slot, replacing whatever is there.
- `CancelAlarm` — empty the alarm slot.
- `ShowNotification(kind)` — kind is `Deliver`, `Nag` or `Reshow`. Deliver
  and Nag alert; Reshow is silent. The runner decides from preferences
  whether to display the original due time, so preferences never reach the
  transition function.
- `CancelNotification` — remove the notification for this id.

## Transition table

"Stale" means the command's expected due time differs from the stored due
time, or the stored state cannot accept the command. A stale command is
always `Unchanged` with no effects: it is not an error.

The guards of a command are read in the order they appear.

| Command | Stored state | Guard | Outcome | Effects |
|---|---|---|---|---|
| Add | absent | no id left to give | Refused(IdSpaceExhausted) | – |
| Add | absent | due > now | Updated, `SCHEDULED` | SetAlarm(due, Deliver) |
| Add | absent | due ≤ now | Refused(PastDue) | – |
| Deliver | `SCHEDULED` | expected == due | Updated, `NOTIFIED` | ShowNotification(Deliver); SetAlarm(nextNag(now), Nag) if nagging |
| Deliver | `SCHEDULED` | expected ≠ due | Unchanged (stale) | – |
| Deliver | `SCHEDULED` | no expected due time, due ≤ now | Updated, `NOTIFIED` | as the Deliver row above |
| Deliver | `SCHEDULED` | no expected due time, due > now | Unchanged (stale) | – |
| Deliver | `NOTIFIED`, `DONE` | – | Unchanged (stale) | – |
| Nag | `NOTIFIED` | nagging and expected == due | Unchanged | ShowNotification(Nag); SetAlarm(nextNag(now), Nag) |
| Nag | `NOTIFIED` | nagging and no expected due time | Unchanged | as the Nag row above |
| Nag | anything else | – | Unchanged (stale) | – |
| MarkDone | `SCHEDULED`, `NOTIFIED` | – | Updated, `DONE` | CancelAlarm; CancelNotification |
| MarkDone | `DONE` | – | Unchanged | CancelAlarm; CancelNotification |
| Reschedule | any | new due > now | Updated, `SCHEDULED` with new due, text, nag | CancelNotification; SetAlarm(new due, Deliver) |
| Reschedule | any | new due ≤ now | Refused(PastDue) | – |
| Edit | `SCHEDULED` | due > now | Updated, same state | – |
| Edit | `SCHEDULED` | due ≤ now | Updated, same state | SetAlarm(due, Deliver) |
| Edit | `NOTIFIED` | – | Updated, same state | ShowNotification(Reshow); SetAlarm(nextNag(now), Nag) if nagging, else CancelAlarm |
| Edit | `DONE` | – | Updated, same state | – |
| Delete | any | – | Removed | CancelAlarm; CancelNotification |
| Reconcile | `SCHEDULED` | due ≤ now | as Deliver with expected = due | as Deliver |
| Reconcile | `SCHEDULED` | due > now | Unchanged | SetAlarm(due, Deliver) |
| Reconcile | `NOTIFIED` | – | Unchanged | ShowNotification(Reshow); SetAlarm(nextNag(now), Nag) if nagging |
| Reconcile | `DONE` | – | Unchanged | CancelAlarm; CancelNotification |
| any except Add | absent | – | Unchanged | CancelAlarm; CancelNotification |

`nextNag(now)` is the first multiple of the nag interval after now, counted from
the original due time, and never earlier than the due time plus one interval. A
delayed nag therefore never replays the occurrences it missed, and a clock set
back — which puts now before the due time of a reminder that has already been
delivered — does not put a nag before the reminder's own due time (ticket 23).
The interval bound is ticket 14.

Reconcile over a `DONE` reminder cancels its alarm and its notification rather
than doing nothing (ticket 23). MarkDone is persisted before its cancel effects
run, so a process that dies in between, or a cancel that fails, leaves a done
reminder with something still in its alarm slot or still on screen, and the next
start is the only thing that can repair it. Both cancels are idempotent, so doing
them for every done reminder costs nothing. That is what makes invariant 3 hold
after Reconcile and not only after MarkDone.

An Edit on a `SCHEDULED` reminder still ahead of now has no effects because the
alarm payload carries only the id and the due time; text and nag settings are
read from the store when the alarm fires. The one exception is a `SCHEDULED`
reminder already past due, whose delivery has not happened yet: there the Edit
puts the Deliver alarm back. That is what makes invariant 1 hold after every
command rather than only after Add, Reschedule and Reconcile — the unguarded row
contradicted it for that case (ticket 11). An alarm set for a past time fires at
once, so the delivery happens then instead of waiting for the next Reconcile, and
the status guard means it still happens only once.

## The stale-alarm rule

Every Deliver and Nag alarm carries the due time it was set for. On
arrival, the transition function compares it to the stored due time and
ignores the command on any mismatch. A Reschedule changes the due time, so
every alarm set under the old due time becomes stale by construction, even
one already in flight when the reschedule happened.

This costs nothing in the stored format: the due time is already stored. A
generation counter was considered and rejected because the only case it
adds is "rescheduled to the identical millisecond", which is
indistinguishable to the user.

## An alarm from an older build carries no due time

An alarm outlives the build that set it: after an upgrade, `AlarmManager` still
holds pending intents written by the old code, and builds from before this rule
put no due time in them. Such an alarm has nothing to compare against the store.
Calling it stale would lose its delivery for good, so "no due time carried" means
deliver if the reminder is still `SCHEDULED` and already due, nag if it is still
`NOTIFIED` and still nagging, and ignore otherwise. That is what Reconcile would
do for the same reminder, and the status guard still holds, so it cannot deliver
twice.

A payload the app cannot read at all — an unknown format, or an intent with no
payload — is not an error either. The alarm receiver logs it and runs Reconcile
instead of throwing, because throwing there crashes the app and loses the alarm,
while Reconcile brings every reminder's alarm and notification back in line with
the store, including the one that alarm was for.

## A missing reminder is not an error

An alarm can arrive for a reminder that is no longer stored: deleted while
the alarm was in flight, or never durably written. The transition function
treats absence as a normal input and answers with cleanup effects. The
runner logs it at warning level and does not throw. The alarm receiver
must never crash on this path.

The one place that reports a missing reminder to the user is the edit
dialog, which already shows a toast and closes.

## Where the nag chain ends

A Nag is legal only when the reminder is `NOTIFIED`, nags, and the alarm's
expected due time matches. MarkDone, Reschedule and Delete each empty the
alarm slot as an effect, and the guard covers the race where the cancel
loses. No nag chain can survive its reminder. Nagging has no time or count
bound of its own in this map; that is a feature decision.

## Reconcile

Runs once per process start, from `Main.onCreate`, before any component
including the alarm receiver. It applies `Reconcile` to every stored
reminder. Because it delivers a past-due `SCHEDULED` reminder itself, the
Deliver alarm that woke the process then finds the reminder already
`NOTIFIED` and is stale. That is the cold-start fix: one alert, one write.

## The runner

The transition function is wrapped by one entry point, the only public
mutation on `ReminderManager`:

1. Take the storage lock.
2. Set aside the stored value if the store reports it cannot be read, and stop
   with a persistence failure if that write does not commit. Reading never
   writes, so this is the only place the recovery happens, and the first
   command of a process is the startup Reconcile. The id counter is carried
   over in that same commit rather than emptied with the reminders, and every
   notification of the app's is cancelled once it commits (ticket 21).
3. Read the stored reminder (or all of them, for Reconcile).
4. Call the transition function with the current time.
5. On `Updated` or `Removed`, write the whole list and check the commit
   result. A failed commit releases the lock and returns a typed
   persistence failure. No broadcast, no effects.
6. On success, release the lock, broadcast the change, then execute the
   effects in order, each on its own: one that throws is reported to the
   runner's failure reporter — which on Android logs it at error level, with
   the reminder's id and not its text — and the ones after it still run
   (ticket 22). The ones that could not be carried out are kept as they fail.
7. Return the outcome to the caller, wrapped in `EffectsFailed(outcome,
   failedEffects)` when step 6 kept any (ticket 31). The bare outcome is the
   runner's promise that everything the command asked for happened; nothing
   less may be read as one, which is why the wrapper is a case of
   `CommandResult` and every caller has to answer it. A sweep answers
   `Reconciled(outcomes, failedEffects)` instead, a field rather than a case,
   because its one caller has no user in front of it and logs either way.

Persist first, then effects. A Deliver whose write succeeds but whose
notification is blocked by a missing permission leaves a `NOTIFIED`
reminder with nothing on screen; the next Reconcile re-shows it silently.
The reverse order can alert twice, or alert for a reminder that was never
saved.

One effect that fails does not take the rest of the list with it. A sweep's
effects are every reminder's alarms and notifications in one list, so an
exception escaping one of them would stop the app from restoring the alarms
of every reminder behind it — one notification that cannot be shown, and
nothing else fires either. The outcome the caller gets back still says what was
stored — the failure wraps it rather than replacing it, because the write did
happen — and the effects that failed travel with it, so a caller with a user in
front of it can report the reminder as saved without claiming it will go off.
The effect itself is the only thing lost, and only until the next Reconcile asks
for it again.

`Reminder` becomes an immutable value. `status` is a `val`. `Reminder.Builder`
survives only as the payload of `Add`. Storage mutation methods become
private to the runner; reading stays public for the list.

## Invariants

After any command that carried out its effects, and after such a Reconcile:

1. A `SCHEDULED` reminder has a Deliver alarm in its slot for its due time,
   and that time was in the future when it was written.
2. A `NOTIFIED` reminder has a notification requested, and its slot holds a
   Nag alarm if and only if it nags.
3. A `DONE` reminder has an empty slot and no notification.
4. For a given (id, due time), Deliver alerts at most once, regardless of
   how many alarms or Reconciles arrive.
5. `status` and due time change only through the transition function.

Invariant 1 lives in the transition function, not in any dialog. Add and
Reschedule are the only commands that write a due time and both refuse one that
is not in the future, so a `SCHEDULED` reminder past due can only be one whose
delivery has not happened yet; every other row either leaves the due time alone
or puts the Deliver alarm back. A dialog therefore never has to check the time
before saving — it only reports `Refused(PastDue)` to the user.

What the invariants do not cover is a command whose store change committed and
whose effects did not all run: a `SecurityException` from `AlarmManager` on API
31 or 32, where exact-alarm access can be revoked, is the concrete case, and it
leaves a `SCHEDULED` reminder with an empty alarm slot. The runner does not
pretend otherwise — that command answers `EffectsFailed` and the sweep carries
its failed effects — and the next Reconcile asks for the same alarm again, which
is when invariant 1 is true once more. Between the two, the add and edit dialogs
tell the user the reminder was saved and may not go off as set, rather than
showing the usual "due in ..." confirmation.

## Recurrence (deferred)

Recurring reminders ("every two weeks on Friday at 17:30") are the next
map's feature. This model is kept open for them, without implementing
anything:

- **Leaning:** the same reminder cycles. A recurrence rule becomes a
  property of the reminder, and `MarkDone` on a reminder with a rule
  behaves as `Reschedule` to the next occurrence after now, instead of
  moving to `DONE`. No new state, no new transition, one new branch inside
  MarkDone. A reminder that is ignored stays `NOTIFIED` (nagging if
  configured) and does not stack or re-fire behind the user's back.
- **Alternative still open:** a series spawns one-shot occurrences. Needs
  only `Add` and this machine, plus a parent entity and list grouping.
- **If the feature map wants re-delivery on every occurrence** whether or
  not the last was acknowledged, it adds a legal Deliver from `NOTIFIED`
  guarded on the due time having advanced. The stale-alarm rule already
  supports that.
- What keeps the door open: Reschedule is legal from `NOTIFIED` and `DONE`,
  the due time is the stale token, and the transition function takes `now`
  as an input.

## Tests to write from this document

All are plain JVM tests of the transition function with a fixed `now`,
unless marked otherwise.

1. **Cold start (ticket 10):** Reconcile on a past-due `SCHEDULED`
   reminder, then Deliver with the same due time. Exactly one
   `ShowNotification(Deliver)`, exactly one `Updated`.
2. Deliver with a stale due time: `Unchanged`, no effects.
3. Deliver on `NOTIFIED` and on `DONE`: `Unchanged`, no effects.
4. Deliver on a nagging reminder sets a Nag alarm at `nextNag(now)`.
5. Nag on `NOTIFIED` with matching due: re-show and next Nag alarm. Nag
   after MarkDone, after Reschedule, and after Delete: `Unchanged`.
6. MarkDone from each state; twice in a row is idempotent.
7. Reschedule from each state to a future time: `SCHEDULED`, notification
   cancelled, Deliver alarm set. To a past time: `Refused(PastDue)`.
8. Edit in each state without touching the due time keeps the state
   (ticket 11's three cases).
9. Edit on `NOTIFIED` that turns nagging off empties the slot.
10. Every command on an absent reminder: `Unchanged` plus both cancels.
11. Reconcile over a mixed store produces the per-state effects above.
12. **Runner (ticket 12):** a failed commit yields a typed failure, no
    broadcast, no effects. Needs an injectable store.
13. **Runner (ticket 13):** a store that fails to decode does not throw
    out of Reconcile and preserves the raw value.
14. Invariant check as a property: after any sequence of commands, the
    invariants above hold for the resulting store and effect log.
