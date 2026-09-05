/*
 * Copyright (C) 2018-2025 Felix Wiemuth and contributors (see CONTRIBUTORS.md)
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

package app.ding

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import app.ding.data.Reminder
import app.ding.state.ReminderStore
import app.ding.state.StoredReminders
import app.ding.ui.reminderslist.RemindersListFragment

/**
 * Handles the persistent reminder storage: the reminder list and the id counter,
 * both JSON in shared preferences.
 *
 * Reading is public, for the list and the edit dialog. Writing is not: it happens
 * only through [storeIn], which [ReminderManager] hands to the one command runner,
 * so that every change to a reminder passes the transition function first.
 */
object ReminderStorage {
    class ReminderNotFoundException(message: String?) : RuntimeException(message)

    /**
     * The store the app runs on. Its [ReminderStore.write] reports whether the write
     * committed, so that a failed write can stop the runner before it acts.
     */
    internal fun storeIn(context: Context): ReminderStore = SharedPreferencesStore(context)

    private class SharedPreferencesStore(private val context: Context) : ReminderStore {
        override fun read(): StoredReminders {
            val prefs = Prefs.getStatePrefs(context)
            return StoredReminders(
                reminders = getRemindersFromPrefs(prefs),
                nextId = prefs.getInt(Prefs.PREF_STATE_NEXTID, 0)
            )
        }

        /**
         * Writes the reminder list and the id counter in one commit, and returns
         * whether that commit went through.
         *
         * `commit()` rather than `apply()` on purpose, which is what the lint
         * suppression is for: `apply()` writes in the background and returns nothing,
         * so a durable write that fails is invisible. Here the answer is the whole
         * point — a false makes the runner stop before it announces the change or
         * sets an alarm for a reminder that is not stored.
         */
        @SuppressLint("ApplySharedPref")
        override fun write(stored: StoredReminders): Boolean =
            Prefs.getStatePrefs(context).edit()
                .putString(Prefs.PREF_STATE_CURRENT_REMINDERS, Reminder.toJson(stored.reminders))
                .putInt(Prefs.PREF_STATE_NEXTID, stored.nextId) // Reminder IDs may only be even
                .commit()

        override fun announceChange() {
            Prefs.setRemindersUpdated(true, context)
            LocalBroadcastManager.getInstance(context)
                .sendBroadcast(RemindersListFragment.getRemindersUpdatedBroadcastIntent())
        }
    }

    /**
     * Returns an immutable list of the saved reminders.
     *
     * @param prefs
     * @return
     */
    private fun getRemindersFromPrefs(prefs: SharedPreferences): List<Reminder> {
        return Reminder.fromJson(prefs.getString(Prefs.PREF_STATE_CURRENT_REMINDERS, "[]")!!)
    }

    fun getReminders(context: Context): List<Reminder> {
        return getRemindersFromPrefs(Prefs.getStatePrefs(context))
    }

    /**
     * Get the reminder with the specified ID.
     *
     * The edit dialog is the one place that reports a missing reminder to the user;
     * everywhere else a missing reminder is cleanup, decided by the transition
     * function, and never an exception.
     *
     * @param context
     * @param id
     * @return
     * @throws ReminderNotFoundException if no reminder with the given ID exists
     */
    @JvmStatic
    @Throws(ReminderNotFoundException::class)
    fun getReminder(context: Context, id: Int): Reminder =
        getReminders(context).find { r -> r.id == id }
            ?: throw ReminderNotFoundException("Reminder with id $id does not exist.")
}
