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
@file:JvmName("TimePickerTextSizeSetting")

package app.ding.data

/**
 * What the stored size of the reminder dialog's time display means.
 *
 * **The unit is sp**, so the time display scales with the user's system font size, as
 * text should. It used to be ambiguous: the stored number was converted from dp to
 * pixels and then handed to `TextView.textSize`, whose setter reads it as sp and scales
 * it a second time, so the header came out density-squared too large — about three
 * times the asked-for size on a 420 dpi screen.
 *
 * Like [naggingRepeatIntervalFromStored], this is here rather than in `Prefs` because it
 * has no Android in it, and because it is read while the reminder dialog is opening and
 * while the settings summary is drawn — neither may throw. The read itself is one of the
 * things that can fail, so it is passed in as a function: shared preferences throw
 * [ClassCastException] when the stored value is of another type than the read asks for,
 * which a hand-edited preferences file can be.
 */

/**
 * The smallest size the time display may be set to, in sp. Below this the hour and
 * minute stop being readable, and they are also the buttons that switch between hour and
 * minute selection, so a too-small header is a too-small touch target as well.
 */
const val MIN_TIME_PICKER_TEXT_SIZE_SP: Int = 8

/**
 * The largest size the time display may be set to, in sp. The platform's own time header
 * is 60sp; 96 leaves room for a deliberately large one and still fits the dialog on a
 * small screen at the largest system font size.
 */
const val MAX_TIME_PICKER_TEXT_SIZE_SP: Int = 96

/**
 * The stored size in sp, or null when the stored value cannot be used — of another type,
 * absent, empty, not a whole number, or outside
 * [MIN_TIME_PICKER_TEXT_SIZE_SP]..[MAX_TIME_PICKER_TEXT_SIZE_SP].
 *
 * @param readStored reads the preference. It may throw [ClassCastException]; that is one
 *   more way for the value to be unusable, not an error the caller has to catch.
 */
fun timePickerTextSizeFromStored(readStored: () -> String?): Int? = try {
    timePickerTextSizeFromStored(readStored())
} catch (e: ClassCastException) {
    null
}

/**
 * The size in [stored] as it was read, or null when it cannot be used.
 *
 * The text is read exactly as the settings editor writes it, with no trimming, so that a
 * value this accepts and a value the editor accepts are the same set.
 */
fun timePickerTextSizeFromStored(stored: String?): Int? {
    val sizeSp = stored?.toIntOrNull() ?: return null
    val bound = MIN_TIME_PICKER_TEXT_SIZE_SP..MAX_TIME_PICKER_TEXT_SIZE_SP
    return if (sizeSp in bound) sizeSp else null
}
