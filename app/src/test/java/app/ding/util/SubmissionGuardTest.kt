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
package app.ding.util

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * How many times the reminder dialog submits, decided on a plain JVM.
 *
 * The dialog can be told to submit from the Add/OK button and from the keyboard's
 * action key, and both go through one guard. What the dialog does with the closing
 * check in real life is [android.app.Activity.isFinishing]; here it is a flag a
 * submission can set, which is what closing the dialog looks like from the guard's
 * side.
 */
class SubmissionGuardTest : FunSpec({

    test("a submission runs") {
        var submissions = 0
        val guard = SubmissionGuard { false }

        guard.submit { submissions++ } shouldBe true

        submissions shouldBe 1
    }

    test("a submission that left the dialog open can be run again") {
        // A past-due refusal and a failed write both return with the dialog still
        // open, so correcting the input and pressing the button again is a retry.
        // The one-shot click listener this replaced never ran a second time.
        var submissions = 0
        val guard = SubmissionGuard { false }

        guard.submit { submissions++ } shouldBe true
        guard.submit { submissions++ } shouldBe true

        submissions shouldBe 2
    }

    test("a submission that is already running is not started a second time") {
        var submissions = 0
        var secondRan: Boolean? = null
        val guard = SubmissionGuard { false }

        guard.submit {
            submissions++
            secondRan = guard.submit { submissions++ }
        }

        secondRan shouldBe false
        submissions shouldBe 1
    }

    test("no submission runs once one has closed the dialog") {
        // The dialog stays on screen for a moment after it is told to finish, so a
        // second press can still reach the button. It must not add a second reminder.
        var submissions = 0
        var closing = false
        val guard = SubmissionGuard { closing }

        guard.submit {
            submissions++
            closing = true
        } shouldBe true
        guard.submit { submissions++ } shouldBe false

        submissions shouldBe 1
    }

    test("a submission that throws leaves the guard released") {
        var submissions = 0
        val guard = SubmissionGuard { false }

        shouldThrow<IllegalStateException> {
            guard.submit { throw IllegalStateException("the submission failed") }
        }
        guard.submit { submissions++ } shouldBe true

        submissions shouldBe 1
    }
})
