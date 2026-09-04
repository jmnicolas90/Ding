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

import app.ding.data.Reminder
import java.util.Calendar

/**
 * What a reminder dialog decides about a due time, as plain JVM functions.
 *
 * Like the transition function, this file must never import anything from
 * `android.*` or `androidx.*`, so that these decisions are tested with a fixed clock
 * and no device. `java.util.Calendar` is plain JVM and is fine here. The module has
 * no Android test harness at all, so a decision left inside an activity is a decision
 * nothing checks.
 */

/** The same instant with its seconds and milliseconds cut off. */
fun toMinutePrecision(epochMillis: Long): Long {
    val calendar = Calendar.getInstance()
    calendar.timeInMillis = epochMillis
    calendar[Calendar.SECOND] = 0
    calendar[Calendar.MILLISECOND] = 0
    return calendar.timeInMillis
}

/**
 * The due time the edit dialog opens on: the reminder's stored one, at minute
 * precision, whatever its state.
 *
 * [now] is deliberately unused. It is a parameter because the old dialog did consult
 * the clock — a notified or done reminder kept the minute the dialog happened to open
 * on instead of its own due time, so pressing OK asked for a reschedule to a minute
 * that had normally already passed. Naming the clock here and then not reading it is
 * what the tests pin down.
 */
fun initialDueTimeForEdit(stored: Reminder, now: Long): Long =
    toMinutePrecision(stored.date.time)
