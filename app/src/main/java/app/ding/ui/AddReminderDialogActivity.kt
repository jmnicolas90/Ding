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
package app.ding.ui

import android.os.Bundle
import android.util.Log
import android.widget.Toast
import app.ding.Prefs
import app.ding.R
import app.ding.ReminderManager.addReminder
import app.ding.state.TransitionOutcome

/**
 * Shows a dialog allowing to add a reminder. Finishes with [.RESULT_OK] if the reminder has been added.
 */
class AddReminderDialogActivity : ReminderDialogActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTitle(R.string.add_reminder_title)
    }

    override fun onDone() {
        when (val outcome = addReminder(this, buildReminderWithTimeTextNagging())) {
            is TransitionOutcome.Updated -> {
                makeToast(outcome.reminder)
                completeActivity()
                Prefs.setAddReminderDialogUsed(this)
            }
            // A due time that is not in the future is refused: say so and leave the
            // dialog open so the time can be corrected.
            is TransitionOutcome.Refused ->
                Toast.makeText(this, R.string.add_reminder_toast_invalid_date, Toast.LENGTH_LONG).show()
            // Nothing was stored. Telling the user that is ticket 12's typed failure.
            else -> Log.e("AddReminder", "The reminder was not stored: $outcome")
        }
    }
}
