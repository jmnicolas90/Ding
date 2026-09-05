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
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * The one place stored reminders are turned back into reminders, and the one place
 * that decides what a stored value means when it is not what the app wrote.
 *
 * The store is read on every process start, before any component including the
 * alarm receiver, so an exception thrown here is not an error report: it is an app
 * that cannot be launched and cannot be repaired from the inside. Every failure
 * this decoding can meet is therefore an answer — [DecodeResult.Unreadable] — and
 * never something the caller has to catch. Reading itself is one of those failures:
 * shared preferences throw [ClassCastException] when the stored value is of another
 * type than the read asks for, so every value is read through a function that is
 * allowed to throw and is caught here.
 *
 * Like the transition function and the runner, this file has no Android imports, so
 * every stored value the app can meet is decided in a plain JVM test.
 */

/**
 * The version of the stored format this build writes and knows how to read. There
 * is one version, so there is no migration to write yet; the version switch in
 * [decodeStoredReminders] is where the first one goes.
 */
const val KNOWN_STORED_REMINDERS_FORMAT_VERSION = 1

/** What the stored value turned out to be. */
sealed interface DecodeResult {
    /** The stored value is this build's format and these are its reminders. */
    data class Readable(val reminders: List<Reminder>) : DecodeResult

    /** Nothing is stored yet: a first run, not damage. */
    data object Empty : DecodeResult

    /**
     * The stored value cannot be turned into reminders. [raw] is the value exactly as
     * it was read, which is what gets set aside so the user can still keep or send it;
     * it is null only when the value could not even be read as text.
     */
    data class Unreadable(val reason: UnreadableReason, val raw: String?) : DecodeResult
}

/** Why a stored value could not be read. Each one is a different repair. */
enum class UnreadableReason {
    /** Written by a build that knows a format this one does not. */
    NEWER_FORMAT_VERSION,

    /** Not JSON: damaged, truncated, or never JSON in the first place. */
    MALFORMED_JSON,

    /** JSON, but not a list of reminders of this build's shape. */
    SCHEMA_MISMATCH,

    /** A reminder JSON accepts but [Reminder] refuses, such as an odd or out-of-range id. */
    INVALID_REMINDER,

    /** The preference holds a value of another type, so it could not even be read as text. */
    WRONG_TYPE
}

/**
 * An unreadable stored value, set aside under its own keys so that nothing the app
 * does afterwards can write over it.
 */
data class QuarantinedReminders(
    /** The value as it was read, or null when it could not be read as text at all. */
    val raw: String?,
    /** The version the store recorded for it, or null when that is not known. */
    val formatVersion: Int?,
    /** When it was set aside, in epoch milliseconds, or null when that is not known. */
    val quarantinedAt: Long?
)

/**
 * The value that was set aside, read defensively: the raw text is the part that
 * matters, and metadata that cannot be read is unknown rather than a crash.
 *
 * This runs while a value is being set aside and again every time the reminders list
 * activity opens, so a malformed value here would take down the startup path and the
 * one screen that offers the user their data back.
 *
 * @param isSetAside whether anything is set aside at all. It is asked separately
 *   because a value whose own text cannot be read still has to be reported.
 * @return null when nothing is set aside.
 */
fun readQuarantine(
    isSetAside: () -> Boolean,
    readRaw: () -> String?,
    readFormatVersion: () -> Int?,
    readQuarantinedAt: () -> Long?
): QuarantinedReminders? {
    if (!isSetAside()) return null
    return QuarantinedReminders(
        raw = valueOrNullOnWrongType(readRaw),
        formatVersion = valueOrNullOnWrongType(readFormatVersion),
        quarantinedAt = valueOrNullOnWrongType(readQuarantinedAt)
    )
}

/**
 * One read of the store: the reminders to run on, the counter to allocate the next
 * id from, and whether the stored value could be read at all.
 *
 * It never writes. A value it cannot read is reported, not repaired, because the
 * repair is a write and every write goes through the runner's lock — see
 * [ReminderStore.setAsideUnreadable].
 *
 * @param readNextId the stored id counter, or null when nothing recorded one. It may
 *   throw [ClassCastException] like the other two.
 */
fun readStore(
    readFormatVersion: () -> Int?,
    readRawJson: () -> String?,
    readNextId: () -> Int?
): StoreReading {
    val counter = counterIn(readNextId)
    val reminders = when (val decoded = decodeStoredReminders(readFormatVersion, readRawJson)) {
        is DecodeResult.Readable -> decoded.reminders
        DecodeResult.Empty -> emptyList()
        is DecodeResult.Unreadable ->
            // The counter belongs to reminders nobody can read, so it goes with them:
            // the store the app runs on until the value is set aside is empty, and an
            // empty store allocates from 0.
            return StoreReading(
                stored = StoredReminders(emptyList(), nextId = 0),
                unreadable = decoded.reason
            )
    }
    val nextId = nextIdToUse(counter.value, reminders)
    return StoreReading(
        stored = StoredReminders(reminders, nextId),
        counterRepaired = !counter.readable || (counter.value != null && nextId != counter.value)
    )
}

/**
 * The counter the next reminder id is allocated from.
 *
 * A stored counter is used as it is when it can still do its one job: give an id that
 * is even, within range, and held by no stored reminder. Anything else — a value of
 * another type, an odd number, a number out of range, or one an existing reminder has
 * already passed — is recomputed from the reminders themselves, as the largest stored
 * id plus two, or 0 when there are none.
 *
 * Substituting 0 for a counter that cannot be read is the one thing this must not do:
 * the next Add would take id 0, and since the id is the identity of the reminder, of
 * its notification and of its alarms, it would replace whatever already holds it.
 * Recomputing keeps the only promise the id has to make.
 *
 * @param storedNextId null when the store holds no number at all.
 */
fun nextIdToUse(storedNextId: Int?, reminders: List<Reminder>): Int {
    val usable = storedNextId != null &&
        storedNextId % 2 == 0 &&
        storedNextId in 0..Reminder.MAX_REMINDER_ID &&
        reminders.none { it.id >= storedNextId }
    // Stored ids are even, so the largest one plus two is even as well.
    return if (usable) storedNextId else reminders.maxOfOrNull { it.id + 2 } ?: 0
}

/**
 * What the store holds for the id counter. A counter that is not there at all is a
 * first run, not damage; one that is there and of another type is damage worth
 * reporting, and the two look the same once the value is null.
 */
private class StoredCounter(val value: Int?, val readable: Boolean)

private fun counterIn(readNextId: () -> Int?): StoredCounter = try {
    StoredCounter(readNextId(), readable = true)
} catch (e: ClassCastException) {
    StoredCounter(value = null, readable = false)
}

/** What [read] gave, or null when the stored value is of another type than it asked for. */
private fun <T> valueOrNullOnWrongType(read: () -> T?): T? = try {
    read()
} catch (e: ClassCastException) {
    null
}

/**
 * Which value the quarantine holds once [candidate] turns up: whatever is already
 * there, if anything is.
 *
 * Only one value is kept, and it is the first one, because that is the one closest
 * to the reminders the user actually had. A later failure is usually a consequence
 * of the first — the empty store the app ran on afterwards, half-rewritten — and
 * letting it in would overwrite the only copy worth keeping.
 */
fun quarantineToKeep(
    existing: QuarantinedReminders?,
    candidate: QuarantinedReminders
): QuarantinedReminders = existing ?: candidate

/**
 * Decode the stored reminders, reading the two stored values through the given
 * functions because reading is itself a thing that can fail: shared preferences
 * throw [ClassCastException] when the stored value is of another type than the read
 * asks for, which a hand-edited or half-migrated preferences file can be.
 */
fun decodeStoredReminders(
    readFormatVersion: () -> Int?,
    readRawJson: () -> String?
): DecodeResult {
    val formatVersion = try {
        readFormatVersion()
    } catch (e: ClassCastException) {
        // The raw value may still be readable, and it is the half worth keeping.
        return DecodeResult.Unreadable(
            UnreadableReason.WRONG_TYPE,
            valueOrNullOnWrongType(readRawJson)
        )
    }
    val rawJson = try {
        readRawJson()
    } catch (e: ClassCastException) {
        return DecodeResult.Unreadable(UnreadableReason.WRONG_TYPE, null)
    }
    return decodeStoredReminders(formatVersion, rawJson)
}

/**
 * Decode the stored reminders from the two values as they were read: the format
 * version the store was written at, and the JSON itself.
 *
 * @param formatVersion null when nothing recorded one, which is read as this
 *   build's own version because that is what the store writes on first use.
 * @param rawJson null when nothing is stored at all.
 */
fun decodeStoredReminders(formatVersion: Int?, rawJson: String?): DecodeResult {
    if (rawJson == null) return DecodeResult.Empty
    val version = formatVersion ?: KNOWN_STORED_REMINDERS_FORMAT_VERSION
    return when {
        version == KNOWN_STORED_REMINDERS_FORMAT_VERSION -> decodeVersion1(rawJson)

        // A newer build may mean something else by the same JSON, so reading it as
        // version 1 is how a downgrade quietly eats reminders. Set aside instead.
        version > KNOWN_STORED_REMINDERS_FORMAT_VERSION ->
            DecodeResult.Unreadable(UnreadableReason.NEWER_FORMAT_VERSION, rawJson)

        // Version 1 is the oldest there has ever been, so anything below it is a
        // number this app never wrote. When a version 2 arrives, its migration is a
        // branch here: read the old version with its own decoder and write the result
        // back at the current one.
        else -> DecodeResult.Unreadable(UnreadableReason.SCHEMA_MISMATCH, rawJson)
    }
}

/**
 * Parsing and construction are two steps on purpose, so that the answer says which
 * one failed: text that is not JSON is different damage from JSON that is not a
 * reminder, and a reminder the schema accepts can still be one [Reminder] refuses.
 */
private fun decodeVersion1(rawJson: String): DecodeResult {
    val element = try {
        Json.parseToJsonElement(rawJson)
    } catch (e: SerializationException) {
        return DecodeResult.Unreadable(UnreadableReason.MALFORMED_JSON, rawJson)
    }
    return try {
        DecodeResult.Readable(
            Json.decodeFromJsonElement(ListSerializer(Reminder.serializer()), element)
        )
    } catch (e: SerializationException) {
        // A missing field, a field of the wrong type, a shape that is not a list.
        DecodeResult.Unreadable(UnreadableReason.SCHEMA_MISMATCH, rawJson)
    } catch (e: IllegalArgumentException) {
        // `Reminder`'s own require: an odd or out-of-range id fails construction
        // rather than deserialization. SerializationException is caught first
        // because it is itself an IllegalArgumentException.
        DecodeResult.Unreadable(UnreadableReason.INVALID_REMINDER, rawJson)
    }
}
