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

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.util.Calendar

class DateTimeUtilTest : FunSpec({
    context("Hours Minutes Between") {

        test("Hours Minutes Between 1") {
            testPositiveNegative(0, 0, DateTimeUtil.Duration(0, 0, true))
        }

        test("Hours Minutes Between 2") {
            testPositiveNegative(1234567890, 1234567890, DateTimeUtil.Duration(0, 0, true))
        }

        test("Hours Minutes Between 3") {
            testPositiveNegative(0, 1000, DateTimeUtil.Duration(0, 0, true))
        }

        test("Hours Minutes Between 4") {
            testPositiveNegative(123400000, 123459999, DateTimeUtil.Duration(0, 0, true))
        }

        test("Hours Minutes Between 5") {
            testPositiveNegative(123400000, 123460000, DateTimeUtil.Duration(0, 1, true))
        }

        test("Hours Minutes Between 6") {
            testPositiveNegative(0, 1000 * 60 * 60 - 1, DateTimeUtil.Duration(0, 59, true))
        }

        test("Hours Minutes Between 7") {
            testPositiveNegative(0, 1000 * 60 * 60, DateTimeUtil.Duration(1, 0, true))
        }

        test("Hours Minutes Between 8") {
            testPositiveNegative(0, 1000 * 61 * 60, DateTimeUtil.Duration(1, 1, true))
        }

        test("Hours Minutes Between 9") {
            testPositiveNegative(0, 1000L * 2000 * 60 * 60 + 1000 * 60 * 5, DateTimeUtil.Duration(2000, 5, true))
        }
    }

    context("Hours Between") {
        test("Hours Between 1") {
            val start = Calendar.getInstance()
            val end = Calendar.getInstance()
            end.time = start.time
            end.add(Calendar.MINUTE, 1)
            testWithCalendar(start, end, 0, 1)
        }

        test("Hours Between 2") {
            val start = Calendar.getInstance()
            val end = Calendar.getInstance()
            end.time = start.time
            end.add(Calendar.HOUR_OF_DAY, 1)
            testWithCalendar(start, end, 1, 0)
        }

        test("Hours Between 3") {
            val start = Calendar.getInstance()
            val end = Calendar.getInstance()
            end.time = start.time
            end.add(Calendar.SECOND, 59)
            testWithCalendar(start, end, 0, 0)
        }

        test("Hours Between 4") {
            val start = Calendar.getInstance()
            testWithCalendar(start, start, 0, 0)
        }

        test("Hours Between 5") {
            val start = Calendar.getInstance()
            val end = Calendar.getInstance()
            end.time = start.time
            end.add(Calendar.SECOND, 60 * 70)
            // This should hold even with leap seconds etc.
            testWithCalendar(start, end, 1, 10)
        }
    }
}) {
    companion object {
        private fun testPositiveNegative(start: Long, end: Long, expected: DateTimeUtil.Duration) {
            DateTimeUtil.hoursMinutesBetween(start, end) shouldBe expected
            if (start != end) {
                DateTimeUtil.hoursMinutesBetween(end, start) shouldBe DateTimeUtil.Duration(expected.hours, expected.minutes, false)
            }
        }

        private fun testWithCalendar(start: Calendar, end: Calendar, hours: Long, minutes: Int) {
            testPositiveNegative(start.time.time, end.time.time, DateTimeUtil.Duration(hours, minutes, true))
        }
    }
}
