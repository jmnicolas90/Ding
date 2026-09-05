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

/**
 * The value the id counter holds once the largest id a reminder may have has been
 * allocated: two past that id, and so no id left to give.
 *
 * This is a number the store writes by itself — every allocation moves the counter two
 * past the id it handed out — so it is kept as it is wherever the counter is read,
 * rebuilt or written, and never mistaken for damage. Winding it back would hand out an
 * id this install has already used, whose notification, alarm and pending intents may
 * still be live in the OS. An Add against it is refused instead, with
 * [RefusalReason.IdSpaceExhausted].
 */
const val EXHAUSTED_ID_COUNTER = Reminder.MAX_REMINDER_ID + 2

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

    /**
     * Two stored reminders share an id. Each one is a reminder on its own, but the two
     * together are not a store the app can run on: an id is the identity of the
     * reminder, of its notification and of both its pending intents at once, so the
     * second reminder's alarm replaces the first's and one of them never fires.
     */
    DUPLICATE_ID,

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
            // The reminders are gone but the ids they held are not: their notifications,
            // alarms and pending intents are still in the OS, all keyed by those ids. So
            // the store the app runs on until the value is set aside is empty, and its
            // counter carries on where the old one left off.
            return StoreReading(
                stored = StoredReminders(
                    emptyList(),
                    nextId = nextIdAfterQuarantine(counter.value, decoded.raw)
                ),
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
 * Recovering a counter only ever moves it up. Both halves of what the store says are
 * read as evidence of ids already handed out — the counter itself, and the largest id
 * the reminders hold — and the answer is the larger of the two. Nothing is ever
 * recomputed *down* to what the reminders alone suggest: an empty list is not evidence
 * that no id was ever handed out, since every reminder in it may have been deleted, and
 * the next Add would then take id 0. Since the id is the identity of the reminder, of
 * its notification and of both its pending intents, it would replace whatever already
 * holds it.
 *
 * A counter that is not even is off by one from a number this app did write, so it is
 * rounded up and used. A counter with no number in it that can mean anything — of
 * another type, negative, or past [EXHAUSTED_ID_COUNTER], none of which this app ever
 * wrote — contributes nothing, and the reminders answer alone. 0 comes out only when
 * neither half says anything, which is the genuine first run.
 *
 * @param storedNextId null when the store holds no number at all.
 */
fun nextIdToUse(storedNextId: Int?, reminders: List<Reminder>): Int =
    counterPast(storedNextId, reminders.maxOfOrNull { it.id })

/**
 * The id counter to allocate from once an unreadable stored value has been set aside.
 *
 * An id is never reused within an install. The reminders leave the store when their
 * value is set aside, but their notifications are still on screen and their alarms and
 * pending intents are still in `AlarmManager`, all keyed by the ids the counter handed
 * out. Giving one of those ids to a new reminder cross-wires the two: swiping the old
 * notification away sends a mark-done for the new reminder, and the new reminder
 * silently never fires. So the counter is durable on its own and a quarantine leaves it
 * alone.
 *
 * The raw text being set aside is the other record of which ids were handed out, and it
 * is scanned for `"id":<number>` occurrences. That scan is best effort by nature: the
 * text did not parse, which is why it is being set aside. It errs upwards on purpose,
 * since a match that is not really an id only skips an id that was never used, while
 * missing a real one hands it out twice. The counter and the scan are the same two
 * halves [nextIdToUse] weighs, read the same way, and the larger one wins.
 *
 * The one case with no answer at all is a counter with no usable number in it together
 * with a value that could not even be read as text: there the count starts again from 0.
 *
 * @param storedNextId the counter as the store holds it, or null when there is no
 *   number there at all.
 * @param quarantinedRaw the value being set aside, or null when it could not be read as
 *   text.
 */
fun nextIdAfterQuarantine(storedNextId: Int?, quarantinedRaw: String?): Int =
    counterPast(storedNextId, largestStoredIdIn(quarantinedRaw))

/**
 * The counter that is past both records of what was handed out: the stored counter and
 * the largest id anything still says was allocated. Either may say nothing, and 0 —
 * the first run — is what comes out when neither says anything at all.
 *
 * Stored ids are even, so the largest one plus two is even as well, and a counter that
 * is not even is rounded up by [usableCounter]. The answer is therefore always even and
 * never above [EXHAUSTED_ID_COUNTER].
 */
private fun counterPast(storedNextId: Int?, largestAllocatedId: Int?): Int =
    maxOf(usableCounter(storedNextId) ?: 0, largestAllocatedId?.plus(2) ?: 0)

/**
 * The stored counter as a number that can be used, or null when it holds nothing this
 * app could have written.
 *
 * [EXHAUSTED_ID_COUNTER] is the top of the range and is usable: it is what the store
 * holds once the last id has been handed out, and an Add against it is refused rather
 * than the counter lowered. A negative number, or one above that, is no record of an
 * allocation at all, because the app could never have written it.
 */
private fun usableCounter(storedNextId: Int?): Int? = storedNextId
    ?.takeIf { it in 0..EXHAUSTED_ID_COUNTER }
    // Ids are even. Rounding up rather than down is what keeps the answer from landing
    // back on an id the store had already gone past.
    ?.let { if (it % 2 == 0) it else it + 1 }

/**
 * The largest even id in range that an `"id":<number>` occurrence in [raw] names, or
 * null when there is none.
 */
private fun largestStoredIdIn(raw: String?): Int? = raw
    ?.let { STORED_ID.findAll(it) }
    ?.mapNotNull { it.groupValues[1].toIntOrNull() }
    ?.filter { it % 2 == 0 && it in 0..Reminder.MAX_REMINDER_ID }
    ?.maxOrNull()

/**
 * How a reminder's id is written in the stored JSON, allowing for the whitespace a
 * hand-edited file may have. Matching this inside a reminder's text as well is harmless
 * — see [nextIdAfterQuarantine] on why the scan errs upwards.
 */
private val STORED_ID = Regex("\"id\"\\s*:\\s*(\\d+)")

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
        val reminders =
            Json.decodeFromJsonElement(ListSerializer(Reminder.serializer()), element)
        // Uniqueness is a property of the list, so it cannot be checked one reminder at
        // a time the way `Reminder`'s own require is. Two reminders sharing an id share
        // one alarm slot, one notification and one request code, and the last one
        // scheduled silently replaces the first, so the store is set aside whole
        // rather than half-repaired by picking a winner.
        if (reminders.distinctBy { it.id }.size != reminders.size) {
            DecodeResult.Unreadable(UnreadableReason.DUPLICATE_ID, rawJson)
        } else {
            DecodeResult.Readable(reminders)
        }
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
