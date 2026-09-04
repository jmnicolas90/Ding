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
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import java.util.Date

/**
 * Tests of the runner over a fake store and a fake effect executor: the same cold
 * start as test 1 of `docs/reminder-state-machine.md`, but driven end to end
 * through lock, read, write and effects.
 */
class ReminderCommandRunnerTest : FunSpec({

    test("the cold start alerts once and writes once through the runner too") {
        val dueTime = NOW - 60_000
        val store = FakeStore(
            StoredReminders(
                listOf(
                    Reminder(
                        id = 2,
                        date = Date(dueTime),
                        text = "Water the plants",
                        status = Status.SCHEDULED
                    )
                ),
                nextId = 4
            )
        )
        val executor = RecordingExecutor()
        val runner = ReminderCommandRunner(store, executor) { NOW }

        // The process starts because of the alarm: reconciliation runs first, the
        // delivered alarm arrives second.
        runner.reconcileAll()
        runner.run(ReminderCommand.Deliver(2, dueTime))

        executor.effects.filterIsInstance<ReminderEffect.ShowNotification>() shouldHaveSize 1
        store.writes shouldHaveSize 1
        store.announcements shouldBe 1
        store.read().reminders.single().status shouldBe Status.NOTIFIED
    }

    test("adding a reminder allocates the next even id and moves the counter") {
        val store = FakeStore(StoredReminders(emptyList(), nextId = 4))
        val executor = RecordingExecutor()
        val runner = ReminderCommandRunner(store, executor) { NOW }

        val outcome = runner.add(NOW + 60_000, "Call the plumber", naggingRepeatInterval = 0)

        val added = (outcome as TransitionOutcome.Updated).reminder
        added.id shouldBe 4
        store.read() shouldBe StoredReminders(listOf(added), nextId = 6)
        executor.effects shouldBe listOf(
            ReminderEffect.SetAlarm(4, NOW + 60_000, AlarmKind.DELIVER, NOW + 60_000)
        )
    }
})

/** A fixed clock: 2026-09-05T12:00:00Z. */
private const val NOW = 1788609600000L

/**
 * A store a test can drive. [writeSucceeds] is what ticket 12 needs to test a
 * commit that fails; this ticket only uses the succeeding case.
 */
private class FakeStore(
    private var stored: StoredReminders,
    private val writeSucceeds: Boolean = true
) : ReminderStore {
    val writes = mutableListOf<StoredReminders>()
    var announcements = 0

    override fun read(): StoredReminders = stored

    override fun write(stored: StoredReminders): Boolean {
        if (!writeSucceeds) {
            return false
        }
        writes.add(stored)
        this.stored = stored
        return true
    }

    override fun announceChange() {
        announcements++
    }
}

private class RecordingExecutor : ReminderEffectExecutor {
    val effects = mutableListOf<ReminderEffect>()
    override fun execute(effect: ReminderEffect) {
        effects.add(effect)
    }
}
