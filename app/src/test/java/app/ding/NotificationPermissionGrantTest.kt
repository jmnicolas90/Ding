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

import android.Manifest
import android.content.pm.PackageManager
import android.os.Looper
import app.ding.data.Reminder
import app.ding.ui.reminderslist.RemindersListActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * A delivery that happened while `POST_NOTIFICATIONS` was denied, and what the app does
 * about it once the user grants the permission.
 *
 * The hole this closes: a denied permission does not stop a delivery, it only stops the
 * notification. The reminder is written as `NOTIFIED` all the same, and a reminder that
 * does not nag has no alarm left afterwards — so without something to put the
 * notification back, the reminder is invisible until the next process start, which may
 * be days away. The reminders list asks for the permission on start, but the startup
 * sweep in `Main.onCreate` has already run by then. The grant is therefore one of the
 * things that reconciles, and this is the test of it.
 *
 * Robolectric rather than a device for the reason `AlarmToNotificationRoundTripTest`
 * gives: it runs in the gate on every commit. That test is where the wiring from an
 * alarm to a notification is checked in full; the delivery here is driven the same way
 * but only asserted on where the permission is what decides the outcome.
 *
 * No permission is granted in a set-up here: the denied case is the starting position
 * [RobolectricReminderHarness] leaves behind, and it is the one every test in this
 * class starts from.
 */
@Config(sdk = [36])
class NotificationPermissionGrantTest : RobolectricReminderHarness() {

    /**
     * The code the list activity passes to `requestPermissions`. Nothing in the app
     * looks at it — the permission is matched by name, because the boot permission
     * arrives at the same callback — so the number matters to nobody; it is the real
     * one only so that this reads like the answer Android would deliver.
     */
    private val requestCodeTheListAskedWith = 0

    @Test
    fun `a delivery suppressed by the denied permission is shown once it is granted`() {
        val reminder = deliverWhilePermissionIsDenied()

        val list = openRemindersList()
        assertEquals("opening the list on its own shows nothing", 0, notificationsOnScreen())

        grantNotificationPermission()
        answerThePermissionRequest(list, PackageManager.PERMISSION_GRANTED)

        assertNotNull(
            "the reminder the user never saw is on screen, under its own id",
            shadowOf(notificationManager).getNotification(reminder.id)
        )
        assertEquals("only that one", 1, notificationsOnScreen())
        assertEquals(
            "and it is still delivered, not re-delivered",
            Reminder.Status.NOTIFIED,
            storedReminder(reminder.id).status
        )
    }

    @Test
    fun `a refused permission leaves the delivery where it was`() {
        val reminder = deliverWhilePermissionIsDenied()

        val list = openRemindersList()
        answerThePermissionRequest(list, PackageManager.PERMISSION_DENIED)

        // Reconciling here would be harmless but dishonest: it would post a
        // notification that the permission still forbids, and the log would fill with
        // the failure. The condition is the grant, not the answer.
        assertEquals("nothing was posted", 0, notificationsOnScreen())
        assertEquals(Reminder.Status.NOTIFIED, storedReminder(reminder.id).status)
    }

    /**
     * Take a reminder all the way to `NOTIFIED` with the permission denied: the alarm
     * fires, the receiver runs, the store is written and nothing appears on screen.
     */
    private fun deliverWhilePermissionIsDenied(): Reminder {
        val dueTime = now + oneMinute
        val reminder = addReminder(dueTime)
        val alarm = scheduledAlarms().single().pendingIntent

        now = dueTime
        send(alarm)

        assertEquals("nothing is shown while the permission is denied", 0, notificationsOnScreen())
        assertEquals(
            "but the reminder counts as delivered all the same",
            Reminder.Status.NOTIFIED,
            storedReminder(reminder.id).status
        )
        // Which is what makes the grant the only way back: the delivery asked for no
        // further alarm, because the reminder does not nag, so nothing will ever ask for
        // this notification again before the next process start. The alarm that has just
        // fired is still in the list only because Robolectric keeps a one-shot alarm the
        // platform would have dropped; what matters is that no second one joined it.
        assertEquals("the delivery set no new alarm", 1, scheduledAlarms().size)
        return reminder
    }

    // --- the app, driven the way the user drives it ------------------------------------

    /**
     * The reminders list, created the way the launcher creates it. It is the activity
     * that asks for the permission, so it is the activity whose permission result the
     * test delivers to; nothing is stubbed in between.
     */
    private fun openRemindersList(): RemindersListActivity =
        Robolectric.buildActivity(RemindersListActivity::class.java).create().get()

    /** The answer Android hands back, delivered to the activity that asked for it. */
    private fun answerThePermissionRequest(list: RemindersListActivity, answer: Int) {
        list.onRequestPermissionsResult(
            requestCodeTheListAskedWith,
            arrayOf(Manifest.permission.POST_NOTIFICATIONS),
            intArrayOf(answer)
        )
        shadowOf(Looper.getMainLooper()).idle()
    }
}
