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
@file:JvmName("BooleanSetting")

package app.ding.data

/**
 * What a stored switch means. There is only one way a boolean preference can be
 * unusable — the value under the key is not a boolean at all — so this says less than
 * [naggingRepeatIntervalFromStored] does, and is here for the same reason: the read
 * itself is what fails, it has no Android in it, and it is tested on a plain JVM.
 *
 * The reads it stands for are on the delivery path. The notification builder asks for
 * them after the store has already committed the reminder as delivered, so a read that
 * threw would consume the alarm and show nothing.
 */

/**
 * The stored value, or null when it cannot be used.
 *
 * @param readStored reads the preference. It may throw [ClassCastException] — which is
 *   what shared preferences do when the stored value is of another type, as a restored
 *   backup or a hand-edited preferences file can make it. That is one more way for the
 *   value to be unusable, not an error the caller has to catch.
 */
fun booleanFromStored(readStored: () -> Boolean): Boolean? = try {
    readStored()
} catch (e: ClassCastException) {
    null
}
