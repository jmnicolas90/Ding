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
import java.util.concurrent.locks.ReentrantLock

/**
 * Runs commands against the store: lock, read, decide, write, then act. Every
 * change to a reminder goes through here.
 *
 * Like [transition], this file has no Android imports. The store and the effect
 * executor are interfaces so that a test can inject fakes and a runner can be
 * driven on a plain JVM.
 */

/** The stored reminders, together with the counter that allocates the next reminder id. */
data class StoredReminders(val reminders: List<Reminder>, val nextId: Int)

/**
 * What one read of the store found.
 *
 * A read never repairs anything, so it answers with the reminders the app can run on
 * and with what it noticed about the stored value. The repairs are writes, and every
 * write happens inside the runner under its lock.
 */
data class StoreReading(
    /** The reminders to run on: empty when the stored value could not be read. */
    val stored: StoredReminders,
    /**
     * Why the stored value could not be read, or null when it could. A value that
     * could not be read has not been set aside yet: the runner does that before it
     * does anything else, and until then the app runs on the empty view above.
     */
    val unreadable: UnreadableReason? = null,
    /**
     * True when the stored id counter was not usable as it stood and the read answered
     * with another one: moved past the reminders it was behind, rounded up to an even
     * number, or clamped down to [EXHAUSTED_ID_COUNTER] because it was past the end of
     * the id space. The counter the read settled on is in [stored] and lands on disk
     * with the next write that commits.
     */
    val counterRepaired: Boolean = false
)

/**
 * What running one command did: one of the transition function's outcomes, or
 * [PersistenceFailed].
 *
 * The failure is a case of its own rather than [TransitionOutcome.Unchanged],
 * because "nothing needed doing" and "the write did not happen" are opposite
 * answers to the caller: the first is safe to ignore, the second means the user
 * asked for something and did not get it. Callers match on this type without an
 * `else`, so the compiler asks every one of them what it does about a failed write.
 */
sealed interface CommandResult

/** What a reconciliation sweep did. */
sealed interface ReconcileResult {
    /** The sweep ran; one outcome per stored reminder, in store order. */
    data class Reconciled(val outcomes: List<TransitionOutcome>) : ReconcileResult
}

/**
 * The store did not commit, so nothing happened at all: nothing was written, no
 * change was announced, no effect ran. Every reminder keeps the state it has on
 * disk, which is what makes the failure safe to retry.
 */
data object PersistenceFailed : CommandResult, ReconcileResult

/** Where reminders are kept. */
interface ReminderStore {
    /**
     * Read the whole store. Never writes: a value it cannot read is reported through
     * [StoreReading.unreadable], not repaired, because the repair is a write and a
     * write from a read can land on top of a command the runner is in the middle of.
     */
    fun read(): StoreReading

    /**
     * Move the value [read] reported unreadable to keys of its own and empty the normal
     * ones, in one commit, so that nothing written afterwards can land on top of it.
     * The id counter is the one thing that is carried over instead of emptied, because
     * the ids it handed out are still live outside the store as notifications, alarms
     * and pending intents — see [nextIdAfterQuarantine].
     *
     * Called by the runner alone, under its lock, before anything else it does.
     *
     * @return the store as it stands afterwards — no reminders, the counter where it
     *   was — or null when the commit did not go through, in which case nothing was
     *   written and the value is still unreadable, for the next command to try again.
     */
    fun setAsideUnreadable(): StoredReminders?

    /**
     * Write the whole store, and report whether the write actually committed.
     * A false answer means nothing was stored, so nothing may be acted on.
     *
     * The contract on failure: after a write that reports failure, every later
     * [read] returns the snapshot from before that write. An implementation whose
     * underlying commit does not manage that has to put the previous values back
     * itself — see [writeWithRollback] — because the runner and its callers treat a
     * failed write as something that never happened at all.
     */
    fun write(stored: StoredReminders): Boolean

    /** Tell the rest of the app that the stored reminders changed. */
    fun announceChange()
}

/** Does what the transition function decided: alarms and notifications. */
interface ReminderEffectExecutor {
    fun execute(effect: ReminderEffect)
}

class ReminderCommandRunner(
    private val store: ReminderStore,
    private val effectExecutor: ReminderEffectExecutor,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val lock = ReentrantLock()

    /** Run one command and return what it did to the store. */
    fun run(command: ReminderCommand): CommandResult = runCommand { command }

    /**
     * Add a reminder. The id comes from the store's counter, read under the same
     * lock as the write, so two additions cannot be given the same id.
     */
    fun add(dueTime: Long, text: String, naggingRepeatInterval: Int): CommandResult =
        runCommand { stored ->
            ReminderCommand.Add(stored.nextId, dueTime, text, naggingRepeatInterval)
        }

    /**
     * Bring alarms and notifications back in line with the store, for every stored
     * reminder. Runs once per process start, before any other component.
     */
    fun reconcileAll(): ReconcileResult {
        lock.lock()
        val outcomes: List<TransitionOutcome>
        val effects: List<ReminderEffect>
        var written = false
        try {
            val stored = readable() ?: return PersistenceFailed
            val now = clock()
            val results = stored.reminders.map { reminder ->
                transition(reminder, ReminderCommand.Reconcile(reminder.id), now)
            }
            outcomes = results.map { it.outcome }
            effects = results.flatMap { it.effects }
            // Reconcile never removes or refuses, so one write of the whole list covers
            // every reminder it delivered.
            val delivered = outcomes.filterIsInstance<TransitionOutcome.Updated>()
            if (delivered.isNotEmpty()) {
                val byId = delivered.associate { it.reminder.id to it.reminder }
                val reminders = stored.reminders.map { byId[it.id] ?: it }
                written = store.write(stored.copy(reminders = reminders))
                if (!written) {
                    // Persist first: a store that did not commit gets no announcement
                    // and no effects at all, because the reminders it just delivered
                    // are still scheduled on disk. The sweep is reported as failed
                    // rather than as a sweep that found nothing to do.
                    return PersistenceFailed
                }
            }
        } finally {
            lock.unlock()
        }
        if (written) {
            store.announceChange()
        }
        effects.forEach(effectExecutor::execute)
        return ReconcileResult.Reconciled(outcomes)
    }

    private fun runCommand(makeCommand: (StoredReminders) -> ReminderCommand): CommandResult {
        lock.lock()
        val result: TransitionResult
        var written = false
        try {
            val stored = readable() ?: return PersistenceFailed
            val command = makeCommand(stored)
            val existing = stored.reminders.find { it.id == command.reminderId }
            result = transition(existing, command, clock())
            val toWrite = storeAfter(stored, result.outcome, command.reminderId)
            if (toWrite != null) {
                written = store.write(toWrite)
                if (!written) {
                    // The write did not commit, so the decision never happened: no
                    // announcement, no effects, and a failure the caller has to answer
                    // for. Reporting it as Unchanged would let a caller schedule an
                    // alarm for a reminder that is not in the store.
                    return PersistenceFailed
                }
            }
        } finally {
            lock.unlock()
        }
        if (written) {
            store.announceChange()
        }
        result.effects.forEach(effectExecutor::execute)
        return result.outcome
    }

    /**
     * The store to work from, with an unreadable value set aside first.
     *
     * Setting aside is the first thing every command does, and the first command of a
     * process is always the startup Reconcile, so a value that could not be read is
     * kept before anything else writes to the normal keys. It is a write like any
     * other: when it does not commit, the command it was the first step of does not
     * happen at all, and the next one tries the set-aside again.
     *
     * @return null when the set-aside did not commit, which is a persistence failure.
     */
    private fun readable(): StoredReminders? {
        val reading = store.read()
        return if (reading.unreadable != null) store.setAsideUnreadable() else reading.stored
    }

    /**
     * The store as it should be after the outcome, or null when the outcome asks for
     * no write.
     */
    private fun storeAfter(
        stored: StoredReminders,
        outcome: TransitionOutcome,
        reminderId: Int
    ): StoredReminders? = when (outcome) {
        is TransitionOutcome.Updated -> {
            val reminder = outcome.reminder
            val reminders =
                if (stored.reminders.any { it.id == reminder.id }) {
                    stored.reminders.map { if (it.id == reminder.id) reminder else it }
                } else {
                    stored.reminders + reminder
                }
            // Reminder ids are even and allocated in order, so an added reminder moves
            // the counter past itself and any other write leaves it alone.
            stored.copy(
                reminders = reminders,
                nextId = maxOf(stored.nextId, reminder.id + 2)
            )
        }

        TransitionOutcome.Removed ->
            stored.copy(reminders = stored.reminders.filterNot { it.id == reminderId })

        TransitionOutcome.Unchanged -> null
        is TransitionOutcome.Refused -> null
    }
}
