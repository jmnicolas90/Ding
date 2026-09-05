/*
 * Copyright (C) 2018-2025 Felix Wiemuth and contributors (see CONTRIBUTORS.md)
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
package app.ding.data

import app.ding.data.Reminder.Companion.MAX_REMINDER_ID
import app.ding.util.DateSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.*

@Serializable
data class Reminder
constructor(
    /**
     * ID of the reminder, also used for notifications. Must be in the range 0..[MAX_REMINDER_ID] and even.
     */
    val id: Int,

    /**
     * Reminder's due date.
     */
    @Serializable(with = DateSerializer::class)
    val date: Date,

    /**
     * The interval in minutes with which this reminder should be repeated until dismissed.
     * This field is optional. 0 (or omitting it in JSON) means that nagging is disabled,
     * which is the default; any other value must be in 1..[MAX_NAGGING_REPEAT_INTERVAL].
     * @since 0.9.9
     */
    val naggingRepeatInterval: Int = 0,

    val text: String = "",

    /**
     * The reminder's state. Only the transition function in `app.ding.state` decides
     * this; it is a `val` so that no caller can set it on the side.
     */
    val status: Status = Status.SCHEDULED
) : Comparable<Reminder> {
    /**
     * Status of saved reminders.
     */
    enum class Status {
        /**
         * The reminder has been scheduled but is not due yet.
         */
        SCHEDULED,

        /**
         * The reminder is due and the notification has been sent.
         */
        NOTIFIED,

        /**
         * The reminder has been marked as "done" by the user.
         */
        DONE
    }

    init {
        require(id in 0..MAX_REMINDER_ID && id % 2 == 0) { "Id must be even, >= 0 and <= $MAX_REMINDER_ID." }
        require(naggingRepeatInterval in 0..MAX_NAGGING_REPEAT_INTERVAL) {
            "Nag interval must be 0 (no nagging) or 1..$MAX_NAGGING_REPEAT_INTERVAL minutes."
        }
    }

    /**
     * Get a new [Calendar] instance set to the reminder's date.
     */
    val calendar: Calendar
        get() {
            val c = Calendar.getInstance()
            c.time = date
            return c
        }

    override fun compareTo(other: Reminder): Int {
        return date.compareTo(other.date)
    }

    val isNagging: Boolean
        get() = naggingRepeatInterval > 0
    val naggingRepeatIntervalInMillis: Long
        get() = nagIntervalInMillis(naggingRepeatInterval)

    companion object {
        const val MAX_REMINDER_ID = 1000000

        /**
         * The largest nag interval a reminder may have, in minutes: 24 hours. A nag
         * that repeats less often than once a day is not a nag, it is a second
         * reminder. The bound is enforced here, in the settings preference for the
         * default interval, and in the number picker of the reminder dialog.
         */
        const val MAX_NAGGING_REPEAT_INTERVAL = 1440

        /** The smallest nag interval a reminder may have, in minutes. */
        const val MIN_NAGGING_REPEAT_INTERVAL = 1

        /**
         * [minutes] as milliseconds. The multiplication is in [Long] because
         * `60 * 1000 * minutes` in [Int] overflows from 35,792 minutes upward, and a
         * negative or zero interval schedules a nag in the past or divides by zero.
         * The bound above keeps a reminder well inside that, but the conversion does
         * not depend on it.
         */
        @JvmStatic
        fun nagIntervalInMillis(minutes: Int): Long = 60_000L * minutes

        @JvmStatic
        fun builder(date: Date, text: String): Builder = Builder(date = date, text = text)

        @JvmStatic
        fun toJson(reminders: List<Reminder?>?): String =
            Json.encodeToString(reminders)

        // There is deliberately no fromJson here. Decoding stored reminders is
        // `decodeStoredReminders` in `app.ding.state`, which reads the format version
        // and answers with a result instead of throwing; a second way in would be a way
        // of bypassing that, and the store is read on every process start.
    }

    /**
     * Used to construct a [Reminder] step by step. This allows the ID for a new reminder to be first created when adding the reminder to the storage.
     */
    data class Builder
    @JvmOverloads
    constructor(
        @JvmField
        var id: Int? = null,
        @JvmField
        val date: Date,
        @JvmField
        var naggingRepeatInterval: Int = 0,
        @JvmField
        val text: String = "",
        @JvmField
        var status: Status = Status.SCHEDULED
    ) {
        fun build() = Reminder(requireNotNull(id), date, naggingRepeatInterval, text, status)
    }
}