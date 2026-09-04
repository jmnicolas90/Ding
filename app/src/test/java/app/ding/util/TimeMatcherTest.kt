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
import app.ding.util.TextBasedTimeInput.TimeMatcher.TimeMatch

class TimeMatcherTest : FunSpec({

    context("Different separators") {
        val timeMatcher = TextBasedTimeInput.TimeMatcher(
            separatorAbsoluteTime = "\\.",
            separatorRelativeTime = ":",
            prefixRelativeTime = "\\+"
        )

        infix fun String.shouldMatchAs(timeMatch: TimeMatch) = timeMatcher.match(this) shouldBe timeMatch
        fun String.shouldNotMatch() = timeMatcher.match(this) shouldBe null

        context("Prefixed relative time hours/minutes") {
            test("1") {
                "+0:00" shouldMatchAs TimeMatch(4, true, 0, 0)
            }

            test("2") {
                "+12:34" shouldMatchAs TimeMatch(5, true, 12, 34)
            }

            test("3") {
                "+1:23" shouldMatchAs TimeMatch(4, true, 1, 23)
            }

            test("4") {
                "+24:00" shouldMatchAs TimeMatch(5, true, 24, 0)
            }

            test("5") {
                "+25:00" shouldMatchAs TimeMatch(5, true, 25, 0)
            }

            test("6") {
                "+100:00" shouldMatchAs TimeMatch(6, true, 100, 0)
            }

            test("7") {
                "+1:89".shouldNotMatch()
            }

            test("8") {
                "+1:2".shouldNotMatch()
            }
        }

        context("Prefixed relative time minutes") {
            test("1") {
                "+0" shouldMatchAs TimeMatch(1, true, 0, 0)
            }

            test("2") {
                "+34" shouldMatchAs TimeMatch(2, true, 0, 34)
            }

            test("3") {
                "+5" shouldMatchAs TimeMatch(1, true, 0, 5)
            }

            test("4") {
                "+89" shouldMatchAs TimeMatch(2, true, 0, 89)
            }

            test("5") {
                "+999" shouldMatchAs TimeMatch(3, true, 0, 999)
            }
        }

        context("Unprefixed relative time hours/minutes") {
            test("1") {
                "12:34" shouldMatchAs TimeMatch(4, true, 12, 34)
            }

            test("2") {
                "1:23" shouldMatchAs TimeMatch(3, true, 1, 23)
            }

            test("3") {
                "25:00" shouldMatchAs TimeMatch(4, true, 25, 0)
            }

            test("4") {
                "1:89".shouldNotMatch()
            }

            test("5") {
                "1:2".shouldNotMatch()
            }
        }

        context("Unprefixed relative time minutes") {
            test("1") {
                ":0" shouldMatchAs TimeMatch(1, true, 0, 0)
            }

            test("2") {
                ":34" shouldMatchAs TimeMatch(2, true, 0, 34)
            }

            test("3") {
                ":5" shouldMatchAs TimeMatch(1, true, 0, 5)
            }

            test("4") {
                ":89" shouldMatchAs TimeMatch(2, true, 0, 89)
            }

            test("5") {
                ":999" shouldMatchAs TimeMatch(3, true, 0, 999)
            }
        }

        context("Absolute time hours/minutes") {
            test("1") {
                "12.34" shouldMatchAs TimeMatch(4, false, 12, 34)
            }

            test("2") {
                "1.23" shouldMatchAs TimeMatch(3, false, 1, 23)
            }

            test("3") {
                "24.00".shouldNotMatch()
            }

            test("4") {
                "25.00".shouldNotMatch()
            }

            test("5") {
                "100.00".shouldNotMatch()
            }
        }

        context("Range end") {
            test("1") {
                "12.34abc".shouldNotMatch()
            }

            test("2") {
                "12.34 abc" shouldMatchAs TimeMatch(4, false, 12, 34)
            }

            test("3") {
                "12.34 " shouldMatchAs TimeMatch(4, false, 12, 34)
            }

            test("4") {
                "2.34  " shouldMatchAs TimeMatch(3, false, 2, 34)
            }
        }
    }

    context("Same separators") {
        val timeMatcher = TextBasedTimeInput.TimeMatcher(
            separatorAbsoluteTime = ":",
            separatorRelativeTime = ":",
            prefixRelativeTime = "\\+"
        )

        infix fun String.shouldMatchAs(timeMatch: TimeMatch) = timeMatcher.match(this) shouldBe timeMatch
        fun String.shouldNotMatch() = timeMatcher.match(this) shouldBe null


        // Same as for different separators
        context("Prefixed relative time hours/minutes") {
            test("1") {
                "+0:00" shouldMatchAs TimeMatch(4, true, 0, 0)
            }

            test("2") {
                "+12:34" shouldMatchAs TimeMatch(5, true, 12, 34)
            }

            test("3") {
                "+1:23" shouldMatchAs TimeMatch(4, true, 1, 23)
            }

            test("4") {
                "+24:00" shouldMatchAs TimeMatch(5, true, 24, 0)
            }

            test("5") {
                "+25:00" shouldMatchAs TimeMatch(5, true, 25, 0)
            }

            test("6") {
                "+100:00" shouldMatchAs TimeMatch(6, true, 100, 0)
            }

            test("7") {
                "+1:89".shouldNotMatch()
            }

            test("8") {
                "+1:2".shouldNotMatch()
            }
        }

        // Same as for different separators
        context("Prefixed relative time minutes") {
            test("1") {
                "+0" shouldMatchAs TimeMatch(1, true, 0, 0)
            }

            test("2") {
                "+34" shouldMatchAs TimeMatch(2, true, 0, 34)
            }

            test("3") {
                "+5" shouldMatchAs TimeMatch(1, true, 0, 5)
            }

            test("4") {
                "+89" shouldMatchAs TimeMatch(2, true, 0, 89)
            }

            test("5") {
                "+999" shouldMatchAs TimeMatch(3, true, 0, 999)
            }
        }

        // As opposed to different separators, the "unprefixed relative time" cases from above are here considered absolute time specifications
        context("Absolute time hours/minutes") {
            test("1") {
                "12:34" shouldMatchAs TimeMatch(4, false, 12, 34)
            }

            test("2") {
                "1:23" shouldMatchAs TimeMatch(3, false, 1, 23)
            }

            test("3") {
                "25:00".shouldNotMatch()
            }

            test("4") {
                "100:00".shouldNotMatch()
            }

            test("5") {
                "1:89".shouldNotMatch()
            }

            test("6") {
                "1:2".shouldNotMatch()
            }
        }

        // (not yet implemented) As opposed to different separators, the "unprefixed relative time" cases from above are here considered absolute time specifications
        /*
        context("Absolute time minutes") {
            test("1") {
                ":0" shouldMatchAs TimeMatch(1, true, 0, 0)
            }

            test("2") {
                ":34" shouldMatchAs TimeMatch(2, true, 0, 34)
            }

            test("3") {
                ":5" shouldMatchAs TimeMatch(1, true, 0, 5)
            }

            test("4") {
                ":89" shouldMatchAs TimeMatch(2, true, 0, 89)
            }

            test("5") {
                ":999" shouldMatchAs TimeMatch(3, true, 0, 999)
            }
        }
         */

    }

})
