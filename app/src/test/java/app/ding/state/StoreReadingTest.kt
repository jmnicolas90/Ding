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
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.util.Date

/**
 * Every preference the store reads, read the way the shared-preferences adapter reads
 * it: through functions that are allowed to throw [ClassCastException], because that is
 * what shared preferences do when a value is of another type than the read asks for.
 *
 * These are the adapter's own reading decisions, kept here so that they can be made on
 * a plain JVM. A value of the wrong type in any of them used to be an exception out of
 * `Main.onCreate` or out of the reminders list activity.
 */
class StoreReadingTest : FunSpec({

    test("a store this build wrote reads its reminders and its counter") {
        val reminders = listOf(reminder(2), reminder(4))

        readStore(
            readFormatVersion = { KNOWN_STORED_REMINDERS_FORMAT_VERSION },
            readRawJson = { Reminder.toJson(reminders) },
            readNextId = { 6 }
        ) shouldBe StoreReading(StoredReminders(reminders, nextId = 6))
    }

    test("a format version of the wrong type is an unreadable store, not an exception") {
        val reading = readStore(
            readFormatVersion = { throw ClassCastException("String cannot be cast to Integer") },
            readRawJson = { Reminder.toJson(listOf(reminder(2))) },
            readNextId = { 4 }
        )

        // What Main.onCreate used to do with this value was throw out of onCreate.
        reading.unreadable shouldBe UnreadableReason.WRONG_TYPE
        reading.stored shouldBe StoredReminders(emptyList(), nextId = 0)
    }

    test("a reminder list of the wrong type is an unreadable store, not an exception") {
        val reading = readStore(
            readFormatVersion = { KNOWN_STORED_REMINDERS_FORMAT_VERSION },
            readRawJson = { throw ClassCastException("Integer cannot be cast to String") },
            readNextId = { 4 }
        )

        reading.unreadable shouldBe UnreadableReason.WRONG_TYPE
        reading.stored shouldBe StoredReminders(emptyList(), nextId = 0)
    }

    test("an unreadable store takes its id counter with it") {
        // The counter allocates ids for reminders nobody can read, so it is reset with
        // them rather than kept to allocate against a store that is now empty.
        readStore(
            readFormatVersion = { KNOWN_STORED_REMINDERS_FORMAT_VERSION },
            readRawJson = { "this is not JSON" },
            readNextId = { 40 }
        ) shouldBe StoreReading(
            StoredReminders(emptyList(), nextId = 0),
            unreadable = UnreadableReason.MALFORMED_JSON
        )
    }

    test("nothing stored yet reads as an empty store") {
        readStore(
            readFormatVersion = { null },
            readRawJson = { null },
            readNextId = { null }
        ) shouldBe StoreReading(StoredReminders(emptyList(), nextId = 0))
    }

    // The id counter: never substituted with 0, because the next Add would take id 0
    // and replace whatever already holds it.

    test("an id counter of the wrong type is recomputed from the stored reminders") {
        val reminders = listOf(reminder(0), reminder(2))

        val reading = readStore(
            readFormatVersion = { KNOWN_STORED_REMINDERS_FORMAT_VERSION },
            readRawJson = { Reminder.toJson(reminders) },
            readNextId = { throw ClassCastException("String cannot be cast to Integer") }
        )

        reading.stored shouldBe StoredReminders(reminders, nextId = 4)
        reading.counterRepaired shouldBe true
    }

    test("an id counter that cannot be read gives an id no stored reminder has") {
        val reminders = listOf(reminder(0), reminder(2), reminder(6))

        forEachCorruptCounter { counter ->
            val nextId = nextIdToUse(counter, reminders)

            nextId shouldBe 8
            (nextId % 2) shouldBe 0
            (nextId in 0..Reminder.MAX_REMINDER_ID) shouldBe true
            reminders.none { it.id == nextId } shouldBe true
        }
    }

    test("an id counter that cannot be read starts again from 0 when there are no reminders") {
        forEachCorruptCounter { counter ->
            nextIdToUse(counter, emptyList()) shouldBe 0
        }
    }

    test("a counter a stored reminder has already reached is recomputed past it") {
        // Even and in range, but the store was written by something that let the two
        // drift apart: allocating from it would replace the reminder holding id 4.
        nextIdToUse(storedNextId = 2, reminders = listOf(reminder(2), reminder(4))) shouldBe 6
    }

    test("a counter that can still allocate is left alone") {
        val reminders = listOf(reminder(0), reminder(2))

        val reading = readStore(
            readFormatVersion = { KNOWN_STORED_REMINDERS_FORMAT_VERSION },
            readRawJson = { Reminder.toJson(reminders) },
            readNextId = { 40 }
        )

        // A gap in the ids is not damage: an id is never reused, and 40 allocates fine.
        reading.stored.nextId shouldBe 40
        reading.counterRepaired shouldBe false
    }

    // The value set aside: read on the way in to the set-aside and every time the
    // reminders list opens, so none of its three keys may throw.

    test("nothing set aside is nothing to offer the user") {
        readQuarantine(
            isSetAside = { false },
            readRaw = { "the damage" },
            readFormatVersion = { 1 },
            readQuarantinedAt = { NOW }
        ) shouldBe null
    }

    test("a value set aside is read with its version and its time") {
        readQuarantine(
            isSetAside = { true },
            readRaw = { "the damage" },
            readFormatVersion = { 1 },
            readQuarantinedAt = { NOW }
        ) shouldBe QuarantinedReminders(raw = "the damage", formatVersion = 1, quarantinedAt = NOW)
    }

    test("a raw value of the wrong type leaves the rest of the value set aside readable") {
        readQuarantine(
            isSetAside = { true },
            readRaw = { throw ClassCastException("Integer cannot be cast to String") },
            readFormatVersion = { 1 },
            readQuarantinedAt = { NOW }
        ) shouldBe QuarantinedReminders(raw = null, formatVersion = 1, quarantinedAt = NOW)
    }

    test("a format version of the wrong type is unknown rather than a crash") {
        readQuarantine(
            isSetAside = { true },
            readRaw = { "the damage" },
            readFormatVersion = { throw ClassCastException("String cannot be cast to Integer") },
            readQuarantinedAt = { NOW }
        ) shouldBe QuarantinedReminders(
            raw = "the damage",
            formatVersion = null,
            quarantinedAt = NOW
        )
    }

    test("a time of the wrong type is unknown rather than a crash") {
        readQuarantine(
            isSetAside = { true },
            readRaw = { "the damage" },
            readFormatVersion = { 1 },
            readQuarantinedAt = { throw ClassCastException("String cannot be cast to Long") }
        ) shouldBe QuarantinedReminders(
            raw = "the damage",
            formatVersion = 1,
            quarantinedAt = null
        )
    }

    test("a value set aside with no metadata at all is still offered to the user") {
        // The raw text is the part that matters; the rest is shown as unknown.
        readQuarantine(
            isSetAside = { true },
            readRaw = { "the damage" },
            readFormatVersion = { null },
            readQuarantinedAt = { null }
        ) shouldBe QuarantinedReminders(
            raw = "the damage",
            formatVersion = null,
            quarantinedAt = null
        )
    }
})

/** Every counter the store can hold that cannot allocate an id: the same answer for each. */
private fun forEachCorruptCounter(check: (Int?) -> Unit) {
    listOf(
        null, // of another type, or nothing at all
        3, // odd, so it is not an id this app ever allocated
        -2, // out of range
        Reminder.MAX_REMINDER_ID + 2 // past the largest id a reminder may have
    ).forEach(check)
}

private fun reminder(id: Int) = Reminder(id = id, date = Date(NOW), text = "Water the plants")

/** A fixed clock: 2026-09-05T12:00:00Z. */
private const val NOW = 1788609600000L
