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
 * What the stored size of the time display means, decided on a plain JVM.
 *
 * The number is a size in sp. `Prefs.getReminderDialogTimePickerTextSize` runs while the
 * reminder dialog is opening and while the settings summary is drawn, so every answer
 * here is a value or a null — never an exception. The read itself is one of the things
 * that can fail, which is why it is passed in as a function: shared preferences throw
 * [ClassCastException] when the stored value is of another type than the read asks for.
 */
class TimePickerTextSizeSettingTest : FunSpec({

    test("a stored size inside the bound is used as it is") {
        timePickerTextSizeFromStored("8") shouldBe MIN_TIME_PICKER_TEXT_SIZE_SP
        timePickerTextSizeFromStored("30") shouldBe 30
        timePickerTextSizeFromStored("96") shouldBe MAX_TIME_PICKER_TEXT_SIZE_SP
    }

    test("a stored value of another type is unusable, not an exception") {
        // What shared preferences do when the preferences file holds, say, an integer
        // under a key the app reads as a string.
        timePickerTextSizeFromStored { throw ClassCastException("Integer cannot be cast to String") } shouldBe null
    }

    test("nothing stored is unusable, so the default stands") {
        timePickerTextSizeFromStored(null as String?) shouldBe null
        timePickerTextSizeFromStored { null } shouldBe null
    }

    test("an empty stored value is unusable") {
        timePickerTextSizeFromStored("") shouldBe null
    }

    test("a stored value that is not a whole number is unusable") {
        // Integer.parseInt of these used to throw out of the opening reminder dialog.
        timePickerTextSizeFromStored("big") shouldBe null
        timePickerTextSizeFromStored("24.5") shouldBe null
        timePickerTextSizeFromStored("99999999999999") shouldBe null
    }

    test("a size below the bound is unusable: the digits are also the hour and minute buttons") {
        timePickerTextSizeFromStored("7") shouldBe null
        timePickerTextSizeFromStored("0") shouldBe null
        timePickerTextSizeFromStored("-30") shouldBe null
    }

    test("a size past the bound is unusable") {
        timePickerTextSizeFromStored("97") shouldBe null
        timePickerTextSizeFromStored(Int.MAX_VALUE.toString()) shouldBe null
    }

    test("the default is inside the bound the reading enforces") {
        // Prefs.Defaults.REMINDER_DIALOG_TIMEPICKER_TEXTSIZE, which is Android-side. A
        // default outside the bound would be stored as the repair for itself, for ever.
        timePickerTextSizeFromStored("30") shouldBe 30
    }

    test("the read is only asked for once") {
        var reads = 0

        timePickerTextSizeFromStored {
            reads++
            "42"
        } shouldBe 42
        reads shouldBe 1
    }
})
