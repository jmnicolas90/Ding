/*
 * Copyright (C) 2026 Jean-Michel Nicolas
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package app.ding.state

import app.ding.data.Reminder
import app.ding.data.Reminder.Status
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import java.util.Date

/**
 * Tests of the transition function, written from the numbered list in
 * `docs/reminder-state-machine.md`. Plain JVM, fixed clock, no Android.
 */
class ReminderTransitionTest : FunSpec({

    test("1. cold start: reconcile then the delivered alarm alerts once and writes once") {
        // The bug this closes: an alarm wakes a dead process, Main.onCreate reconciles
        // and delivers the past-due reminder, and only then does the alarm that woke
        // the process arrive. Two alerts, two writes, one reminder.
        val dueTime = NOW - MINUTE
        val stored = reminder(dueTime = dueTime, status = Status.SCHEDULED)

        val reconciled = transition(stored, ReminderCommand.Reconcile(ID), NOW)
        val afterReconcile =
            (reconciled.outcome as? TransitionOutcome.Updated)?.reminder ?: stored
        val delivered = transition(afterReconcile, ReminderCommand.Deliver(ID, dueTime), NOW)

        val shown = (reconciled.effects + delivered.effects)
            .filterIsInstance<ReminderEffect.ShowNotification>()
        shown shouldHaveSize 1
        shown.single().kind shouldBe NotificationKind.DELIVER

        val writes = listOf(reconciled.outcome, delivered.outcome)
            .filterIsInstance<TransitionOutcome.Updated>()
        writes shouldHaveSize 1
        writes.single().reminder.status shouldBe Status.NOTIFIED
    }

    test("2. deliver with a due time the store no longer holds is stale") {
        val stored = reminder(dueTime = NOW + HOUR, status = Status.SCHEDULED)

        val result = transition(stored, ReminderCommand.Deliver(ID, NOW - HOUR), NOW)

        result shouldBe TransitionResult(TransitionOutcome.Unchanged)
    }

    test("3. deliver on an already delivered or finished reminder is stale") {
        val dueTime = NOW - MINUTE
        for (status in listOf(Status.NOTIFIED, Status.DONE)) {
            val stored = reminder(dueTime = dueTime, status = status)

            val result = transition(stored, ReminderCommand.Deliver(ID, dueTime), NOW)

            result shouldBe TransitionResult(TransitionOutcome.Unchanged)
        }
    }

    test("4. delivering a nagging reminder sets the next nag alarm") {
        val dueTime = NOW - 25 * MINUTE
        val stored = reminder(
            dueTime = dueTime,
            status = Status.SCHEDULED,
            naggingRepeatInterval = 10
        )

        val result = transition(stored, ReminderCommand.Deliver(ID, dueTime), NOW)

        val delivered = (result.outcome as TransitionOutcome.Updated).reminder
        delivered.status shouldBe Status.NOTIFIED
        result.effects shouldBe listOf(
            ReminderEffect.ShowNotification(delivered, NotificationKind.DELIVER),
            // Counted from the due time, not from now: 25 minutes late on a 10 minute
            // interval is 5 minutes to the next occurrence, and the two missed ones
            // are not replayed.
            ReminderEffect.SetAlarm(ID, NOW + 5 * MINUTE, AlarmKind.NAG, dueTime)
        )
    }

    test("5. nagging repeats while notified and stops once the reminder is dealt with") {
        val dueTime = NOW - 25 * MINUTE
        val notified = reminder(
            dueTime = dueTime,
            status = Status.NOTIFIED,
            naggingRepeatInterval = 10
        )

        val nagged = transition(notified, ReminderCommand.Nag(ID, dueTime), NOW)
        nagged shouldBe TransitionResult(
            TransitionOutcome.Unchanged,
            listOf(
                ReminderEffect.ShowNotification(notified, NotificationKind.NAG),
                ReminderEffect.SetAlarm(ID, NOW + 5 * MINUTE, AlarmKind.NAG, dueTime)
            )
        )

        val afterMarkDone =
            (transition(notified, ReminderCommand.MarkDone(ID), NOW).outcome
                as TransitionOutcome.Updated).reminder
        transition(afterMarkDone, ReminderCommand.Nag(ID, dueTime), NOW) shouldBe
            TransitionResult(TransitionOutcome.Unchanged)

        val newDueTime = NOW + HOUR
        val afterReschedule = (transition(
            notified,
            ReminderCommand.Reschedule(ID, newDueTime, notified.text, 10),
            NOW
        ).outcome as TransitionOutcome.Updated).reminder
        transition(afterReschedule, ReminderCommand.Nag(ID, dueTime), NOW) shouldBe
            TransitionResult(TransitionOutcome.Unchanged)

        // After Delete the reminder is gone, so the in-flight nag finds nothing and
        // cleans up instead of alerting.
        val afterDelete = transition(null, ReminderCommand.Nag(ID, dueTime), NOW)
        afterDelete.outcome shouldBe TransitionOutcome.Unchanged
        afterDelete.effects shouldBe bothCancels()
    }

    test("6. marking done from any state is the same, and doing it twice changes nothing more") {
        for (status in Status.entries) {
            val stored = reminder(dueTime = NOW - MINUTE, status = status)

            val first = transition(stored, ReminderCommand.MarkDone(ID), NOW)
            first.effects shouldBe bothCancels()
            val done = when (val outcome = first.outcome) {
                is TransitionOutcome.Updated -> outcome.reminder
                else -> stored
            }
            done.status shouldBe Status.DONE

            val second = transition(done, ReminderCommand.MarkDone(ID), NOW)
            second shouldBe TransitionResult(TransitionOutcome.Unchanged, bothCancels())
        }
    }

    test("7. rescheduling to the future re-arms from any state, to the past is refused") {
        val newDueTime = NOW + HOUR
        for (status in Status.entries) {
            val stored = reminder(dueTime = NOW - MINUTE, status = status)

            val result = transition(
                stored,
                ReminderCommand.Reschedule(ID, newDueTime, "Call the plumber", 0),
                NOW
            )

            val rescheduled = (result.outcome as TransitionOutcome.Updated).reminder
            rescheduled.status shouldBe Status.SCHEDULED
            rescheduled.date shouldBe Date(newDueTime)
            rescheduled.text shouldBe "Call the plumber"
            result.effects shouldBe listOf(
                ReminderEffect.CancelNotification(ID),
                ReminderEffect.SetAlarm(ID, newDueTime, AlarmKind.DELIVER, newDueTime)
            )

            val refused = transition(
                stored,
                ReminderCommand.Reschedule(ID, NOW - MINUTE, "Call the plumber", 0),
                NOW
            )
            refused shouldBe TransitionResult(TransitionOutcome.Refused(RefusalReason.PastDue))
        }
    }

    test("8. an edit that leaves the due time alone keeps the state in each of the three states") {
        for (status in Status.entries) {
            // Each state with a due time it plausibly has: ahead for a scheduled
            // reminder, behind for one that has been delivered or finished.
            val dueTime = if (status == Status.SCHEDULED) NOW + HOUR else NOW - 25 * MINUTE
            val stored = reminder(dueTime = dueTime, status = status, naggingRepeatInterval = 10)

            val result =
                transition(stored, ReminderCommand.Edit(ID, "Water the ferns", 10), NOW)

            withClue(status.toString()) {
                val edited = (result.outcome as TransitionOutcome.Updated).reminder
                edited.status shouldBe status
                edited.date shouldBe Date(dueTime)
                edited.text shouldBe "Water the ferns"
                edited.naggingRepeatInterval shouldBe 10
                result.effects shouldBe when (status) {
                    // The notification is on screen and has to show the new text; the
                    // nag alarm is reset because the settings it was set from changed.
                    Status.NOTIFIED -> listOf(
                        ReminderEffect.ShowNotification(edited, NotificationKind.RESHOW),
                        ReminderEffect.SetAlarm(ID, NOW + 5 * MINUTE, AlarmKind.NAG, dueTime)
                    )
                    // A scheduled reminder still ahead keeps the Deliver alarm it has,
                    // and a done reminder keeps its empty slot: nothing to do.
                    Status.SCHEDULED, Status.DONE -> emptyList()
                }
            }
        }
    }

    test("9. an edit that turns nagging off empties a notified reminder's alarm slot") {
        val dueTime = NOW - 25 * MINUTE
        val stored = reminder(dueTime = dueTime, status = Status.NOTIFIED, naggingRepeatInterval = 10)

        val result = transition(stored, ReminderCommand.Edit(ID, "Water the plants", 0), NOW)

        val edited = (result.outcome as TransitionOutcome.Updated).reminder
        edited.status shouldBe Status.NOTIFIED
        edited.isNagging shouldBe false
        result.effects shouldBe listOf(
            ReminderEffect.ShowNotification(edited, NotificationKind.RESHOW),
            ReminderEffect.CancelAlarm(ID)
        )
    }

    test("an edit never leaves a scheduled reminder whose due time has passed without a delivery") {
        // The invariant is that a scheduled reminder has a Deliver alarm for its due
        // time. Editing one that is already past due and not yet delivered puts the
        // alarm back rather than waiting for the next reconciliation.
        val dueTime = NOW - MINUTE
        val stored = reminder(dueTime = dueTime, status = Status.SCHEDULED)

        val result = transition(stored, ReminderCommand.Edit(ID, "Water the ferns", 0), NOW)

        val edited = (result.outcome as TransitionOutcome.Updated).reminder
        edited.status shouldBe Status.SCHEDULED
        edited.date shouldBe Date(dueTime)
        result.effects shouldBe
            listOf(ReminderEffect.SetAlarm(ID, dueTime, AlarmKind.DELIVER, dueTime))
    }

    test("the edit dialog asks for an edit when the due time is untouched and a reschedule when it moved") {
        editOrReschedule(ID, NOW + HOUR, NOW + HOUR, "Water the ferns", 10) shouldBe
            ReminderCommand.Edit(ID, "Water the ferns", 10)

        editOrReschedule(ID, NOW + HOUR, NOW + 2 * HOUR, "Water the ferns", 10) shouldBe
            ReminderCommand.Reschedule(ID, NOW + 2 * HOUR, "Water the ferns", 10)
    }

    test("an untouched save is an edit when only the seconds and milliseconds differ") {
        // The dialog's due time is minute precision: the pickers show a date, an hour
        // and a minute and nothing finer. The picker paths used to keep whatever sat
        // below the minute — the stored reminder's milliseconds on restore, the
        // milliseconds of the moment it ran on every other path — so reopening the date
        // picker on the same day produced a different epoch value for the same displayed
        // minute. An OK press with nothing visibly changed must still be an edit,
        // otherwise a future-due done reminder is silently re-armed as scheduled.
        for (status in Status.entries) {
            val displayedMinute = if (status == Status.SCHEDULED) NOW + HOUR else NOW - HOUR
            val stored = reminder(dueTime = displayedMinute + 12_345L, status = status)

            val command = editOrReschedule(
                reminderId = ID,
                dueTimeWhenOpened = stored.date.time,
                // Same date and minute on screen, another moment within it.
                chosenDueTime = displayedMinute + 54_321L,
                text = "Water the ferns",
                naggingRepeatInterval = 0
            )

            withClue(status.toString()) {
                command shouldBe ReminderCommand.Edit(ID, "Water the ferns", 0)
                val saved = (transition(stored, command, NOW).outcome as TransitionOutcome.Updated).reminder
                saved.status shouldBe status
                saved.date shouldBe stored.date
            }
        }
    }

    test("the edit dialog opens on the stored due time whatever the reminder's state") {
        for (status in Status.entries) {
            val dueMinute = if (status == Status.SCHEDULED) NOW + HOUR else NOW - 25 * MINUTE
            // Seconds and milliseconds the store happens to hold are not part of the
            // displayed due time and do not come back from the pickers either.
            val stored = reminder(dueTime = dueMinute + 12_345L, status = status)

            withClue(status.toString()) {
                initialDueTimeForEdit(stored, NOW) shouldBe dueMinute
            }
        }
    }

    test("saving a notified or done reminder with the time untouched no longer schedules it in the past") {
        // The reported bug, from the dialog's two decisions composed: the due time the
        // dialog opens on, and the command it issues for the value that comes back.
        // The chosen due time is what the pickers hold after the restore and the user
        // did not touch them; what makes an untouched save an edit is that the restored
        // value is the reminder's own due time and not the minute the dialog opened on.
        for (status in listOf(Status.NOTIFIED, Status.DONE)) {
            val stored = reminder(dueTime = NOW - HOUR, status = status)

            val command = editOrReschedule(
                reminderId = ID,
                dueTimeWhenOpened = stored.date.time,
                chosenDueTime = initialDueTimeForEdit(stored, NOW),
                text = "Water the ferns",
                naggingRepeatInterval = 0
            )
            val result = transition(stored, command, NOW)

            withClue(status.toString()) {
                command shouldBe ReminderCommand.Edit(ID, "Water the ferns", 0)
                val saved = (result.outcome as TransitionOutcome.Updated).reminder
                saved.status shouldBe status
                saved.date shouldBe stored.date
                saved.text shouldBe "Water the ferns"
                // Nothing put a Deliver alarm in the slot, which is what the old path did
                // by re-arming a reminder that was already dealt with.
                result.effects.filterIsInstance<ReminderEffect.SetAlarm>()
                    .filter { it.kind == AlarmKind.DELIVER } shouldBe emptyList()
            }
        }
    }

    test("10. every command on a reminder the store does not hold is cleanup") {
        val commands = listOf(
            ReminderCommand.Deliver(ID, NOW),
            ReminderCommand.Nag(ID, NOW),
            ReminderCommand.MarkDone(ID),
            ReminderCommand.Reschedule(ID, NOW + HOUR, "Gone", 0),
            ReminderCommand.Edit(ID, "Gone", 0),
            ReminderCommand.Delete(ID),
            ReminderCommand.Reconcile(ID)
        )

        for (command in commands) {
            withClue(command.toString()) {
                transition(null, command, NOW) shouldBe
                    TransitionResult(TransitionOutcome.Unchanged, bothCancels())
            }
        }
    }

    test("11. reconciling a mixed store gives each state its own effects") {
        val pastDue = reminder(dueTime = NOW - MINUTE, status = Status.SCHEDULED, id = 2)
        val future = reminder(dueTime = NOW + HOUR, status = Status.SCHEDULED, id = 4)
        val notified = reminder(
            dueTime = NOW - 25 * MINUTE,
            status = Status.NOTIFIED,
            naggingRepeatInterval = 10,
            id = 6
        )
        val done = reminder(dueTime = NOW - HOUR, status = Status.DONE, id = 8)

        val results = listOf(pastDue, future, notified, done)
            .associateWith { transition(it, ReminderCommand.Reconcile(it.id), NOW) }

        val delivered = (results.getValue(pastDue).outcome as TransitionOutcome.Updated).reminder
        delivered.status shouldBe Status.NOTIFIED
        results.getValue(pastDue).effects shouldBe
            listOf(ReminderEffect.ShowNotification(delivered, NotificationKind.DELIVER))

        results.getValue(future) shouldBe TransitionResult(
            TransitionOutcome.Unchanged,
            listOf(ReminderEffect.SetAlarm(4, NOW + HOUR, AlarmKind.DELIVER, NOW + HOUR))
        )

        results.getValue(notified) shouldBe TransitionResult(
            TransitionOutcome.Unchanged,
            listOf(
                ReminderEffect.ShowNotification(notified, NotificationKind.RESHOW),
                ReminderEffect.SetAlarm(6, NOW + 5 * MINUTE, AlarmKind.NAG, notified.date.time)
            )
        )

        // A done reminder is left with an empty slot and nothing on screen, which is
        // also the repair for a mark done whose cancels never ran.
        results.getValue(done) shouldBe
            TransitionResult(TransitionOutcome.Unchanged, bothCancels(8))
    }

    test("12. an add with no id left to give is refused rather than built") {
        // The counter holds EXHAUSTED_ID_COUNTER once the largest id there is has been
        // handed out. Building the reminder anyway threw out of Reminder's own require,
        // which is a crash in the add dialog; the answer is a refusal the dialog can say
        // something about, and the counter stays where it is.
        val result = transition(
            null,
            ReminderCommand.Add(EXHAUSTED_ID_COUNTER, NOW + HOUR, "Water the plants", 0),
            NOW
        )

        result shouldBe TransitionResult(
            TransitionOutcome.Refused(RefusalReason.IdSpaceExhausted)
        )
    }

    test("13. the largest id there is can still be added") {
        // The bound itself is allocatable: it is the counter two past it that is not.
        val result = transition(
            null,
            ReminderCommand.Add(Reminder.MAX_REMINDER_ID, NOW + HOUR, "Water the plants", 0),
            NOW
        )

        (result.outcome as TransitionOutcome.Updated).reminder.id shouldBe
            Reminder.MAX_REMINDER_ID
    }

    test("a nag is never earlier than the due time plus one interval") {
        // Setting the clock back puts now before the due time of a reminder that was
        // already delivered. The remainder that counts occurrences from the due time
        // then lands on a negative multiple of the interval: due 10:00, now 09:00 and a
        // ten minute interval used to give a nag at 09:10, and the reminder nagged over
        // and over before its own due time on the corrected clock.
        val dueTime = NOW
        val interval = 10 * MINUTE
        val stored = reminder(
            dueTime = dueTime,
            status = Status.NOTIFIED,
            naggingRepeatInterval = 10
        )

        // Now before the due time, by an hour and by a millisecond.
        nextNagTime(stored, dueTime - HOUR) shouldBe dueTime + interval
        nextNagTime(stored, dueTime - 1) shouldBe dueTime + interval
        // At the due time, and a millisecond after it.
        nextNagTime(stored, dueTime) shouldBe dueTime + interval
        nextNagTime(stored, dueTime + 1) shouldBe dueTime + interval
        // On an exact interval boundary the next nag is the following occurrence, not
        // this instant, so a nag alarm is never set for the moment it fires.
        nextNagTime(stored, dueTime + interval) shouldBe dueTime + 2 * interval
        nextNagTime(stored, dueTime + 3 * interval) shouldBe dueTime + 4 * interval
    }

    test("reconciling after the clock was set back does not nag before the due time") {
        // What a rollback looks like in the store: a reminder that has been delivered
        // and whose due time is ahead of the clock the app is now running on.
        val dueTime = NOW + HOUR
        val notified = reminder(
            dueTime = dueTime,
            status = Status.NOTIFIED,
            naggingRepeatInterval = 10
        )

        val result = transition(notified, ReminderCommand.Reconcile(ID), NOW)

        result shouldBe TransitionResult(
            TransitionOutcome.Unchanged,
            listOf(
                ReminderEffect.ShowNotification(notified, NotificationKind.RESHOW),
                ReminderEffect.SetAlarm(ID, dueTime + 10 * MINUTE, AlarmKind.NAG, dueTime)
            )
        )
    }

    test("reconciling a done reminder empties its slot and takes its notification off the screen") {
        // Invariant 3: a done reminder has an empty alarm slot and no notification
        // after a reconciliation. The write that marks a reminder done happens before
        // the cancels, so a process that dies in between — or a cancel that fails —
        // leaves a done reminder with a nag alarm or a notification still there, and
        // the reconciliation is the only thing that can repair it. Both cancels are
        // idempotent, so doing them on every start costs nothing.
        for (dueTime in listOf(NOW - HOUR, NOW + HOUR)) {
            val done = reminder(dueTime = dueTime, status = Status.DONE, naggingRepeatInterval = 10)

            transition(done, ReminderCommand.Reconcile(ID), NOW) shouldBe
                TransitionResult(TransitionOutcome.Unchanged, bothCancels())
        }
    }

    test("the largest nag interval a reminder may hold computes its next nag a day ahead") {
        // The remainder in `nextNagTime` divides by the interval, so an interval that
        // came out zero or negative from overflowing Int arithmetic used to throw here
        // or set an alarm in the past. 1440 minutes is the largest the model accepts.
        val dueTime = NOW - 30 * MINUTE
        val stored = reminder(
            dueTime = dueTime,
            status = Status.NOTIFIED,
            naggingRepeatInterval = Reminder.MAX_NAGGING_REPEAT_INTERVAL
        )

        nextNagTime(stored, NOW) shouldBe dueTime + 24 * HOUR
    }
})

/** A fixed clock: 2026-09-05T12:00:00Z. */
private const val NOW = 1788609600000L
private const val MINUTE = 60L * 1000L
private const val HOUR = 60L * MINUTE
private const val ID = 2

/** The cleanup pair: empty the alarm slot and take the notification off the screen. */
private fun bothCancels(id: Int = ID) = listOf(
    ReminderEffect.CancelAlarm(id),
    ReminderEffect.CancelNotification(id)
)

private fun reminder(
    dueTime: Long,
    status: Status,
    naggingRepeatInterval: Int = 0,
    text: String = "Water the plants",
    id: Int = ID
) = Reminder(
    id = id,
    date = Date(dueTime),
    naggingRepeatInterval = naggingRepeatInterval,
    text = text,
    status = status
)
