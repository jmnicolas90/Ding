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

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * What the stored default nag interval means, decided on a plain JVM.
 *
 * `Prefs.getNaggingRepeatInterval` runs when the reminder dialog opens and when the
 * settings summary is drawn, so every answer here is a value or a null — never an
 * exception. The read itself is one of the things that can fail, which is why it is
 * passed in as a function: shared preferences throw [ClassCastException] when the
 * stored value is of another type than the read asks for.
 */
class NagIntervalSettingTest : FunSpec({

    test("a stored interval inside the bound is used as it is") {
        naggingRepeatIntervalFromStored("1") shouldBe 1
        naggingRepeatIntervalFromStored("5") shouldBe 5
        naggingRepeatIntervalFromStored("1440") shouldBe Reminder.MAX_NAGGING_REPEAT_INTERVAL
    }

    test("a stored value of another type is unusable, not an exception") {
        // What shared preferences do when the preferences file holds, say, an integer
        // under a key the app reads as a string. This used to throw out of the reminder
        // dialog and out of the settings screen.
        naggingRepeatIntervalFromStored { throw ClassCastException("Integer cannot be cast to String") } shouldBe null
    }

    test("nothing stored is unusable, so the default stands") {
        naggingRepeatIntervalFromStored(null as String?) shouldBe null
        naggingRepeatIntervalFromStored { null } shouldBe null
    }

    test("an empty stored value is unusable") {
        naggingRepeatIntervalFromStored("") shouldBe null
    }

    test("a stored value that is not a whole number is unusable") {
        naggingRepeatIntervalFromStored("soon") shouldBe null
        naggingRepeatIntervalFromStored("2.5") shouldBe null
        naggingRepeatIntervalFromStored("99999999999999") shouldBe null
    }

    test("zero is unusable: nagging is switched on separately, and its interval starts at one minute") {
        // 0 means "no nagging" on a reminder, but this preference is the interval the
        // number picker opens at, and the picker's minimum is one minute. A stored 0
        // would arm the switch with an interval that never nags.
        naggingRepeatIntervalFromStored("0") shouldBe null
    }

    test("a negative stored interval is unusable") {
        naggingRepeatIntervalFromStored("-1") shouldBe null
    }

    test("a stored interval past the bound is unusable") {
        naggingRepeatIntervalFromStored("1441") shouldBe null
        naggingRepeatIntervalFromStored(Int.MAX_VALUE.toString()) shouldBe null
    }

    test("the read is only asked for once") {
        var reads = 0

        naggingRepeatIntervalFromStored {
            reads++
            "7"
        } shouldBe 7
        reads shouldBe 1
    }
})
