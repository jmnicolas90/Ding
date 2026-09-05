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
import android.content.Intent
import android.os.Looper
import app.ding.data.Reminder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager

/**
 * What happens to a `SCHEDULED` reminder when the user takes `SCHEDULE_EXACT_ALARM`
 * away and gives it back.
 *
 * The hole this closes: on Android 12 and 12L revoking that permission stops the
 * process *and deletes the app's exact alarms*. Nothing is left to fire, and the app
 * only finds out at its next process start, which may be days away — or never, since a
 * reminder app that is never opened is one that is only ever woken by the alarms it has
 * just lost. Android's own answer is the broadcast this test delivers, and until this
 * ticket Ding registered nothing for it.
 *
 * Pinned to 31 and 32 because those are the only versions where the path exists: from
 * 33 on `USE_EXACT_ALARM` makes the grant permanent and the broadcast is never sent.
 * Robolectric rather than a device because no API 31 or 32 emulator image is installed
 * here and the map rules one out of scope (ticket 18).
 *
 * The receiver is reached through the manifest rather than by name — the manifest entry
 * is half of the fix, and a test that constructed the class directly would stay green
 * with the app registered for nothing.
 */
@Config(sdk = [31, 32])
class ExactAlarmPermissionGrantTest : RobolectricReminderHarness() {

    @Test
    fun `an alarm the revocation deleted is back in its slot when access is granted again`() {
        allowExactAlarms(true)
        val dueTime = now + oneMinute
        val reminder = addReminder(dueTime)
        assertTrue(
            "the alarm went in exact, so the revocation is what takes it away",
            scheduledAlarms().single().isAllowWhileIdle
        )

        androidRevokesTheAccessAndDeletesTheAlarms()
        assertEquals("nothing is left to fire", 0, scheduledAlarms().size)
        assertEquals(
            "while the reminder still expects to be delivered",
            Reminder.Status.SCHEDULED,
            storedReminder(reminder.id).status
        )

        allowExactAlarms(true)
        deliverThePermissionStateChange()

        val alarm = scheduledAlarms().single()
        assertEquals("the alarm is back, for the due time it was set for", dueTime, alarm.triggerAtMs)
        assertEquals(
            "in the reminder's own slot",
            reminder.id,
            shadowOf(alarm.pendingIntent).requestCode
        )
        assertTrue("and exact again, since the access is back", alarm.isAllowWhileIdle)
        assertEquals(
            "the reminder itself is untouched: it was never delivered",
            Reminder.Status.SCHEDULED,
            storedReminder(reminder.id).status
        )
    }

    @Test
    fun `the revocation itself leaves an inexact alarm rather than none`() {
        allowExactAlarms(true)
        val dueTime = now + oneMinute
        val reminder = addReminder(dueTime)

        androidRevokesTheAccessAndDeletesTheAlarms()
        deliverThePermissionStateChange()

        // The broadcast says the state changed, not which way it changed, and the
        // receiver deliberately does not ask. Reconciling on the revocation too is what
        // turns "no alarm at all" into "an alarm that may fire late": the runner's
        // scheduleExact falls back to an inexact alarm when the access is gone, and a
        // late reminder beats a lost one.
        val alarm = scheduledAlarms().single()
        assertEquals("something is set for the due time again", dueTime, alarm.triggerAtMs)
        assertEquals(reminder.id, shadowOf(alarm.pendingIntent).requestCode)
        assertFalse("inexact, because that is all the app may ask for now", alarm.isAllowWhileIdle)
    }

    /** Leave behind what the user's answer in Settings leaves for `canScheduleExactAlarms`. */
    private fun allowExactAlarms(allowed: Boolean) {
        ShadowAlarmManager.setCanScheduleExactAlarms(allowed)
    }

    /**
     * The revocation as the platform performs it: the access goes, and every alarm the
     * app holds goes with it. Robolectric has no reader for that, so the alarms are
     * drained one by one out of the shadow, which is the same thing seen from the app.
     */
    private fun androidRevokesTheAccessAndDeletesTheAlarms() {
        allowExactAlarms(false)
        while (scheduledAlarms().isNotEmpty()) {
            shadowOf(alarmManager).getNextScheduledAlarm()
        }
    }

    /**
     * Hand the app the broadcast Android sends when the permission's state changes,
     * through the receiver the manifest declares for it.
     *
     * Robolectric delivers a broadcast only to a receiver a test registered, never to
     * one the manifest declares — the same limit `send` works around in
     * [RobolectricReminderHarness]. So the manifest is asked which receiver would get
     * this, that answer is what gets instantiated, and removing the entry fails the test
     * here rather than leaving it quietly green.
     */
    @Suppress("DEPRECATION") // The ResolveInfoFlags overload that replaces it is API 33.
    private fun deliverThePermissionStateChange() {
        val broadcast = Intent(AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED)
            .setPackage(context.packageName)
        val declared = context.packageManager.queryBroadcastReceivers(broadcast, 0)
        assertEquals(
            "the manifest declares one receiver for the permission change, not $declared",
            1,
            declared.size
        )
        val receiver = Class.forName(declared.single().activityInfo.name)
            .getDeclaredConstructor()
            .newInstance() as BroadcastReceiver
        receiver.onReceive(context, broadcast)
        shadowOf(Looper.getMainLooper()).idle()
    }
}
