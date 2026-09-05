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
package app.ding.state

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * The rollback the shared-preferences adapter performs, on its own. The commit here
 * behaves the way shared preferences do: it makes the values visible whatever it
 * then reports about the durable write.
 */
class WriteWithRollbackTest : FunSpec({

    test("a commit that succeeds stores the new values and rolls nothing back") {
        val commits = mutableListOf<String>()

        val result = writeWithRollback(previous = "old", next = "new") { values ->
            commits.add(values)
            true
        }

        result shouldBe WriteWithRollbackResult(
            committed = true,
            stored = "new",
            rollbackCommitted = null
        )
        commits shouldBe listOf("new")
    }

    test("a commit that fails puts the previous values back") {
        val commits = mutableListOf<String>()

        val result = writeWithRollback(previous = "old", next = "new") { values ->
            commits.add(values)
            // The rollback of a store that is writable again goes through.
            values == "old"
        }

        result shouldBe WriteWithRollbackResult(
            committed = false,
            stored = "old",
            rollbackCommitted = true
        )
        commits shouldBe listOf("new", "old")
    }

    test("a rollback whose own commit fails still reports the write as failed") {
        var visible = "old"

        val result = writeWithRollback(previous = "old", next = "new") { values ->
            // Visible first, durable second: the durable write is what fails.
            visible = values
            false
        }

        result shouldBe WriteWithRollbackResult(
            committed = false,
            stored = "old",
            rollbackCommitted = false
        )
        // The point of rolling back anyway: later reads see the previous values.
        visible shouldBe "old"
    }
})
