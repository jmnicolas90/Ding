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

package app.ding

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Puts every reminder's alarm back after the user has changed whether the app may
 * schedule exact alarms.
 *
 * On Android 12 and 12L the user can revoke `SCHEDULE_EXACT_ALARM` in Settings. Android
 * then stops the process *and deletes the app's exact alarms*, and documents that the
 * app must reschedule its work when it receives this broadcast. Without that, every
 * `SCHEDULED` reminder holds nothing that will ever fire, and the app only finds out at
 * its next process start — which for a reminder app may be days away, since what
 * normally wakes it is the alarms it has just lost. From Android 13 on `USE_EXACT_ALARM`
 * makes the grant permanent and this broadcast is never sent, so this is a 31–32 path,
 * and those are supported versions.
 *
 * Reconcile is the whole answer: it re-sets the alarm of every future reminder, and
 * `AlarmManagerUtil.scheduleExact` picks the exact or the inexact call according to the
 * access the app has at that moment.
 *
 * The broadcast says the state changed, not which way, and this deliberately does not
 * ask. On the grant it puts exact alarms back; on the revocation it replaces the alarms
 * Android has just deleted with the inexact ones the app may still set, and a reminder
 * that may fire late beats one that cannot fire at all. That is the opposite of the
 * notification permission, where a re-show that the denied permission would refuse is
 * worth nothing (`RemindersListActivity.onRequestPermissionsResult`) — here the fallback
 * always succeeds.
 */
class ExactAlarmPermissionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // This receiver is declared for one action, but a receiver never trusts the
        // intent it is handed to be the one it asked for.
        if (intent.action != AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED) {
            Log.w("Scheduling", "Ignoring [${intent.action}] on the exact-alarm permission receiver")
            return
        }
        Log.d("Scheduling", "Exact-alarm access changed; reconciling so every reminder holds an alarm again")
        // The application context: this one is a receiver context that dies with the
        // call, and the runner Reconcile builds outlives it.
        ReminderManager.reconcileAllReminders(context.applicationContext)
    }
}
