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

package app.ding.util

object TextBasedTimeInput {

    /**
     * With the following separators:
     * - absolute time: "."
     * - relative time: ":"
     * - prefix relative time "+"
     *
     * the following formats are possible (and are checked in this order):
     * 1. +12:34 (also +1:23, +24:00, +25:00, +100:00, +0:00, but not +1:89, +1:2)
     * 2. +34 (also +0, +5, +89, +999)
     * 3. 12:34 (also 1:23, 25:00, 0:00, but not 1:89, 1:2) - same meaning as 1.
     * 4. :34 (also :0, :5, :89, :999) - same meaning as 2.
     * 5. 12.34 (also 1.23, but not 1.89, 24.00, 25.00, 100.00)
     * 6. [not yet implemented] .34 (also .5) - next occurrence with next hour
     *
     * If absolute and relative separator are the same, 3. + 4. are not available
     */
    class TimeMatcher(
        /**
         * Separator between hour and minute when specifying an absolute time.
         *
         * NOTE: This is used in a [Regex] and must be escaped accordingly.
         */
        val separatorAbsoluteTime: String,
        /**
         * Separator between hour and minute when specifying an absolute time.
         *
         * NOTE: This is used in a [Regex] and must be escaped accordingly.
         */
        val separatorRelativeTime: String,
        /**
         * Prefix indicating a relative time specification.
         * This is not needed when different separators are used for absolute and relative time.
         *
         * NOTE: This is used in a [Regex] and must be escaped accordingly. E.g. "\\+"
         */
        val prefixRelativeTime: String
    ) {

        data class TimeMatch(
            /**
             * Index of the last character matched.
             */
            val rangeLast: Int,
            val isRelative: Boolean,
            val hour: Int,
            val minute: Int,
        )

        data class OneNumberMatch(val rangeLast: Int, val first: Int)
        data class TwoNumbersMatch(val rangeLast: Int, val first: Int, val second: Int)

        private fun Regex.matchOneNumber(input: CharSequence) =
            this.matchAt(input, 0)?.let {
                OneNumberMatch(rangeLast = it.range.last, first = it.groupValues[1].toInt())
            }

        private fun Regex.matchTwoNumbers(input: CharSequence) =
            this.matchAt(input, 0)?.let {
                TwoNumbersMatch(rangeLast = it.range.last, first = it.groupValues[1].toInt(), second = it.groupValues[2].toInt())
            }

        // Hour part: for absolute, check that in correct range, for relative, all numbers are valid;
        // limiting to three digits because of performance issues with many digits (and unlikely use case).
        // Minute part: always two digits when hours are specified; for absolute, check that in correct range, for relative all numbers valid

        val differentSeparators = separatorRelativeTime != separatorAbsoluteTime

        private val endOfMatch = "(?=\\s|\$)"

        private val prefixedRelTimeHMM = Regex(
            "$prefixRelativeTime(\\d{1,3})$separatorRelativeTime(\\d\\d)$endOfMatch"
        )
        private val prefixedRelTimeM = Regex(
            "$prefixRelativeTime(\\d{1,3})$endOfMatch"
        )
        private val unprefixedReltimeHM =
            if (differentSeparators) Regex(
                "(\\d{1,3})$separatorRelativeTime(\\d\\d)$endOfMatch"
            )
            else null
        private val unprefixedReltimeM =
            if (differentSeparators) Regex(
                "$separatorRelativeTime(\\d{1,3})$endOfMatch"
            )
            else null
        private val absTimeHMM = Regex(
            "(\\d{1,2})$separatorAbsoluteTime(\\d\\d)$endOfMatch"
        )

        fun match(input: CharSequence): TimeMatch? =
            prefixedRelTimeHMM.matchTwoNumbers(input)?.let {
                if ((0..59).contains(it.second))
                    TimeMatch(rangeLast = it.rangeLast, isRelative = true, hour = it.first, minute = it.second)
                else null
            } ?: prefixedRelTimeM.matchOneNumber(input)?.let {
                TimeMatch(rangeLast = it.rangeLast, isRelative = true, hour = 0, minute = it.first)
            } ?: unprefixedReltimeHM?.matchTwoNumbers(input)?.let {
                if ((0..59).contains(it.second))
                    TimeMatch(rangeLast = it.rangeLast, isRelative = true, hour = it.first, minute = it.second)
                else null
            } ?: unprefixedReltimeM?.matchOneNumber(input)?.let {
                TimeMatch(rangeLast = it.rangeLast, isRelative = true, hour = 0, minute = it.first)
            } ?: absTimeHMM.matchTwoNumbers(input)?.let {
                // TODO When implementing 12h mode, this should check range depending on 12/24 hour mode.
                if (((0..23).contains(it.first) && (0..59).contains(it.second)))
                    TimeMatch(rangeLast = it.rangeLast, isRelative = false, hour = it.first, minute = it.second)
                else null
            }
    }
}
