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
 * What a stored switch means when the value under it is not a boolean at all. The
 * reads these stand for are on the delivery path — the notification builder asks
 * for them after the store has already committed NOTIFIED — so the answer has to be
 * a value, never an exception.
 */
class BooleanSettingTest : FunSpec({

    test("a stored true is true and a stored false is false") {
        booleanFromStored { true } shouldBe true
        booleanFromStored { false } shouldBe false
    }

    test("a value of another type cannot be used") {
        // What shared preferences do when the file holds a string, a number or a set
        // under a key the app reads as a boolean: a restored backup or a hand-edited
        // file is enough to get there.
        booleanFromStored { throw ClassCastException("java.lang.String cannot be cast to java.lang.Boolean") } shouldBe null
    }

    test("the read is only asked for once") {
        var reads = 0
        booleanFromStored { reads++; true } shouldBe true
        reads shouldBe 1
    }
})
