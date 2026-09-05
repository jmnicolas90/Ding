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
import io.kotest.matchers.shouldBe
import java.util.Date

/**
 * Every stored value the app can meet on a process start, decoded. Nothing here
 * throws: a value that cannot be read is an answer, not an exception, because the
 * caller is `Main.onCreate` and an exception there is a startup crash loop.
 */
class StoredReminderDecodingTest : FunSpec({

    test("a valid store decodes to its reminders") {
        val reminders = listOf(
            Reminder(id = 2, date = Date(NOW), text = "Water the plants"),
            Reminder(id = 4, date = Date(NOW), text = "Take the bins out", status = Status.NOTIFIED)
        )

        decodeStoredReminders(
            formatVersion = KNOWN_STORED_REMINDERS_FORMAT_VERSION,
            rawJson = Reminder.toJson(reminders)
        ) shouldBe DecodeResult.Readable(reminders)
    }

    test("an empty list is a readable store with no reminders") {
        decodeStoredReminders(KNOWN_STORED_REMINDERS_FORMAT_VERSION, "[]") shouldBe
            DecodeResult.Readable(emptyList())
    }

    test("nothing stored yet is empty rather than unreadable") {
        decodeStoredReminders(formatVersion = null, rawJson = null) shouldBe DecodeResult.Empty
        decodeStoredReminders(KNOWN_STORED_REMINDERS_FORMAT_VERSION, null) shouldBe DecodeResult.Empty
    }

    test("a value that is not JSON at all is unreadable") {
        val raw = "this is not JSON"

        decodeStoredReminders(KNOWN_STORED_REMINDERS_FORMAT_VERSION, raw) shouldBe
            DecodeResult.Unreadable(UnreadableReason.MALFORMED_JSON, raw)
    }

    test("a truncated value is unreadable") {
        val raw = """[{"id":2,"date":1788609600000,"text":"Water the pl"""

        decodeStoredReminders(KNOWN_STORED_REMINDERS_FORMAT_VERSION, raw) shouldBe
            DecodeResult.Unreadable(UnreadableReason.MALFORMED_JSON, raw)
    }

    test("an empty value is unreadable rather than an empty store") {
        // The store writes "[]" for no reminders, never "", so a "" is damage.
        decodeStoredReminders(KNOWN_STORED_REMINDERS_FORMAT_VERSION, "") shouldBe
            DecodeResult.Unreadable(UnreadableReason.MALFORMED_JSON, "")
    }

    test("JSON that is not a list of reminders is a schema mismatch") {
        val raw = """{"reminders":[]}"""

        decodeStoredReminders(KNOWN_STORED_REMINDERS_FORMAT_VERSION, raw) shouldBe
            DecodeResult.Unreadable(UnreadableReason.SCHEMA_MISMATCH, raw)
    }

    test("a reminder missing a field the schema requires is a schema mismatch") {
        val raw = """[{"id":2}]"""

        decodeStoredReminders(KNOWN_STORED_REMINDERS_FORMAT_VERSION, raw) shouldBe
            DecodeResult.Unreadable(UnreadableReason.SCHEMA_MISMATCH, raw)
    }

    test("a field of the wrong JSON type is a schema mismatch") {
        val raw = """[{"id":2,"date":"tomorrow","text":"Water the plants"}]"""

        decodeStoredReminders(KNOWN_STORED_REMINDERS_FORMAT_VERSION, raw) shouldBe
            DecodeResult.Unreadable(UnreadableReason.SCHEMA_MISMATCH, raw)
    }

    // `Reminder`'s init block requires an even id in 0..MAX_REMINDER_ID, so these two
    // fail construction rather than deserialization.

    test("an odd id is an invalid reminder, not a crash") {
        val raw = """[{"id":3,"date":1788609600000,"text":"Water the plants"}]"""

        decodeStoredReminders(KNOWN_STORED_REMINDERS_FORMAT_VERSION, raw) shouldBe
            DecodeResult.Unreadable(UnreadableReason.INVALID_REMINDER, raw)
    }

    test("an out-of-range id is an invalid reminder, not a crash") {
        val raw = """[{"id":-2,"date":1788609600000,"text":"Water the plants"}]"""
        val tooLarge =
            """[{"id":${Reminder.MAX_REMINDER_ID + 2},"date":1788609600000,"text":"x"}]"""

        decodeStoredReminders(KNOWN_STORED_REMINDERS_FORMAT_VERSION, raw) shouldBe
            DecodeResult.Unreadable(UnreadableReason.INVALID_REMINDER, raw)
        decodeStoredReminders(KNOWN_STORED_REMINDERS_FORMAT_VERSION, tooLarge) shouldBe
            DecodeResult.Unreadable(UnreadableReason.INVALID_REMINDER, tooLarge)
    }

    test("a nag interval past the model's bound is an invalid reminder, not a crash") {
        // A value an older build could write: its settings input and its number picker
        // both took anything up to Int.MAX_VALUE. Setting it aside is the right answer
        // for this field too — the bound is part of the model, the store is the source
        // of truth, and the raw JSON the user is offered still holds the text and the
        // due time. Silently clamping it here would be a write the user never made.
        val raw =
            """[{"id":2,"date":1788609600000,"naggingRepeatInterval":100000,"text":"x"}]"""

        decodeStoredReminders(KNOWN_STORED_REMINDERS_FORMAT_VERSION, raw) shouldBe
            DecodeResult.Unreadable(UnreadableReason.INVALID_REMINDER, raw)
    }

    test("a format version from a newer build is not decoded at all") {
        // Valid JSON for this build, but a build that wrote version 2 may mean
        // something else by it. Reading it as version 1 is how a downgrade eats
        // reminders, so it is set aside instead.
        val raw = Reminder.toJson(listOf(Reminder(2, Date(NOW), text = "Water the plants")))

        decodeStoredReminders(
            formatVersion = KNOWN_STORED_REMINDERS_FORMAT_VERSION + 1,
            rawJson = raw
        ) shouldBe DecodeResult.Unreadable(UnreadableReason.NEWER_FORMAT_VERSION, raw)
    }

    test("a format version this build never wrote is unreadable") {
        val raw = "[]"

        decodeStoredReminders(formatVersion = 0, rawJson = raw) shouldBe
            DecodeResult.Unreadable(UnreadableReason.SCHEMA_MISMATCH, raw)
    }

    test("a missing format version is read as this build's own") {
        val raw = "[]"

        decodeStoredReminders(formatVersion = null, rawJson = raw) shouldBe
            DecodeResult.Readable(emptyList())
    }

    // Shared preferences throw when the stored value is of another type than the
    // read asks for, which a hand-edited or half-migrated preferences file can be.

    test("a stored value of the wrong type is unreadable") {
        decodeStoredReminders(
            readFormatVersion = { KNOWN_STORED_REMINDERS_FORMAT_VERSION },
            readRawJson = { throw ClassCastException("Integer cannot be cast to String") }
        ) shouldBe DecodeResult.Unreadable(UnreadableReason.WRONG_TYPE, null)
    }

    test("a format version of the wrong type is unreadable, and keeps the raw value") {
        val raw = "[]"

        decodeStoredReminders(
            readFormatVersion = { throw ClassCastException("String cannot be cast to Integer") },
            readRawJson = { raw }
        ) shouldBe DecodeResult.Unreadable(UnreadableReason.WRONG_TYPE, raw)
    }

    test("reading through the lambdas is the same decision as reading through the values") {
        decodeStoredReminders(
            readFormatVersion = { KNOWN_STORED_REMINDERS_FORMAT_VERSION },
            readRawJson = { "[]" }
        ) shouldBe DecodeResult.Readable(emptyList())
    }

    // The quarantine keeps one value: the first one, because a second failure is
    // usually the damage the first one already caused.

    test("the quarantine keeps the value already set aside") {
        val first = QuarantinedReminders(raw = "the first damage", formatVersion = 1, quarantinedAt = NOW)
        val second = QuarantinedReminders(raw = "the second damage", formatVersion = 1, quarantinedAt = NOW + 1)

        quarantineToKeep(existing = first, candidate = second) shouldBe first
    }

    test("the quarantine takes the value when there is nothing set aside yet") {
        val candidate = QuarantinedReminders(raw = "the damage", formatVersion = 1, quarantinedAt = NOW)

        quarantineToKeep(existing = null, candidate = candidate) shouldBe candidate
    }
})

/** A fixed clock: 2026-09-05T12:00:00Z. */
private const val NOW = 1788609600000L
