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
import app.ding.state.PersistenceFailed
import app.ding.state.RefusalReason
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
        when (val result = addReminder(this, buildReminderWithTimeTextNagging())) {
            is TransitionOutcome.Updated -> {
                makeToast(result.reminder)
                completeActivity()
                Prefs.setAddReminderDialogUsed(this)
            }
            is TransitionOutcome.Refused -> when (result.reason) {
                // A due time that is not in the future: say so and leave the dialog
                // open so the time can be corrected.
                RefusalReason.PastDue ->
                    Toast.makeText(this, R.string.add_reminder_toast_invalid_date, Toast.LENGTH_LONG).show()
                // No id left to give the reminder, which is not something the user can
                // correct by changing what they typed. Same answer as a store that did
                // not commit: the reminder could not be saved.
                RefusalReason.IdSpaceExhausted -> {
                    Log.e("AddReminder", "No reminder id is left to allocate; no reminder was added")
                    Toast.makeText(this, R.string.error_msg_reminder_not_saved, Toast.LENGTH_LONG).show()
                }
            }
            // The store did not commit, so there is no reminder and no alarm. Say so
            // and leave the dialog open with what was typed, so nothing is lost
            // silently and pressing OK again is a real retry.
            PersistenceFailed -> {
                Log.e("AddReminder", "The store did not commit; no reminder was added")
                Toast.makeText(this, R.string.error_msg_reminder_not_saved, Toast.LENGTH_LONG).show()
            }
            // Add either creates the reminder or refuses it; these cannot happen.
            TransitionOutcome.Removed, TransitionOutcome.Unchanged ->
                Log.e("AddReminder", "The reminder was not stored: $result")
        }
    }
}
