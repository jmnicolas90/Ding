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
package app.ding

import app.ding.ReminderManager.ReminderAction
import app.ding.state.ReminderCommand
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * An alarm outlives the build that set it: after an upgrade, `AlarmManager` still
 * holds pending intents whose payload was written by the old code. These tests
 * pin the payloads the app has to keep reading, and pin that a payload it cannot
 * read is answered with null rather than an exception, because the answer is
 * consumed inside a broadcast receiver that must not crash.
 *
 * Reminder id 2 in the payloads below is the reminder the runner test stores.
 */
class ReminderActionTest : FunSpec({

    test("a Notify payload from before the rename decodes as a Deliver carrying no due time") {
        val action = ReminderAction.fromJsonOrNull(NOTIFY_PAYLOAD_BEFORE_THE_RENAME)

        action shouldBe ReminderAction.Deliver(2, expectedDueTime = null)
        action?.toCommand() shouldBe ReminderCommand.Deliver(2, expectedDueTime = null)
    }

    test("a Nag payload from before the rename decodes carrying no due time") {
        val action = ReminderAction.fromJsonOrNull(NAG_PAYLOAD_BEFORE_THE_RENAME)

        action shouldBe ReminderAction.Nag(2, expectedDueTime = null)
        action?.toCommand() shouldBe ReminderCommand.Nag(2, expectedDueTime = null)
    }

    test("a mark-done payload from before the rename still decodes") {
        ReminderAction.fromJsonOrNull(MARK_DONE_PAYLOAD_BEFORE_THE_RENAME) shouldBe
            ReminderAction.MarkDone(3)
    }

    test("a payload this build writes reads back as the action that wrote it") {
        val actions = listOf(
            ReminderAction.Deliver(2, 1_700_000_000_000),
            ReminderAction.Nag(2, 1_700_000_000_000),
            ReminderAction.MarkDone(3)
        )

        actions.map { ReminderAction.fromJsonOrNull(it.toJson()) } shouldBe actions
    }

    test("a payload that cannot be read is null, not an exception") {
        ReminderAction.fromJsonOrNull(null) shouldBe null
        ReminderAction.fromJsonOrNull("") shouldBe null
        ReminderAction.fromJsonOrNull("not a payload at all") shouldBe null
        ReminderAction.fromJsonOrNull("""["a list", "not an action"]""") shouldBe null
        ReminderAction.fromJsonOrNull("""{"reminderId":2}""") shouldBe null
        ReminderAction.fromJsonOrNull("""{"type":"Invent","reminderId":2}""") shouldBe null
    }
})

/** A Deliver alarm's payload as builds before the Notify to Deliver rename wrote it. */
internal const val NOTIFY_PAYLOAD_BEFORE_THE_RENAME =
    """{"type":"app.ding.ReminderManager.ReminderAction.Notify","reminderId":2}"""

/** A Nag alarm's payload as those builds wrote it. */
internal const val NAG_PAYLOAD_BEFORE_THE_RENAME =
    """{"type":"app.ding.ReminderManager.ReminderAction.Nag","reminderId":2}"""

/** A notification's mark-done payload as those builds wrote it. */
internal const val MARK_DONE_PAYLOAD_BEFORE_THE_RENAME =
    """{"type":"app.ding.ReminderManager.ReminderAction.MarkDone","reminderId":3}"""
