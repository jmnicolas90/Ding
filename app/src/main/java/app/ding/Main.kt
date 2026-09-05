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
package app.ding

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.preference.PreferenceManager
import app.ding.ReminderManager.createNotificationChannel
import app.ding.ui.util.UIUtils

class Main : Application() {
    // Note: This is run before any app component starts, i.e., also when starting the app via "Add reminder" or the service.
    override fun onCreate() {
        super.onCreate()

        seedSettingsDefaults()
        // The stored format version is not read here. It is read by the decoding, which
        // is the one thing that knows what to do with a version it does not understand
        // or with a value of another type; a typed read here would throw out of
        // onCreate, which is the startup crash loop ticket 13 is about. Nothing writes
        // it on a read either: it is written with the reminders, in the same commit.
        createNotificationChannel(this)

        // Reconcile on app startup: schedule future reminders, deliver past-due ones and re-show
        // the ones already delivered. This ensures that reminders are scheduled and re-shown
        // automatically after reboot (if this is enabled in settings) and when starting the app again after a force-close which cancels
        // AlarmManager alarms and notifications.
        // This might also be called in situations where it is not necessary, for example after the system or user killed the app process
        // without cancelling notifications and alarms. However, there is no handy way of detecting whether this is the case.
        ReminderManager.reconcileAllReminders(this)
    }

    /**
     * Write the defaults the settings XML declares for keys that have no value yet.
     *
     * It reads as well as writes, and the read is the preference framework's own:
     * a key that *is* set is read back with the type its `Preference` expects, so a
     * value of another type — a restored backup, a hand-edited preferences file —
     * throws [ClassCastException] from here. That is out of `Application.onCreate`,
     * before any component of the app has run, so nothing seeds the defaults, the
     * notification channel is not created, the startup sweep never happens and no
     * reminder ever fires again: the same damage as the read this ticket fixed in the
     * notification builder, on a path that costs the user every reminder rather than
     * one.
     *
     * So it is caught, and the app carries on to the two things that matter. Nothing
     * is repaired here: the exception says which types were involved and not which key
     * held them, and guessing wrong would throw away a setting the user chose. The
     * value stays where it is, this runs again at the next start, and the app reads
     * every preference it needs through a read that tolerates it — the settings screen
     * that displays the offending key is the one place still left, which is ticket 15's
     * territory rather than this one's.
     */
    private fun seedSettingsDefaults() {
        try {
            PreferenceManager.setDefaultValues(this, R.xml.preferences, true)
        } catch (e: ClassCastException) {
            Log.e(
                "Settings",
                "A stored setting is of another type than the settings screen declares, " +
                    "so the defaults were not seeded. The app carries on: reminders are " +
                    "what matter here, and every preference they need is read in a way " +
                    "that tolerates this.",
                e
            )
        }
    }

    companion object {
        @JvmStatic
        fun showWelcomeMessage(context: Context) {
            UIUtils.showMessageDialog(R.string.dialog_welcome_title, R.string.welcome_message, context)
        }

        @JvmStatic
        fun showWelcomeMessageUpdate(context: Context) {
            UIUtils.showMessageDialog(R.string.dialog_welcome_title, R.string.welcome_message_update, context)
        }
    }
}