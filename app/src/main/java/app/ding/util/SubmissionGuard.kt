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

/**
 * Lets a dialog run one submission at a time without ever running out of tries.
 *
 * The guard is armed when a submission starts and released again when that submission
 * returns with the dialog still open, so a submission that was refused or that could
 * not be written can be corrected and sent again with the same button. It stays armed
 * once a submission has closed the dialog, because the dialog remains on screen for a
 * moment after it is told to finish and a second press must not add a second reminder.
 *
 * This replaced a one-shot click listener that armed itself and never released, which
 * left the Add and OK buttons dead after the first press even though the dialog was
 * deliberately still open for a retry.
 *
 * It holds no Android type, so it is tested on a plain JVM.
 *
 * @param closing tells whether the dialog is on its way out. The reminder dialog
 * passes [android.app.Activity.isFinishing].
 */
class SubmissionGuard(private val closing: () -> Boolean) {
    private var running = false

    /**
     * Run [submission] unless one is already running or one has already closed the
     * dialog. Returns whether it ran.
     */
    fun submit(submission: () -> Unit): Boolean {
        if (running) {
            return false
        }
        running = true
        try {
            submission()
        } finally {
            // A submission that threw did not close anything, so the guard is released
            // and the dialog can still be used.
            if (!closing()) {
                running = false
            }
        }
        return true
    }
}
