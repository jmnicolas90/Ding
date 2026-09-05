# 31 — Say so when an effect failed, instead of reporting a clean success

Type: task
Status: open
Blocked by: —

## Question

Codex review finding on ticket 22 (high), job `review-mtohwk7v-i9g48x`,
`app/src/main/java/app/ding/state/ReminderCommandRunner.kt`.

Ticket 22 made the runner carry out each effect on its own, so one that throws
no longer stops the ones behind it. What it did not do is tell anybody: a
failed effect is reported to the failure reporter, which logs it, and the
command still returns its ordinary outcome. For a sweep that is the right
answer — there is no user in front of it, and the next process start tries
again. For an interactive command it is not. If `SetAlarm` throws on an Add or
a Reschedule — a `SecurityException` on API 31 or 32 where exact-alarm access
can be revoked is the concrete case — the reminder is stored `SCHEDULED` with
an empty alarm slot, while the dialog gets `TransitionOutcome.Updated`, reports
success and closes. Nothing retries until the next process start, so a process
that stays alive past the due time misses the reminder with no sign to the
user. That contradicts invariant 1 of `docs/reminder-state-machine.md`, which
says a `SCHEDULED` reminder holds an alarm at its due time after any command.

This is not a regression from ticket 22 — before it, the same failure threw out
of the runner, so the alarm was equally missing and the app crashed on top of
it — but it is the half of the isolation that was left standing.

Every change, nothing more:

1. **The result says what failed.** Carry the effects that could not be carried
   out in `CommandResult` and `ReconcileResult`. It has to stay a change the
   compiler forces every caller to answer, the way `PersistenceFailed` is:
   returning a success that quietly holds a list nobody reads would be the same
   bug one level up. Decide between a wrapper around the outcome and a case of
   its own, and say in the ticket which and why.
2. **The interactive callers.** `AddReminderDialogActivity` and
   `EditReminderDialogActivity` go through ticket 24's `finishAfterChange` /
   `finishWithoutChange`. A stored change whose alarm was not set is neither:
   the reminder exists, so the store did change, but the user must not be told
   it is set for a time it will not fire at. Work out what the dialog says and
   which result it returns. Reuse the existing "could not save" wording only if
   it is honest here; a reminder that was saved and not scheduled is a
   different thing.
3. **The sweep keeps its behaviour.** `reconcileAllReminders` has no user, so
   it logs and carries on exactly as it does now. Only the type changes.
4. **Tests, plain JVM**, on the fake executor ticket 22 added: an Add whose
   `SetAlarm` throws reports the failure rather than a clean `Updated`; a
   Reschedule likewise; a sweep with one failing effect still answers
   `Reconciled` and still carries the failure; and a command whose effects all
   succeed carries none.
5. **`docs/reminder-state-machine.md`.** Step 6 of the runner's steps and
   invariant 1 both need a sentence: what the runner promises when an effect
   fails, and that the invariant holds after a *successful* command.

Not in scope: retrying the failed effect, whether by an immediate second
attempt or by a scheduled one — decide that in its own ticket once this one has
made the failure visible. Also not in scope: the notification-permission case,
which is ticket 29, and the exact-alarm one, which is ticket 30.

**Done when** no interactive command can report a clean success after an effect
failed, the gate is green, and the tests above fail with the change reverted.
