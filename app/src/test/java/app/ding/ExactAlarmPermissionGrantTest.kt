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
 * just lost. Android's own answer is the broadcast this test delivers, and until ticket
 * 30 Ding registered nothing for it.
 *
 * That broadcast is the grant and nothing else — the platform documents that it is not
 * sent when the permission is revoked — so the revocation is modelled here only as the
 * silent starting position, never as something the app is told about (ticket 32).
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
    fun `access taken back again before the broadcast arrives leaves an inexact alarm rather than none`() {
        allowExactAlarms(true)
        val dueTime = now + oneMinute
        val reminder = addReminder(dueTime)

        androidRevokesTheAccessAndDeletesTheAlarms()
        deliverThePermissionStateChange()

        // The race Android names on this broadcast: it is sent on the grant, but the
        // user may have revoked the permission again before it is delivered, so an app
        // must ask `canScheduleExactAlarms` rather than trust the grant it is being told
        // about. The receiver never reads the grant at all — `scheduleExact` asks the
        // platform as it sets each alarm — so this reconciliation leaves an alarm that
        // may fire late rather than none at all or a `SecurityException`.
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
     * The revocation as the platform performs it: the access goes, every alarm the app
     * holds goes with it, and the app is told nothing — it is stopped instead. So this
     * only ever sets a starting position; no broadcast follows from it. Robolectric has
     * no reader for the deletion, so the alarms are drained one by one out of the shadow,
     * which is the same thing seen from the app.
     */
    private fun androidRevokesTheAccessAndDeletesTheAlarms() {
        allowExactAlarms(false)
        while (scheduledAlarms().isNotEmpty()) {
            shadowOf(alarmManager).getNextScheduledAlarm()
        }
    }

    /**
     * Hand the app the broadcast Android sends when the permission is granted, through
     * the receiver the manifest declares for it.
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
            "the manifest declares one receiver for the permission grant, not $declared",
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
