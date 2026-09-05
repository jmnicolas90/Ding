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
@file:JvmName("NagIntervalSetting")

package app.ding.data

/**
 * What the stored default nag interval means: the one place that decides whether the
 * text in the settings preference can be used, so that `Prefs.getNaggingRepeatInterval`
 * only has to say what to do when it cannot.
 *
 * It is here rather than in `Prefs` because it has no Android in it, and because this
 * value is read on two paths a user notices immediately — the reminder dialog opening
 * and the settings summary being drawn. Neither may throw. The read itself is one of
 * the things that can fail, so it is passed in as a function: shared preferences throw
 * [ClassCastException] when the stored value is of another type than the read asks for,
 * which a hand-edited preferences file can be.
 */

/**
 * The stored interval in minutes, or null when the stored value cannot be used —
 * of another type, absent, empty, not a whole number, or outside the bound
 * [Reminder.MIN_NAGGING_REPEAT_INTERVAL]..[Reminder.MAX_NAGGING_REPEAT_INTERVAL].
 *
 * @param readStored reads the preference. It may throw [ClassCastException]; that is
 *   one more way for the value to be unusable, not an error the caller has to catch.
 */
fun naggingRepeatIntervalFromStored(readStored: () -> String?): Int? = try {
    naggingRepeatIntervalFromStored(readStored())
} catch (e: ClassCastException) {
    null
}

/**
 * The interval in [stored] as it was read, or null when it cannot be used.
 *
 * 0 is unusable here, although a reminder holding 0 simply means nagging is off. This
 * preference is not that switch: it is the interval the number picker opens at and the
 * one a newly nagging reminder takes, and the picker's minimum is one minute. A stored
 * 0 would switch nagging on with an interval that never nags — the quiet kind of
 * failure this app cannot afford.
 *
 * The text is read exactly as the settings editor writes it, with no trimming, so that
 * a value this accepts and a value the editor accepts are the same set.
 */
fun naggingRepeatIntervalFromStored(stored: String?): Int? {
    val minutes = stored?.toIntOrNull() ?: return null
    val bound = Reminder.MIN_NAGGING_REPEAT_INTERVAL..Reminder.MAX_NAGGING_REPEAT_INTERVAL
    return if (minutes in bound) minutes else null
}
