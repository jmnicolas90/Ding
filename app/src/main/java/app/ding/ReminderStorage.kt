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
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import app.ding.data.Reminder
import app.ding.state.ReminderStore
import app.ding.state.StoredReminders
import app.ding.state.writeWithRollback
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
     * committed, so that a failed write can stop the runner before it acts, and puts
     * the previous values back when it did not, so a failed write is invisible to
     * every later read as well.
     */
    internal fun storeIn(context: Context): ReminderStore = SharedPreferencesStore(context)

    /** The two preference values one store write puts in place, as they are stored. */
    private data class StatePrefValues(val remindersJson: String, val nextId: Int)

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
         *
         * A false from `commit()` is not the end of the work, though, because
         * shared preferences do not undo anything on it. `commit()` puts the new
         * values in the in-memory map first and only then attempts the durable
         * write, and a durable write that fails leaves that map alone. Without the
         * rollback below, a failed write would still have handed the new reminder
         * list and the new id counter to every later read in this process: a failed
         * Add would keep its phantom reminder and its advanced id, and a failed
         * Deliver would leave the reminder looking `NOTIFIED` so the alarm that has
         * already been consumed and an in-process Reconcile would both skip it.
         * That is exactly the state the runner's callers are told never happened.
         *
         * So on failure the previous values go back through a second `commit()`.
         * It restores the in-memory map whatever its own durable write does, for
         * the same reason the first one polluted it. On disk there is nothing to
         * repair beyond that: a shared-preferences commit is atomic — the file is
         * swapped with its backup rather than patched — so the file holds either
         * the old content or the new one in full, and the rollback brings both
         * halves back in line with the old one. If the rollback's own commit fails
         * the write is still reported as failed; both results are logged, because a
         * store that cannot even write back what it already held is worth seeing.
         */
        @SuppressLint("ApplySharedPref")
        override fun write(stored: StoredReminders): Boolean {
            val result = writeWithRollback(
                previous = readValues(),
                next = StatePrefValues(
                    remindersJson = Reminder.toJson(stored.reminders),
                    nextId = stored.nextId // Reminder IDs may only be even
                ),
                commit = ::commitValues
            )
            if (!result.committed) {
                Log.e(
                    "ReminderStorage",
                    "The store did not commit; put the previous reminders back: " +
                        "rollback committed=${result.rollbackCommitted}"
                )
            }
            return result.committed
        }

        /**
         * The values [commitValues] writes, read straight out of the preferences so
         * that a rollback restores them exactly rather than a re-serialisation of
         * them. These two keys are the whole of what that editor touches.
         */
        private fun readValues(): StatePrefValues {
            val prefs = Prefs.getStatePrefs(context)
            return StatePrefValues(
                remindersJson = prefs.getString(Prefs.PREF_STATE_CURRENT_REMINDERS, "[]")!!,
                nextId = prefs.getInt(Prefs.PREF_STATE_NEXTID, 0)
            )
        }

        @SuppressLint("ApplySharedPref")
        private fun commitValues(values: StatePrefValues): Boolean =
            Prefs.getStatePrefs(context).edit()
                .putString(Prefs.PREF_STATE_CURRENT_REMINDERS, values.remindersJson)
                .putInt(Prefs.PREF_STATE_NEXTID, values.nextId)
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
