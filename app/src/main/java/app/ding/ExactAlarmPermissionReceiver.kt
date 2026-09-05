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
 * Puts every reminder's alarm back after Android has given the app the right to schedule
 * exact alarms again.
 *
 * On Android 12 and 12L the user can revoke `SCHEDULE_EXACT_ALARM` in Settings. Android
 * then stops the process *and deletes the app's exact alarms*, and says nothing: the
 * platform's documentation of the action below is explicit that "this broadcast will not
 * be sent when the user revokes the permission". What arrives here is the grant, and only
 * the grant. Without it, every `SCHEDULED` reminder holds nothing that will ever fire and
 * the app finds out at its next process start — which for a reminder app may be days
 * away, since what normally wakes it is the alarms it has just lost. From Android 13 on
 * `USE_EXACT_ALARM` makes the grant permanent and this broadcast is never sent, so this is
 * a 31–32 path, and those are supported versions.
 *
 * Reconcile is the whole answer: it re-sets the alarm of every future reminder, and
 * `AlarmManagerUtil.scheduleExact` picks the exact or the inexact call according to the
 * access the app has at that moment.
 *
 * This deliberately does not read the grant out of the broadcast, and that is what the
 * platform asks for rather than a shortcut around it: the permission may have been taken
 * back again by the time the broadcast is delivered, so an app must check
 * `canScheduleExactAlarms` instead of trusting the grant it is being told about. Checking
 * it as each alarm is set, which is what `scheduleExact` does, is the strongest form of
 * that check, and it leaves an inexact alarm rather than none in the race the platform
 * warns about.
 *
 * The revocation itself is left to the next process start's Reconcile. It is not
 * neglected, it is unhearable: the alarms are gone before the app is told anything, and
 * it is not told.
 */
class ExactAlarmPermissionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // This receiver is declared for one action, but a receiver never trusts the
        // intent it is handed to be the one it asked for.
        if (intent.action != AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED) {
            Log.w("Scheduling", "Ignoring [${intent.action}] on the exact-alarm permission receiver")
            return
        }
        Log.d("Scheduling", "Exact-alarm access granted; reconciling so every reminder holds an alarm again")
        // The application context: this one is a receiver context that dies with the
        // call, and the runner Reconcile builds outlives it.
        ReminderManager.reconcileAllReminders(context.applicationContext)
    }
}
