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
        store.read().stored.reminders.single().status shouldBe Status.NOTIFIED
    }

    test("adding a reminder allocates the next even id and moves the counter") {
        val store = FakeStore(StoredReminders(emptyList(), nextId = 4))
        val executor = RecordingExecutor()
        val runner = ReminderCommandRunner(store, executor) { NOW }

        val outcome = runner.add(NOW + 60_000, "Call the plumber", naggingRepeatInterval = 0)

        val added = (outcome as TransitionOutcome.Updated).reminder
        added.id shouldBe 4
        store.read().stored shouldBe StoredReminders(listOf(added), nextId = 6)
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
        store.read().stored.reminders.single().status shouldBe Status.NOTIFIED
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
        store.read().stored shouldBe stored
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
        store.read().stored shouldBe stored
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
        store.read().stored shouldBe stored
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
        store.read().stored.reminders.single().status shouldBe Status.DONE
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
        store.read().stored shouldBe StoredReminders(listOf(added), nextId = 6)
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
        store.read().stored shouldBe stored
        store.read().stored.reminders.single().status shouldBe Status.SCHEDULED

        store.writeSucceeds = true
        runner.reconcileAll()

        executor.effects.filterIsInstance<ReminderEffect.ShowNotification>() shouldHaveSize 1
        store.read().stored.reminders.single().status shouldBe Status.NOTIFIED
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

        val reminders = store.read().stored.reminders.associateBy { it.id }
        reminders.getValue(2).status shouldBe Status.NOTIFIED
        reminders.getValue(4).status shouldBe Status.DONE
    }

    // Test 13 of `docs/reminder-state-machine.md`: a store that fails to decode does
    // not throw out of Reconcile, and the raw value survives for recovery.

    test("reconcile over a store that cannot be read does not throw and sets the raw value aside") {
        val raw = """[{"id":2,"date":1788609540000,"text":"Water the pl"""
        val store = QuarantiningFakeStore(raw)
        val executor = RecordingExecutor()
        val runner = ReminderCommandRunner(store, executor) { NOW }

        val result = runner.reconcileAll()

        // The runner sees an empty store: there is nothing to change and nothing to
        // do, which is the sweep's Unchanged answer for every reminder it can see.
        result shouldBe ReconcileResult.Reconciled(emptyList())
        executor.effects shouldBe emptyList()
        store.quarantined?.raw shouldBe raw
    }

    test("an alarm that arrives while the store cannot be read cleans up instead of crashing") {
        val raw = "this is not JSON"
        val store = QuarantiningFakeStore(raw)
        val executor = RecordingExecutor()
        val runner = ReminderCommandRunner(store, executor) { NOW }

        // The alarm finds no reminder, so it takes the missing-reminder cleanup path.
        val result = runner.run(ReminderCommand.Deliver(2, NOW - 60_000))

        result shouldBe TransitionOutcome.Unchanged
        executor.effects shouldBe listOf(
            ReminderEffect.CancelAlarm(2),
            ReminderEffect.CancelNotification(2)
        )
        store.quarantined?.raw shouldBe raw
    }

    test("a reminder added after the store was set aside does not touch the value set aside") {
        val raw = "this is not JSON"
        val store = QuarantiningFakeStore(raw)
        val executor = RecordingExecutor()
        val runner = ReminderCommandRunner(store, executor) { NOW }

        runner.reconcileAll()
        val outcome = runner.add(NOW + 60_000, "Call the plumber", naggingRepeatInterval = 0)

        val added = (outcome as TransitionOutcome.Updated).reminder
        store.read().stored.reminders shouldBe listOf(added)
        store.announcements shouldBe 1
        // The app is usable again and the unreadable value is still there in full.
        store.quarantined?.raw shouldBe raw
    }

    test("a read reports a store it cannot read, and the add after it sets that value aside first") {
        val raw = "this is not JSON"
        val store = QuarantiningFakeStore(raw)
        val executor = RecordingExecutor()
        val runner = ReminderCommandRunner(store, executor) { NOW }

        // The list reads the store between two commands. It gets the empty view and a
        // flag, and writes nothing at all: a set-aside from a read is a write outside
        // the runner's lock, which is how it lands on top of a command in flight.
        val reading = store.read()
        reading.stored shouldBe StoredReminders(emptyList(), nextId = 4)
        reading.unreadable shouldBe UnreadableReason.MALFORMED_JSON
        store.quarantined shouldBe null
        store.log shouldBe emptyList()

        val outcome = runner.add(NOW + 60_000, "Call the plumber", naggingRepeatInterval = 0)

        // Set aside first, added second: the reminder cannot be overwritten by a
        // recovery that comes after it.
        store.log shouldBe listOf("set aside", "write")
        val added = (outcome as TransitionOutcome.Updated).reminder
        store.read().stored.reminders shouldBe listOf(added)
        store.quarantined?.raw shouldBe raw
    }

    test("a set-aside that does not commit is a typed failure, and the next command tries it again") {
        val raw = "this is not JSON"
        val store = QuarantiningFakeStore(raw, setAsideSucceeds = false)
        val executor = RecordingExecutor()
        val runner = ReminderCommandRunner(store, executor) { NOW }

        val result = runner.reconcileAll()

        // Nothing was kept, so nothing may be acted on either: the sweep fails rather
        // than carrying on as if the reminders had been recovered.
        result shouldBe PersistenceFailed
        executor.effects shouldBe emptyList()
        store.announcements shouldBe 0
        store.quarantined shouldBe null
        store.read().unreadable shouldBe UnreadableReason.MALFORMED_JSON

        store.setAsideSucceeds = true
        val outcome = runner.add(NOW + 60_000, "Call the plumber", naggingRepeatInterval = 0)

        store.quarantined?.raw shouldBe raw
        store.read().stored.reminders shouldBe
            listOf((outcome as TransitionOutcome.Updated).reminder)
    }

    test("an id counter that cannot be read does not let the next add replace a stored reminder") {
        val stored = listOf(
            scheduled(NOW + 60_000),
            scheduled(NOW + 120_000).copy(id = 0, text = "Take the bins out")
        )
        // The counter is of another type: a read that answered 0 would hand the next
        // add the id the second reminder already holds.
        val store = QuarantiningFakeStore(Reminder.toJson(stored), nextId = null)
        val executor = RecordingExecutor()
        val runner = ReminderCommandRunner(store, executor) { NOW }

        val outcome = runner.add(NOW + 180_000, "Call the plumber", naggingRepeatInterval = 0)

        val added = (outcome as TransitionOutcome.Updated).reminder
        added.id shouldBe 4
        store.read().stored.reminders shouldBe stored + added
    }

    test("a mark done whose cancels never ran is repaired by the next reconciliation") {
        // Persist first, then effects: between the write and the cancels the process
        // can die, or a cancel can fail. What is left is a reminder that is done on
        // disk with its nag alarm still in the slot and its notification still on
        // screen. The next start has to clean that up, because nothing else will.
        val dueTime = NOW - 60_000
        val notified = scheduled(dueTime).copy(naggingRepeatInterval = 5, status = Status.NOTIFIED)
        val store = FakeStore(StoredReminders(listOf(notified), nextId = 4))

        ReminderCommandRunner(store, EffectsThatNeverRan()) { NOW }
            .run(ReminderCommand.MarkDone(2))
        store.read().stored.reminders.single().status shouldBe Status.DONE

        // The next process start, over the same store.
        val executor = RecordingExecutor()
        ReminderCommandRunner(store, executor) { NOW }.reconcileAll()

        executor.effects shouldBe listOf(
            ReminderEffect.CancelAlarm(2),
            ReminderEffect.CancelNotification(2)
        )
    }

    // An id is the identity of a reminder, of its notification and of both its pending
    // intents at once, so an id handed out once may never be handed out again — not even
    // after the reminders that held it have gone from the store. Their notifications and
    // alarms are still in the OS, and a new reminder given an old id would inherit them:
    // the swipe on the old notification would mark the new reminder done.

    test("an add after a quarantine takes an id above every id the old store held") {
        val old = listOf(
            Reminder(id = 0, date = Date(NOW - 60_000), text = "Water the plants"),
            Reminder(id = 2, date = Date(NOW - 60_000), text = "Take the bins out")
        )
        // Readable counter, unreadable reminders: the JSON is truncated.
        val store = QuarantiningFakeStore(
            Reminder.toJson(old).dropLast(4),
            nextId = 4
        )
        val executor = RecordingExecutor()
        val runner = ReminderCommandRunner(store, executor) { NOW }

        runner.reconcileAll()
        val outcome = runner.add(NOW + 60_000, "Call the plumber", naggingRepeatInterval = 0)

        val added = (outcome as TransitionOutcome.Updated).reminder
        added.id shouldBe 4
        old.none { it.id == added.id } shouldBe true
    }

    test("an add after a quarantine whose counter is also corrupt still takes a fresh id") {
        val old = listOf(
            Reminder(id = 0, date = Date(NOW - 60_000), text = "Water the plants"),
            Reminder(id = 6, date = Date(NOW - 60_000), text = "Take the bins out")
        )
        // Neither the reminders nor the counter can be read: the ids in the raw value
        // are the only record of what was handed out.
        val store = QuarantiningFakeStore(Reminder.toJson(old).dropLast(4), nextId = null)
        val executor = RecordingExecutor()
        val runner = ReminderCommandRunner(store, executor) { NOW }

        runner.reconcileAll()
        val outcome = runner.add(NOW + 60_000, "Call the plumber", naggingRepeatInterval = 0)

        val added = (outcome as TransitionOutcome.Updated).reminder
        added.id shouldBe 8
        old.none { it.id == added.id } shouldBe true
        store.quarantined?.raw shouldBe Reminder.toJson(old).dropLast(4)
    }

    test("a second quarantine does not give back the ids the first one moved past") {
        val store = QuarantiningFakeStore("""[{"id":10,"date":1,"text":"x"}""", nextId = null)
        val executor = RecordingExecutor()
        val runner = ReminderCommandRunner(store, executor) { NOW }

        runner.reconcileAll()
        // Damaged again after the app has been running on the store the first failure
        // left it with. The counter is the only thing carrying the old ids now.
        store.rawJson = "the second damage"
        runner.reconcileAll()
        val outcome = runner.add(NOW + 60_000, "Call the plumber", naggingRepeatInterval = 0)

        (outcome as TransitionOutcome.Updated).reminder.id shouldBe 12
    }

    // There are only so many ids: 0 to MAX_REMINDER_ID, two apart. A store that has
    // handed out the last of them holds EXHAUSTED_ID_COUNTER, and there is nothing left
    // to give. Add says so; it does not wind the counter back onto an id this install
    // has already used, and it does not build a reminder Reminder itself refuses.

    test("handing out the last id leaves none to give, and the next add is refused") {
        val store = FakeStore(StoredReminders(emptyList(), nextId = Reminder.MAX_REMINDER_ID))
        val executor = RecordingExecutor()
        val runner = ReminderCommandRunner(store, executor) { NOW }

        val added = runner.add(NOW + 60_000, "Water the plants", naggingRepeatInterval = 0)
        val refused = runner.add(NOW + 60_000, "Call the plumber", naggingRepeatInterval = 0)

        (added as TransitionOutcome.Updated).reminder.id shouldBe Reminder.MAX_REMINDER_ID
        // The counter the store is left with is the one it will be read back with.
        store.read().stored.nextId shouldBe EXHAUSTED_ID_COUNTER
        refused shouldBe TransitionOutcome.Refused(RefusalReason.IdSpaceExhausted)
        store.writes shouldHaveSize 1
        store.read().stored.reminders shouldHaveSize 1
    }

    test("the largest id there is anywhere in a quarantined value refuses the next add") {
        // The id is not even a reminder's own here: it is nested in a shape that is not
        // a list of reminders, so the store cannot be read at all. The scan counts it
        // anyway, because erring upwards only ever skips ids that were never used — and
        // upwards from the largest id there is leaves nothing to give.
        val store = QuarantiningFakeStore(
            """{"backup":[{"id":${Reminder.MAX_REMINDER_ID},"date":1,"text":"x"}]}""",
            nextId = null
        )
        val executor = RecordingExecutor()
        val runner = ReminderCommandRunner(store, executor) { NOW }

        runner.reconcileAll()
        val outcome = runner.add(NOW + 60_000, "Call the plumber", naggingRepeatInterval = 0)

        outcome shouldBe TransitionOutcome.Refused(RefusalReason.IdSpaceExhausted)
        store.read().stored.nextId shouldBe EXHAUSTED_ID_COUNTER
        executor.effects shouldBe emptyList()
    }

    test("a counter above the mark for having none left refuses the next add") {
        // Only the counter is out of range here; the store itself reads fine and is
        // empty. Reading a counter this app could not have written as no evidence at
        // all would leave the empty list to answer alone, and the next add would take
        // id 0 — an id whose notification, alarm and pending intents an older reminder
        // of this install may still hold.
        val store = QuarantiningFakeStore("[]", nextId = Int.MAX_VALUE)
        val executor = RecordingExecutor()
        val runner = ReminderCommandRunner(store, executor) { NOW }

        val outcome = runner.add(NOW + 60_000, "Call the plumber", naggingRepeatInterval = 0)

        outcome shouldBe TransitionOutcome.Refused(RefusalReason.IdSpaceExhausted)
        store.read().stored.nextId shouldBe EXHAUSTED_ID_COUNTER
        executor.effects shouldBe emptyList()
    }

    test("a quarantine does not give back the ids a counter above the mark stands for") {
        val store = QuarantiningFakeStore("the damage", nextId = EXHAUSTED_ID_COUNTER + 2)
        val executor = RecordingExecutor()
        val runner = ReminderCommandRunner(store, executor) { NOW }

        runner.reconcileAll()
        val outcome = runner.add(NOW + 60_000, "Call the plumber", naggingRepeatInterval = 0)

        outcome shouldBe TransitionOutcome.Refused(RefusalReason.IdSpaceExhausted)
        store.read().stored.nextId shouldBe EXHAUSTED_ID_COUNTER
        executor.effects shouldBe emptyList()
    }

    // Two stored reminders sharing an id cannot both be run on: they share one alarm
    // slot, one notification and one pending-intent request code. Reconcile over a
    // future SCHEDULED one and a DONE one would set the alarm for the first and cancel
    // it for the second, leaving the reminder that must fire with an empty slot.

    test("two reminders sharing an id are set aside before any effect runs") {
        val raw = Reminder.toJson(
            listOf(
                Reminder(
                    id = 2,
                    date = Date(NOW + 60_000),
                    text = "Water the plants",
                    status = Status.SCHEDULED
                ),
                Reminder(
                    id = 2,
                    date = Date(NOW - 60_000),
                    text = "Take the bins out",
                    status = Status.DONE
                )
            )
        )
        val store = QuarantiningFakeStore(raw)
        val executor = RecordingExecutor()
        val runner = ReminderCommandRunner(store, executor) { NOW }

        val result = runner.reconcileAll()

        // No SetAlarm(2) followed by a CancelAlarm(2): the store never reaches the
        // transition function at all.
        result shouldBe ReconcileResult.Reconciled(emptyList())
        executor.effects shouldBe emptyList()
        store.quarantined?.raw shouldBe raw
    }

    test("the value set aside is the first one when a second store cannot be read") {
        val first = "the first damage"
        val store = QuarantiningFakeStore(first)
        val executor = RecordingExecutor()
        val runner = ReminderCommandRunner(store, executor) { NOW }

        runner.reconcileAll()
        // Damaged a second time, after the app has been running on the empty store
        // the first failure left it with.
        store.rawJson = "the second damage"
        runner.reconcileAll()

        store.quarantined?.raw shouldBe first
    }
    // One effect that fails may not take the effects after it down with it. The
    // notification is the one that fails in practice — a preference of the wrong type,
    // a channel the user has torn down, a notification manager that refuses the post —
    // and the alarms of every other reminder are in the same list behind it.

    test("an effect that throws does not stop the effects after it") {
        val notified = scheduled(NOW - 60_000).copy(
            naggingRepeatInterval = 5,
            status = Status.NOTIFIED
        )
        val store = FakeStore(StoredReminders(listOf(notified), nextId = 4))
        // Mark done cancels the alarm first and the notification second; here it is the
        // cancel of the alarm that fails.
        val executor = ExecutorThatFails { it is ReminderEffect.CancelAlarm }
        val failures = RecordedFailures()
        val runner = ReminderCommandRunner(store, executor, failures) { NOW }

        val result = runner.run(ReminderCommand.MarkDone(2))

        // The store committed, so the outcome is what was stored: the effects are not
        // what it answers for.
        result shouldBe TransitionOutcome.Updated(notified.copy(status = Status.DONE))
        executor.ran shouldBe listOf(ReminderEffect.CancelNotification(2))
        failures.effects shouldBe listOf(ReminderEffect.CancelAlarm(2))
    }

    test("a reconciliation whose notification fails still schedules the other reminders") {
        val dueTime = NOW - 60_000
        val delivered = scheduled(dueTime)
        val stored = StoredReminders(
            listOf(
                delivered,
                scheduled(NOW + 60_000).copy(id = 4, text = "Take the bins out"),
                scheduled(NOW + 120_000).copy(id = 6, text = "Call the plumber")
            ),
            nextId = 8
        )
        val store = FakeStore(stored)
        val executor = ExecutorThatFails { it is ReminderEffect.ShowNotification }
        val failures = RecordedFailures()
        val runner = ReminderCommandRunner(store, executor, failures) { NOW }

        val result = runner.reconcileAll()

        // The reminder whose notification could not be shown is delivered on disk all
        // the same, and the two reminders behind it in the sweep still get their alarms.
        result shouldBe ReconcileResult.Reconciled(
            listOf(
                TransitionOutcome.Updated(delivered.copy(status = Status.NOTIFIED)),
                TransitionOutcome.Unchanged,
                TransitionOutcome.Unchanged
            )
        )
        executor.ran shouldBe listOf(
            ReminderEffect.SetAlarm(4, NOW + 60_000, AlarmKind.DELIVER, NOW + 60_000),
            ReminderEffect.SetAlarm(6, NOW + 120_000, AlarmKind.DELIVER, NOW + 120_000)
        )
        failures.effects shouldBe listOf(
            ReminderEffect.ShowNotification(
                delivered.copy(status = Status.NOTIFIED),
                NotificationKind.DELIVER
            )
        )
    }

    test("the failure is reported with the effect it belongs to") {
        val dueTime = NOW - 60_000
        val nagging = scheduled(dueTime).copy(naggingRepeatInterval = 5)
        val store = FakeStore(StoredReminders(listOf(nagging), nextId = 4))
        // Every effect of the delivery fails, so each one has to be reported, each
        // paired with the failure that stopped it.
        val executor = ExecutorThatFails { true }
        val failures = RecordedFailures()
        val runner = ReminderCommandRunner(store, executor, failures) { NOW }

        runner.run(ReminderCommand.Deliver(2, dueTime))

        executor.ran shouldBe emptyList()
        failures.effects shouldBe listOf(
            ReminderEffect.ShowNotification(
                nagging.copy(status = Status.NOTIFIED),
                NotificationKind.DELIVER
            ),
            ReminderEffect.SetAlarm(2, dueTime + 300_000, AlarmKind.NAG, dueTime)
        )
        failures.failures.map { it.message } shouldBe
            List(2) { "the effect could not be carried out" }
    }

    test("an effect names itself by what it does and to which reminder, never by its text") {
        // What the app writes into the log when an effect fails. The reminder's own
        // text is the user's words and stays out of it; the id is what follows one
        // reminder through the log.
        val reminder = scheduled(NOW).copy(text = "Ring the doctor about the results")

        ReminderEffect.ShowNotification(reminder, NotificationKind.NAG).describe() shouldBe
            "ShowNotification(reminder 2, NAG)"
        ReminderEffect.SetAlarm(2, NOW, AlarmKind.DELIVER, NOW).describe() shouldBe
            "SetAlarm(reminder 2, DELIVER, at $NOW)"
        ReminderEffect.CancelAlarm(2).describe() shouldBe "CancelAlarm(reminder 2)"
        ReminderEffect.CancelNotification(2).describe() shouldBe
            "CancelNotification(reminder 2)"
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

    override fun read(): StoreReading = StoreReading(stored)

    /** This store is always readable, so the runner never asks. */
    override fun setAsideUnreadable(): StoredReminders =
        error("There is nothing to set aside.")

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

/**
 * A store that keeps its reminders the way the shared-preferences adapter does: as raw
 * JSON, a format version and an id counter, decoded on every read, with a value that
 * cannot be decoded set aside under keys of its own. The decisions it shares with the
 * adapter are the pure ones — [readStore], [quarantineToKeep] and [writeWithRollback] —
 * so what these tests pin down is the runner's behaviour over a store it cannot read.
 *
 * Reading changes nothing here either. Turn [setAsideSucceeds] off to make the
 * set-aside commit fail, the way shared preferences do when the durable write does not
 * go through.
 */
private class QuarantiningFakeStore(
    var rawJson: String?,
    private var formatVersion: Int? = KNOWN_STORED_REMINDERS_FORMAT_VERSION,
    private var nextId: Int? = 4,
    var setAsideSucceeds: Boolean = true
) : ReminderStore {
    var quarantined: QuarantinedReminders? = null
        private set
    var announcements = 0
    private var setAsides = 0

    /** What this store was asked to write, in order. */
    val log = mutableListOf<String>()

    override fun read(): StoreReading =
        readStore({ formatVersion }, { rawJson }, { nextId })

    override fun setAsideUnreadable(): StoredReminders? {
        log.add("set aside")
        setAsides++
        val previous = values()
        val keep = quarantineToKeep(
            existing = quarantined,
            candidate = QuarantinedReminders(
                raw = rawJson,
                formatVersion = formatVersion,
                quarantinedAt = NOW + setAsides
            )
        )
        // The counter is the one thing a set-aside does not empty: the ids it handed out
        // are still live in the OS as notifications, alarms and pending intents.
        val nextId = nextIdAfterQuarantine(previous.nextId, rawJson)
        // Emptied in the same commit, so the app runs on an empty store rather than an
        // unreadable one and no later write can land on the value set aside.
        val result = writeWithRollback(
            previous = previous,
            next = Values("[]", KNOWN_STORED_REMINDERS_FORMAT_VERSION, nextId, keep),
            commit = ::commit
        )
        return if (result.committed) StoredReminders(emptyList(), nextId = nextId) else null
    }

    override fun write(stored: StoredReminders): Boolean {
        log.add("write")
        commit(
            Values(
                Reminder.toJson(stored.reminders),
                KNOWN_STORED_REMINDERS_FORMAT_VERSION,
                stored.nextId,
                quarantined
            )
        )
        return true
    }

    override fun announceChange() {
        announcements++
    }

    /** Everything a set-aside touches, so that a failed one puts all of it back. */
    private data class Values(
        val rawJson: String?,
        val formatVersion: Int?,
        val nextId: Int?,
        val quarantined: QuarantinedReminders?
    )

    private fun values() = Values(rawJson, formatVersion, nextId, quarantined)

    /** Visible first, durable second — and a failed durable write keeps the new values. */
    private fun commit(values: Values): Boolean {
        rawJson = values.rawJson
        formatVersion = values.formatVersion
        nextId = values.nextId
        quarantined = values.quarantined
        return setAsideSucceeds
    }
}

/** An executor that does nothing: the effects of a command that the process never ran. */
private class EffectsThatNeverRan : ReminderEffectExecutor {
    override fun execute(effect: ReminderEffect) = Unit
}

private class RecordingExecutor : ReminderEffectExecutor {
    val effects = mutableListOf<ReminderEffect>()
    override fun execute(effect: ReminderEffect) {
        effects.add(effect)
    }
}

/**
 * An executor that fails on the effects [fails] picks out and records the rest, so that
 * a test can see which effects still ran after one of them threw.
 */
private class ExecutorThatFails(private val fails: (ReminderEffect) -> Boolean) :
    ReminderEffectExecutor {
    val ran = mutableListOf<ReminderEffect>()

    override fun execute(effect: ReminderEffect) {
        if (fails(effect)) {
            throw IllegalStateException("the effect could not be carried out")
        }
        ran.add(effect)
    }
}

/** What the app logs, kept instead: the effects that could not be carried out. */
private class RecordedFailures : (ReminderEffect, Exception) -> Unit {
    val effects = mutableListOf<ReminderEffect>()
    val failures = mutableListOf<Exception>()

    override fun invoke(effect: ReminderEffect, failure: Exception) {
        effects.add(effect)
        failures.add(failure)
    }
}
