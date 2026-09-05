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
 * The one place stored reminders are turned back into reminders.
 *
 * The store is read on every process start, before any component including the
 * alarm receiver, so an exception thrown here is not an error report: it is an app
 * that cannot be launched and cannot be repaired from the inside. Every failure
 * this decoding can meet is therefore an answer — [DecodeResult.Unreadable] — and
 * never something the caller has to catch.
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
    val raw: String?,
    val formatVersion: Int?,
    val quarantinedAt: Long
)

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
        return DecodeResult.Unreadable(UnreadableReason.WRONG_TYPE, rawOrNull(readRawJson))
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

private fun rawOrNull(readRawJson: () -> String?): String? = try {
    readRawJson()
} catch (e: ClassCastException) {
    null
}
