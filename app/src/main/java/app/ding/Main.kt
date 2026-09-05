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
import androidx.preference.PreferenceManager
import app.ding.ReminderManager.createNotificationChannel
import app.ding.ui.util.UIUtils

class Main : Application() {
    // Note: This is run before any app component starts, i.e., also when starting the app via "Add reminder" or the service.
    override fun onCreate() {
        super.onCreate()

        PreferenceManager.setDefaultValues(this, R.xml.preferences, true)
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