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
import java.util.Date

/**
 * The one place where reminder state changes are decided, implemented from
 * `docs/reminder-state-machine.md`.
 *
 * This file must never import anything from `android.*` or `androidx.*`: it is
 * a plain JVM function so that delivery, nagging and the cold-start sweep can
 * be tested with a fixed clock and no device.
 *
 * Times are epoch milliseconds, matching [Reminder.date], which is what the
 * store already holds.
 */

/** A request to change one reminder. Every command names a reminder id. */
sealed interface ReminderCommand {
    val reminderId: Int

    /** Create a reminder. The id is allocated by the store before the command is formed. */
    data class Add(
        override val reminderId: Int,
        val dueTime: Long,
        val text: String,
        val naggingRepeatInterval: Int
    ) : ReminderCommand

    /** Deliver the reminder: show its notification and move it to [Status.NOTIFIED]. */
    data class Deliver(override val reminderId: Int, val expectedDueTime: Long) : ReminderCommand

    /** Show a delivered reminder again, and set the following nag. */
    data class Nag(override val reminderId: Int, val expectedDueTime: Long) : ReminderCommand

    /** Finish the reminder. */
    data class MarkDone(override val reminderId: Int) : ReminderCommand

    /** Give the reminder a new due time; it lands in [Status.SCHEDULED] from any state. */
    data class Reschedule(
        override val reminderId: Int,
        val dueTime: Long,
        val text: String,
        val naggingRepeatInterval: Int
    ) : ReminderCommand

    /** Change text and nag settings without touching the due time or the state. */
    data class Edit(
        override val reminderId: Int,
        val text: String,
        val naggingRepeatInterval: Int
    ) : ReminderCommand

    /** Remove the reminder from the store. */
    data class Delete(override val reminderId: Int) : ReminderCommand

    /** Bring one reminder's alarm and notification back in line with the store. */
    data class Reconcile(override val reminderId: Int) : ReminderCommand
}

/** What the store has to do about a command. */
sealed interface TransitionOutcome {
    /** Write this reminder. */
    data class Updated(val reminder: Reminder) : TransitionOutcome

    /** Delete the reminder. */
    data object Removed : TransitionOutcome

    /** Write nothing. Effects may still run. */
    data object Unchanged : TransitionOutcome

    /** Write nothing, run nothing. */
    data class Refused(val reason: RefusalReason) : TransitionOutcome
}

/** Why a command was refused. */
enum class RefusalReason {
    /** The requested due time is not in the future. */
    PastDue
}

/** Which alarm occupies a reminder's alarm slot. */
enum class AlarmKind { DELIVER, NAG }

/** Why a notification is being shown. Deliver and Nag alert; Reshow is silent. */
enum class NotificationKind { DELIVER, NAG, RESHOW }

/**
 * Something the runner does to the world after the store has been written.
 * Effects are data: deciding them is pure, doing them is not.
 */
sealed interface ReminderEffect {
    /**
     * Put an alarm in the reminder's alarm slot, replacing whatever is there.
     * [expectedDueTime] travels with the alarm so that a delivery for a due
     * time the store no longer holds can be recognised as stale on arrival.
     */
    data class SetAlarm(
        val reminderId: Int,
        val at: Long,
        val kind: AlarmKind,
        val expectedDueTime: Long
    ) : ReminderEffect

    /** Empty the reminder's alarm slot. */
    data class CancelAlarm(val reminderId: Int) : ReminderEffect

    /** Show the reminder's notification. The reminder travels with the effect so the
     *  runner needs no second read of the store to render it. */
    data class ShowNotification(val reminder: Reminder, val kind: NotificationKind) : ReminderEffect

    /** Remove the reminder's notification. */
    data class CancelNotification(val reminderId: Int) : ReminderEffect
}

/** The answer to one command: what to store, and what to do afterwards. */
data class TransitionResult(
    val outcome: TransitionOutcome,
    val effects: List<ReminderEffect> = emptyList()
)

/**
 * Decide what a command does to a reminder. Pure: no store, no clock of its
 * own, no Android.
 *
 * @param stored the reminder as read from the store under the lock, or null if
 *     the store does not hold it
 * @param now the current time in epoch milliseconds
 */
fun transition(stored: Reminder?, command: ReminderCommand, now: Long): TransitionResult {
    if (command is ReminderCommand.Add) {
        return add(command, now)
    }
    if (stored == null) {
        // A missing reminder is cleanup, never an error: an alarm can arrive for
        // a reminder that was deleted while it was in flight.
        return TransitionResult(
            TransitionOutcome.Unchanged,
            listOf(
                ReminderEffect.CancelAlarm(command.reminderId),
                ReminderEffect.CancelNotification(command.reminderId)
            )
        )
    }
    return when (command) {
        is ReminderCommand.Add -> throw IllegalStateException("Add is handled above.")
        is ReminderCommand.Deliver -> deliver(stored, command.expectedDueTime, now)
        is ReminderCommand.Nag -> nag(stored, command.expectedDueTime, now)
        is ReminderCommand.MarkDone -> markDone(stored)
        is ReminderCommand.Reschedule -> reschedule(stored, command, now)
        is ReminderCommand.Edit -> edit(stored, command, now)
        is ReminderCommand.Delete -> delete(stored)
        is ReminderCommand.Reconcile -> reconcile(stored, now)
    }
}

private fun add(command: ReminderCommand.Add, now: Long): TransitionResult {
    if (command.dueTime <= now) {
        return TransitionResult(TransitionOutcome.Refused(RefusalReason.PastDue))
    }
    val reminder = Reminder(
        id = command.reminderId,
        date = Date(command.dueTime),
        naggingRepeatInterval = command.naggingRepeatInterval,
        text = command.text,
        status = Status.SCHEDULED
    )
    return TransitionResult(
        TransitionOutcome.Updated(reminder),
        listOf(deliverAlarm(reminder))
    )
}

private fun deliver(stored: Reminder, expectedDueTime: Long, now: Long): TransitionResult {
    // The guard that makes delivery happen at most once per (id, due time), however
    // many alarms and reconciliations arrive: a reminder that is no longer SCHEDULED
    // has already been delivered, and an alarm whose expected due time is not the
    // stored one was set for a delivery that no longer exists.
    if (stored.status != Status.SCHEDULED || expectedDueTime != stored.date.time) {
        return stale()
    }
    val delivered = stored.copy(status = Status.NOTIFIED)
    return TransitionResult(
        TransitionOutcome.Updated(delivered),
        buildList {
            add(ReminderEffect.ShowNotification(delivered, NotificationKind.DELIVER))
            if (delivered.isNagging) add(nagAlarm(delivered, now))
        }
    )
}

private fun nag(stored: Reminder, expectedDueTime: Long, now: Long): TransitionResult {
    if (stored.status != Status.NOTIFIED || !stored.isNagging || expectedDueTime != stored.date.time) {
        return stale()
    }
    return TransitionResult(
        TransitionOutcome.Unchanged,
        listOf(
            ReminderEffect.ShowNotification(stored, NotificationKind.NAG),
            nagAlarm(stored, now)
        )
    )
}

private fun markDone(stored: Reminder): TransitionResult {
    val effects = listOf(
        ReminderEffect.CancelAlarm(stored.id),
        ReminderEffect.CancelNotification(stored.id)
    )
    return if (stored.status == Status.DONE) {
        TransitionResult(TransitionOutcome.Unchanged, effects)
    } else {
        TransitionResult(TransitionOutcome.Updated(stored.copy(status = Status.DONE)), effects)
    }
}

private fun reschedule(
    stored: Reminder,
    command: ReminderCommand.Reschedule,
    now: Long
): TransitionResult {
    if (command.dueTime <= now) {
        return TransitionResult(TransitionOutcome.Refused(RefusalReason.PastDue))
    }
    val rescheduled = stored.copy(
        date = Date(command.dueTime),
        text = command.text,
        naggingRepeatInterval = command.naggingRepeatInterval,
        status = Status.SCHEDULED
    )
    return TransitionResult(
        TransitionOutcome.Updated(rescheduled),
        listOf(
            ReminderEffect.CancelNotification(rescheduled.id),
            deliverAlarm(rescheduled)
        )
    )
}

private fun edit(stored: Reminder, command: ReminderCommand.Edit, now: Long): TransitionResult {
    val edited = stored.copy(
        text = command.text,
        naggingRepeatInterval = command.naggingRepeatInterval
    )
    // A scheduled or done reminder needs no effect: the alarm payload carries only
    // the id and the due time, and text and nag settings are read from the store
    // when the alarm fires.
    if (edited.status != Status.NOTIFIED) {
        return TransitionResult(TransitionOutcome.Updated(edited))
    }
    return TransitionResult(
        TransitionOutcome.Updated(edited),
        listOf(
            ReminderEffect.ShowNotification(edited, NotificationKind.RESHOW),
            if (edited.isNagging) nagAlarm(edited, now) else ReminderEffect.CancelAlarm(edited.id)
        )
    )
}

private fun delete(stored: Reminder): TransitionResult =
    TransitionResult(
        TransitionOutcome.Removed,
        listOf(
            ReminderEffect.CancelAlarm(stored.id),
            ReminderEffect.CancelNotification(stored.id)
        )
    )

private fun reconcile(stored: Reminder, now: Long): TransitionResult =
    when (stored.status) {
        Status.SCHEDULED ->
            if (stored.date.time <= now) {
                deliver(stored, stored.date.time, now)
            } else {
                TransitionResult(TransitionOutcome.Unchanged, listOf(deliverAlarm(stored)))
            }

        Status.NOTIFIED -> TransitionResult(
            TransitionOutcome.Unchanged,
            buildList {
                add(ReminderEffect.ShowNotification(stored, NotificationKind.RESHOW))
                if (stored.isNagging) add(nagAlarm(stored, now))
            }
        )

        Status.DONE -> TransitionResult(TransitionOutcome.Unchanged)
    }

/**
 * A command that the stored reminder cannot accept, or that carries a due time
 * the store no longer holds. Not an error: nothing is written and nothing is done.
 */
private fun stale(): TransitionResult = TransitionResult(TransitionOutcome.Unchanged)

private fun deliverAlarm(reminder: Reminder) = ReminderEffect.SetAlarm(
    reminderId = reminder.id,
    at = reminder.date.time,
    kind = AlarmKind.DELIVER,
    expectedDueTime = reminder.date.time
)

private fun nagAlarm(reminder: Reminder, now: Long) = ReminderEffect.SetAlarm(
    reminderId = reminder.id,
    at = nextNagTime(reminder, now),
    kind = AlarmKind.NAG,
    expectedDueTime = reminder.date.time
)

/**
 * The first multiple of the nag interval after [now], counted from the original
 * due time. A nag delayed past several intervals therefore fires once, not once
 * per missed occurrence.
 */
fun nextNagTime(reminder: Reminder, now: Long): Long {
    require(reminder.isNagging) { "Reminder ${reminder.id} does not nag." }
    val interval = reminder.naggingRepeatIntervalInMillis
    val sinceLastNag = Math.floorMod(now - reminder.date.time, interval)
    return now + (interval - sinceLastNag)
}
