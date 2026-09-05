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

import app.ding.NAG_PAYLOAD_BEFORE_THE_RENAME
import app.ding.NOTIFY_PAYLOAD_BEFORE_THE_RENAME
import app.ding.ReminderManager.ReminderAction
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

    test("an alarm set before the upgrade still delivers the reminder it was set for") {
        val dueTime = NOW - 60_000
        val store = FakeStore(StoredReminders(listOf(scheduled(dueTime)), nextId = 4))
        val executor = RecordingExecutor()
        val runner = ReminderCommandRunner(store, executor) { NOW }

        // The old payload carries no due time, so the reminder still being scheduled
        // and already due is what makes the alarm good.
        runner.run(commandIn(NOTIFY_PAYLOAD_BEFORE_THE_RENAME))

        executor.effects.filterIsInstance<ReminderEffect.ShowNotification>()
            .map { it.kind } shouldBe listOf(NotificationKind.DELIVER)
        store.read().reminders.single().status shouldBe Status.NOTIFIED
    }

    test("an alarm set before the upgrade adds nothing to the cold start") {
        val dueTime = NOW - 60_000
        val store = FakeStore(StoredReminders(listOf(scheduled(dueTime)), nextId = 4))
        val executor = RecordingExecutor()
        val runner = ReminderCommandRunner(store, executor) { NOW }

        // Reconciliation runs first and delivers; the old alarm arrives second and
        // finds the reminder already notified.
        runner.reconcileAll()
        runner.run(commandIn(NOTIFY_PAYLOAD_BEFORE_THE_RENAME))

        executor.effects.filterIsInstance<ReminderEffect.ShowNotification>() shouldHaveSize 1
        store.writes shouldHaveSize 1
    }

    test("an alarm set before the upgrade does not deliver a reminder that is not due") {
        val store = FakeStore(StoredReminders(listOf(scheduled(NOW + 60_000)), nextId = 4))
        val executor = RecordingExecutor()
        val runner = ReminderCommandRunner(store, executor) { NOW }

        runner.run(commandIn(NOTIFY_PAYLOAD_BEFORE_THE_RENAME))

        executor.effects shouldBe emptyList()
        store.writes shouldBe emptyList()
    }

    test("a nag alarm set before the upgrade goes on nagging") {
        val dueTime = NOW - 60_000
        val nagging = scheduled(dueTime).copy(
            naggingRepeatInterval = 5,
            status = Status.NOTIFIED
        )
        val store = FakeStore(StoredReminders(listOf(nagging), nextId = 4))
        val executor = RecordingExecutor()
        val runner = ReminderCommandRunner(store, executor) { NOW }

        runner.run(commandIn(NAG_PAYLOAD_BEFORE_THE_RENAME))

        executor.effects shouldBe listOf(
            ReminderEffect.ShowNotification(nagging, NotificationKind.NAG),
            ReminderEffect.SetAlarm(2, NOW + 240_000, AlarmKind.NAG, dueTime)
        )
        store.writes shouldBe emptyList()
    }

    // Test 12 of `docs/reminder-state-machine.md`: a write that does not commit is a
    // failure of its own, and nothing at all happens on that path.
    test("a commit that fails is a typed failure with no announcement and no effects") {
        val stored = StoredReminders(listOf(scheduled(NOW + 60_000)), nextId = 4)
        val store = FakeStore(stored, writeSucceeds = false)
        val executor = RecordingExecutor()
        val runner = ReminderCommandRunner(store, executor) { NOW }

        val result = runner.run(ReminderCommand.MarkDone(2))

        result shouldBe PersistenceFailed
        store.announcements shouldBe 0
        executor.effects shouldBe emptyList()
        // The reminder is still scheduled on disk, exactly as it was.
        store.read() shouldBe stored
    }

    test("an add whose commit fails allocates nothing and is a typed failure") {
        val stored = StoredReminders(emptyList(), nextId = 4)
        val store = FakeStore(stored, writeSucceeds = false)
        val executor = RecordingExecutor()
        val runner = ReminderCommandRunner(store, executor) { NOW }

        val result = runner.add(NOW + 60_000, "Call the plumber", naggingRepeatInterval = 0)

        result shouldBe PersistenceFailed
        store.announcements shouldBe 0
        // No alarm for a reminder that is not in the store: that is the reported bug.
        executor.effects shouldBe emptyList()
        store.read() shouldBe stored
    }

    test("a reconciliation whose commit fails is a typed failure and delivers nothing") {
        val stored = StoredReminders(listOf(scheduled(NOW - 60_000)), nextId = 4)
        val store = FakeStore(stored, writeSucceeds = false)
        val executor = RecordingExecutor()
        val runner = ReminderCommandRunner(store, executor) { NOW }

        val result = runner.reconcileAll()

        result shouldBe PersistenceFailed
        store.announcements shouldBe 0
        executor.effects shouldBe emptyList()
        store.read() shouldBe stored
    }

    test("a command after a failed write works normally") {
        val stored = StoredReminders(listOf(scheduled(NOW + 60_000)), nextId = 4)
        val store = FakeStore(stored, writeSucceeds = false)
        val executor = RecordingExecutor()
        val runner = ReminderCommandRunner(store, executor) { NOW }

        runner.run(ReminderCommand.MarkDone(2)) shouldBe PersistenceFailed
        store.writeSucceeds = true
        val result = runner.run(ReminderCommand.MarkDone(2))

        result shouldBe TransitionOutcome.Updated(
            scheduled(NOW + 60_000).copy(status = Status.DONE)
        )
        store.read().reminders.single().status shouldBe Status.DONE
        store.announcements shouldBe 1
        executor.effects shouldBe listOf(
            ReminderEffect.CancelAlarm(2),
            ReminderEffect.CancelNotification(2)
        )
    }

    // A failed write must leave the store as it was, or the reminder it half-wrote
    // haunts every later command in the same process.

    test("an add whose commit fails leaves the id for the next add to take") {
        val store = FakeStore(StoredReminders(emptyList(), nextId = 4), writeSucceeds = false)
        val executor = RecordingExecutor()
        val runner = ReminderCommandRunner(store, executor) { NOW }

        runner.add(NOW + 60_000, "Call the plumber", naggingRepeatInterval = 0) shouldBe
            PersistenceFailed
        store.writeSucceeds = true
        val outcome = runner.add(NOW + 120_000, "Call the plumber", naggingRepeatInterval = 0)

        val added = (outcome as TransitionOutcome.Updated).reminder
        // The failed add allocated nothing: the id it was given is still free, and it
        // left no phantom reminder behind for this write to persist without an alarm.
        added.id shouldBe 4
        store.read() shouldBe StoredReminders(listOf(added), nextId = 6)
        executor.effects shouldBe listOf(
            ReminderEffect.SetAlarm(4, NOW + 120_000, AlarmKind.DELIVER, NOW + 120_000)
        )
    }

    test("a deliver whose commit fails leaves the reminder for reconcile to deliver once") {
        val dueTime = NOW - 60_000
        val stored = StoredReminders(listOf(scheduled(dueTime)), nextId = 4)
        val store = FakeStore(stored, writeSucceeds = false)
        val executor = RecordingExecutor()
        val runner = ReminderCommandRunner(store, executor) { NOW }

        runner.run(ReminderCommand.Deliver(2, dueTime)) shouldBe PersistenceFailed
        // Still scheduled, so the consumed alarm is not the only chance left.
        store.read() shouldBe stored
        store.read().reminders.single().status shouldBe Status.SCHEDULED

        store.writeSucceeds = true
        runner.reconcileAll()

        executor.effects.filterIsInstance<ReminderEffect.ShowNotification>() shouldHaveSize 1
        store.read().reminders.single().status shouldBe Status.NOTIFIED
    }

    test("a mark done whose commit fails leaves its own reminder notified") {
        val first = scheduled(NOW - 60_000).copy(status = Status.NOTIFIED)
        val second = first.copy(id = 4, text = "Take the bins out")
        val store = FakeStore(
            StoredReminders(listOf(first, second), nextId = 6),
            writeSucceeds = false
        )
        val executor = RecordingExecutor()
        val runner = ReminderCommandRunner(store, executor) { NOW }

        // Two reminders selected in the list; the write fails for the first one only.
        runner.run(ReminderCommand.MarkDone(2)) shouldBe PersistenceFailed
        store.writeSucceeds = true
        runner.run(ReminderCommand.MarkDone(4)) shouldBe
            TransitionOutcome.Updated(second.copy(status = Status.DONE))

        val reminders = store.read().reminders.associateBy { it.id }
        reminders.getValue(2).status shouldBe Status.NOTIFIED
        reminders.getValue(4).status shouldBe Status.DONE
    }
})

/** The one scheduled reminder the payloads above name, due at [dueTime]. */
private fun scheduled(dueTime: Long) = Reminder(
    id = 2,
    date = Date(dueTime),
    text = "Water the plants",
    status = Status.SCHEDULED
)

/** The command a payload asks for, as the broadcast receiver would work it out. */
private fun commandIn(payload: String): ReminderCommand =
    requireNotNull(ReminderAction.fromJsonOrNull(payload)).toCommand()

/** A fixed clock: 2026-09-05T12:00:00Z. */
private const val NOW = 1788609600000L

/**
 * A store a test can drive. Turn [writeSucceeds] off to make the commit fail, the
 * way shared preferences do when the durable write does not go through.
 *
 * The commit is modelled the way shared preferences really behave: the new values
 * become visible to every later read *before* the durable write is attempted, and
 * a failed durable write does not take them back. Honouring the [ReminderStore]
 * contract on top of that is the store's own job, so this fake does it the same
 * way the shared-preferences adapter does.
 */
private class FakeStore(
    private var stored: StoredReminders,
    var writeSucceeds: Boolean = true
) : ReminderStore {
    val writes = mutableListOf<StoredReminders>()
    var announcements = 0

    override fun read(): StoredReminders = stored

    override fun write(stored: StoredReminders): Boolean {
        val result = writeWithRollback(this.stored, stored, ::commit)
        if (result.committed) {
            writes.add(stored)
        }
        return result.committed
    }

    /** Visible first, durable second — and a failed durable write keeps the new values. */
    private fun commit(values: StoredReminders): Boolean {
        stored = values
        return writeSucceeds
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
