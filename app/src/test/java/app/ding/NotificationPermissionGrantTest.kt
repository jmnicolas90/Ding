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
import android.app.AlarmManager
import android.app.Application
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Looper
import app.ding.data.Reminder
import app.ding.state.TransitionOutcome
import app.ding.ui.reminderslist.RemindersListActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.Date

/**
 * A delivery that happened while `POST_NOTIFICATIONS` was denied, and what the app does
 * about it once the user grants the permission.
 *
 * The hole this closes: a denied permission does not stop a delivery, it only stops the
 * notification. The reminder is written as `NOTIFIED` all the same, and a reminder that
 * does not nag has no alarm left afterwards — so without something to put the
 * notification back, the reminder is invisible until the next process start, which may
 * be days away. The reminders list asks for the permission on start, but the startup
 * sweep in `Main.onCreate` has already run by then. The grant is therefore the second
 * thing that reconciles, and this is the test of it.
 *
 * Robolectric rather than a device for the reason `AlarmToNotificationRoundTripTest`
 * gives: it runs in the gate on every commit. That test is where the wiring from an
 * alarm to a notification is checked in full; the delivery here is driven the same way
 * but only asserted on where the permission is what decides the outcome.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NotificationPermissionGrantTest {

    /** A fixed instant to hang the test's times off; nothing depends on which one. */
    private val startOfTest = 1788598800000L // 2026-09-05T09:00:00Z

    private val oneMinute = 60_000L

    /** What [ReminderManager] reads as the current time. The test moves it by hand. */
    private var now = startOfTest

    private lateinit var context: Application

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        // Process-wide, so the next command builds a runner over this test's own store
        // and reads the time from here. See ReminderManager.restartWithClock.
        ReminderManager.restartWithClock { now }
        ReminderManager.createNotificationChannel(context)
        // No permission is granted here, and Robolectric grants none by itself: the
        // denied case is the starting position of every test in this class.
    }

    @After
    fun tearDown() {
        ReminderManager.restartWithClock(System::currentTimeMillis)
    }

    @Test
    fun `a delivery suppressed by the denied permission is shown once it is granted`() {
        val reminder = deliverWhilePermissionIsDenied()

        val list = openRemindersList()
        assertEquals(
            "opening the list on its own shows nothing",
            0,
            shadowOf(notificationManager).size()
        )

        grantNotificationPermission()
        list.onRequestPermissionsResult(
            REQUEST_CODE_NOTIFICATIONS,
            arrayOf(Manifest.permission.POST_NOTIFICATIONS),
            intArrayOf(PackageManager.PERMISSION_GRANTED)
        )
        shadowOf(Looper.getMainLooper()).idle()

        assertNotNull(
            "the reminder the user never saw is on screen, under its own id",
            shadowOf(notificationManager).getNotification(reminder.id)
        )
        assertEquals("only that one", 1, shadowOf(notificationManager).size())
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
        list.onRequestPermissionsResult(
            REQUEST_CODE_NOTIFICATIONS,
            arrayOf(Manifest.permission.POST_NOTIFICATIONS),
            intArrayOf(PackageManager.PERMISSION_DENIED)
        )
        shadowOf(Looper.getMainLooper()).idle()

        // Reconciling here would be harmless but dishonest: it would post a
        // notification that the permission still forbids, and the log would fill with
        // the failure. The condition is the grant, not the answer.
        assertEquals("nothing was posted", 0, shadowOf(notificationManager).size())
        assertEquals(Reminder.Status.NOTIFIED, storedReminder(reminder.id).status)
    }

    /**
     * Take a reminder all the way to `NOTIFIED` with the permission denied: the alarm
     * fires, the receiver runs, the store is written and nothing appears on screen.
     */
    private fun deliverWhilePermissionIsDenied(): Reminder {
        val dueTime = now + oneMinute
        val reminder = addReminder(dueTime)
        val alarm = requireNotNull(scheduledAlarm())

        now = dueTime
        fire(alarm)

        assertEquals("nothing is shown while the permission is denied", 0, shadowOf(notificationManager).size())
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
        assertEquals("the delivery set no new alarm", 1, shadowOf(alarmManager).scheduledAlarms.size)
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

    private fun grantNotificationPermission() {
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun addReminder(dueTime: Long): Reminder {
        val result = ReminderManager.addReminder(
            context,
            Reminder.Builder(date = Date(dueTime), text = "Feed the cat")
        )
        assertTrue("the reminder was stored and its alarm set, not $result", result is TransitionOutcome.Updated)
        return (result as TransitionOutcome.Updated).reminder
    }

    private fun storedReminder(id: Int): Reminder =
        ReminderStorage.getReminders(context).single { it.id == id }

    // --- what the OS is holding ----------------------------------------------------

    private val alarmManager: AlarmManager
        get() = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    private val notificationManager: NotificationManager
        get() = context.getSystemService(NotificationManager::class.java)

    /** The one alarm the app has set, or null when it holds none. */
    @Suppress("DEPRECATION") // Robolectric deprecated `operation` without replacing it.
    private fun scheduledAlarm(): PendingIntent? =
        shadowOf(alarmManager).scheduledAlarms.singleOrNull()?.operation

    /**
     * Fire the alarm the way `AlarmManager` does at the due time, and hand what comes
     * out of it to the receiver it names. Robolectric delivers a broadcast only to a
     * receiver a test registered, never to one the manifest declares, which is why the
     * last step is by hand; `AlarmToNotificationRoundTripTest` is where the addressing
     * itself is checked.
     */
    private fun fire(pendingIntent: PendingIntent) {
        val alreadySent = shadowOf(context).broadcastIntents.size
        pendingIntent.send()
        shadowOf(Looper.getMainLooper()).idle()
        val sent = shadowOf(context).broadcastIntents.drop(alreadySent).single()
        ReminderBroadcastReceiver().onReceive(context, sent)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private companion object {
        /** The code the list activity asks for `POST_NOTIFICATIONS` with. */
        const val REQUEST_CODE_NOTIFICATIONS = 0
    }
}
