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
package app.ding.data

import app.ding.data.Reminder.Companion.MAX_NAGGING_REPEAT_INTERVAL
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.util.Date

/**
 * The nag interval as the model sees it: what it is worth in milliseconds, and which
 * values a reminder may hold at all.
 */
class ReminderTest : FunSpec({

    test("the interval is turned into milliseconds in Long, so 35,792 minutes does not overflow") {
        // 60,000 times 35,791 is the largest product that still fits in an Int. One
        // minute more used to wrap round to a negative duration, which schedules a nag
        // in the past. Tested on the conversion itself, since the model refuses both
        // of these values.
        Reminder.nagIntervalInMillis(35_791) shouldBe 2_147_460_000L
        Reminder.nagIntervalInMillis(35_792) shouldBe 2_147_520_000L
    }

    test("the interval a reminder may hold is one minute to 24 hours, or zero for no nagging") {
        for (interval in listOf(0, 1, MAX_NAGGING_REPEAT_INTERVAL)) {
            withClue("$interval minutes should be accepted") {
                reminder(interval).naggingRepeatInterval shouldBe interval
            }
        }
        for (interval in listOf(-1, MAX_NAGGING_REPEAT_INTERVAL + 1, Int.MAX_VALUE)) {
            withClue("$interval minutes should be refused") {
                shouldThrow<IllegalArgumentException> { reminder(interval) }
            }
        }
    }

    test("the largest interval a reminder may hold is 24 hours of milliseconds") {
        MAX_NAGGING_REPEAT_INTERVAL shouldBe 1440
        reminder(MAX_NAGGING_REPEAT_INTERVAL).naggingRepeatIntervalInMillis shouldBe 86_400_000L
        reminder(MAX_NAGGING_REPEAT_INTERVAL).isNagging shouldBe true
        reminder(0).isNagging shouldBe false
    }
})

/** A fixed clock: 2026-09-05T12:00:00Z. */
private const val NOW = 1788609600000L

private fun reminder(naggingRepeatInterval: Int) = Reminder(
    id = 2,
    date = Date(NOW),
    naggingRepeatInterval = naggingRepeatInterval,
    text = "Water the plants"
)
