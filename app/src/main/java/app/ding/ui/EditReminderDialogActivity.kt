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

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import app.ding.R
import app.ding.ReminderManager
import app.ding.ReminderStorage.ReminderNotFoundException
import app.ding.ReminderStorage.getReminder
import app.ding.state.PersistenceFailed
import app.ding.state.TransitionOutcome
import app.ding.state.editOrReschedule
import app.ding.state.initialDueTimeForEdit
import java.util.Calendar

/**
 * Shows a dialog allowing to edit a reminder. Finishes with `RESULT_OK` when the reminder
 * was changed, and with `RESULT_CANCELED` when it was not — a reminder that is no longer
 * in the store, or backing out. A refused due time and a write that did not commit leave
 * the dialog open so the input can be corrected and sent again.
 *
 *
 * Has to be started with the intent provided by [getIntentEditReminder].
 */
class EditReminderDialogActivity : ReminderDialogActivity() {
    /**
     * The ID of the reminder to be updated.
     */
    private var reminderToUpdate = -1

    /**
     * The due time the pickers were restored to when the dialog opened, which is what
     * tells an edit (same due time) from a reschedule (a different one).
     */
    private var dueTimeWhenOpened = 0L
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTitle(R.string.edit_reminder_title)
        setAddButtonText(R.string.edit_reminder_add_button)
        setupActivityWithReminder(intent)
    }

    /**
     * Process a new intent to this activity, replacing the current content with that of the reminder referenced by the new intent.
     *
     * @param intent
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setupActivityWithReminder(intent)
    }

    /**
     * Setup the state and UI of the activity based on the reminder referenced by the given intent.
     *
     * @param intent
     * @throws IllegalArgumentException if the intent has not the required extra
     */
    private fun setupActivityWithReminder(intent: Intent) {
        require(intent.hasExtra(EXTRA_REMINDER_ID)) { "EditReminderDialogActivity received intent without reminder ID extra." }
        val reminderId = intent.getIntExtra(EXTRA_REMINDER_ID, -1)
        try {
            val reminder = getReminder(this, reminderId)
            setTextMovingCursorToEnd(reminder.text)

            // Restore the due time the dialog opens on. Which one that is, is decided
            // by initialDueTimeForEdit: the stored one whatever the reminder's state.
            // Only a scheduled reminder used to get it back; a notified or done one kept
            // the minute the dialog happened to open on, so pressing OK without touching
            // the time read as a reschedule to a minute that had normally already
            // passed. The restored value is what "untouched" is compared against below,
            // read back from the pickers so that removing this restore cannot pass
            // unnoticed.
            val initialDueTime = initialDueTimeForEdit(reminder, System.currentTimeMillis())
            setSelectedDateTimeAndSelectionMode(
                Calendar.getInstance().apply { timeInMillis = initialDueTime }
            )
            naggingSwitch.isChecked = reminder.isNagging
            if (reminder.isNagging) {
                naggingRepeatInterval = reminder.naggingRepeatInterval
            }
            reminderToUpdate = reminderId
            dueTimeWhenOpened = selectedDueTime
        } catch (e: ReminderNotFoundException) {
            Log.w("AddReminder", "Intent contains invalid reminder ID.")
            Toast.makeText(this, R.string.error_msg_reminder_not_found, Toast.LENGTH_LONG).show()
            // Nothing to edit and nothing was changed.
            finishWithoutChange()
        }
    }

    override fun onDone() {
        val reminderBuilder = buildReminderWithTimeTextNagging()
        // The due time decides the command: left alone it is an edit, which changes only
        // text and nag settings and never the state; moved it is a reschedule, which
        // re-arms the reminder from any state and needs a time in the future.
        val command = editOrReschedule(
            reminderId = reminderToUpdate,
            dueTimeWhenOpened = dueTimeWhenOpened,
            chosenDueTime = reminderBuilder.date.time,
            text = reminderBuilder.text,
            naggingRepeatInterval = reminderBuilder.naggingRepeatInterval
        )
        when (val result = ReminderManager.run(this, command)) {
            is TransitionOutcome.Updated -> {
                makeToast(result.reminder)
                finishAfterChange()
            }
            // A due time that is not in the future is refused: say so and leave the
            // dialog open so the time can be corrected.
            is TransitionOutcome.Refused ->
                Toast.makeText(this, R.string.add_reminder_toast_invalid_date, Toast.LENGTH_LONG).show()
            // The store did not commit, so the reminder still holds what it held
            // before. Say so and leave the dialog open with the changes in it, so the
            // edit is not lost silently and pressing OK again is a real retry.
            PersistenceFailed -> {
                Log.e("EditReminder", "The store did not commit; the reminder is unchanged")
                Toast.makeText(this, R.string.error_msg_reminder_not_saved, Toast.LENGTH_LONG).show()
            }
            // Nothing was written and nothing failed, which means the reminder was
            // removed while this dialog was open.
            TransitionOutcome.Removed, TransitionOutcome.Unchanged -> {
                Log.w("EditReminder", "The reminder was not updated: $result")
                Toast.makeText(this, R.string.error_msg_reminder_not_found, Toast.LENGTH_LONG).show()
                finishWithoutChange()
            }
        }
    }

    companion object {
        private const val EXTRA_REMINDER_ID = "app.ding.ui.AddReminderDialogActivity.extra.ID"
        fun getIntentEditReminder(context: Context?, reminderId: Int): Intent {
            return Intent(context, EditReminderDialogActivity::class.java)
                .putExtra(EXTRA_REMINDER_ID, reminderId)
        }
    }
}
