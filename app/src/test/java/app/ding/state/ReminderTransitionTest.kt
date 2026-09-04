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

        results.getValue(done) shouldBe TransitionResult(TransitionOutcome.Unchanged)
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
