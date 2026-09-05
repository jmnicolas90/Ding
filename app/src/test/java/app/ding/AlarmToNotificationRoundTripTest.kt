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
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.core.app.NotificationManagerCompat
import app.ding.data.Reminder
import app.ding.state.ReminderCommand
import app.ding.state.TransitionOutcome
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager.ScheduledAlarm
import java.util.Date

/**
 * The trip from an added reminder to a notification on the screen, through the alarm
 * `AlarmManager` is holding and the receiver that alarm wakes.
 *
 * This is the layer the pure tests cannot see. `ReminderTransitionTest` proves that a
 * delivery is decided, and `ReminderCommandRunnerTest` proves that the effects are
 * asked for; what is untested without Android is the wiring in `ReminderManager.kt` —
 * that the alarm goes in under the reminder's own request code, that the due time
 * survives the trip through the pending intent as an extra, that the notification is
 * posted under the reminder's id, and that the swipe-away intent uses `id + 1`. Those
 * four numbers are one identity across three Android subsystems (see CLAUDE.md), and
 * getting one of them wrong is silent: the app builds, the tests pass, and a reminder
 * never fires.
 *
 * Robolectric rather than a device because it is a JVM test: it runs in the gate on
 * every commit. What only a device can answer — whether a real `AlarmManager` wakes a
 * sleeping process — is the instrumented smoke test of ticket 28.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AlarmToNotificationRoundTripTest {

    /** A fixed instant to hang the test's times off; nothing depends on which one. */
    private val startOfTest = 1788598800000L // 2026-09-05T09:00:00Z

    private val oneMinute = 60_000L

    /** What [ReminderManager] reads as the current time. The test moves it by hand. */
    private var now = startOfTest

    private lateinit var context: Application

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        // The runner is process-wide and so is its clock, so the test both holds time
        // still and makes the next command build a runner over this test's own store.
        ReminderManager.useClock { now }
        // From Android 13 on nothing is posted without it. The denied case is its own
        // ticket; here the permission is granted so that the round trip is about the
        // wiring and not about the permission.
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        // What `Main.onCreate` does on a device: a notification posted to a channel
        // that was never created is dropped.
        ReminderManager.createNotificationChannel(context)
    }

    @After
    fun tearDown() {
        ReminderManager.useClock(System::currentTimeMillis)
    }

    @Test
    fun `an added reminder is delivered by the alarm it scheduled`() {
        val dueTime = now + oneMinute
        val reminder = addReminder(dueTime)

        val alarm = scheduledAlarms().single()
        val alarmIntent = alarm.pendingIntent
        assertEquals("the alarm is set for the due time", dueTime, alarm.triggerAtMs)
        assertEquals(
            "the alarm holds the reminder's own slot",
            reminder.id,
            shadowOf(alarmIntent).requestCode
        )
        assertEquals(
            "the alarm asks for a delivery of that reminder, for that due time",
            ReminderManager.ReminderAction.Deliver(reminder.id, dueTime),
            actionIn(alarmIntent)
        )

        now = dueTime
        send(alarmIntent)

        val notification = shadowOf(notificationManager).getNotification(reminder.id)
        assertNotNull("a notification is posted under the reminder's id", notification)
        assertEquals("only that one", 1, shadowOf(notificationManager).size())
        assertEquals(
            "swiping it away marks the reminder done, from the id above the reminder's",
            reminder.id + 1,
            shadowOf(notification.deleteIntent).requestCode
        )
        assertEquals(Reminder.Status.NOTIFIED, storedReminder(reminder.id).status)
    }

    @Test
    fun `the same alarm delivered a second time notifies nothing`() {
        val dueTime = now + oneMinute
        val reminder = addReminder(dueTime)
        val alarm = scheduledAlarms().single().pendingIntent

        now = dueTime
        send(alarm)
        // Take the notification off the screen the way the shade does when the user
        // clears it without dealing with the reminder. A second delivery would put a
        // visible one back, rather than silently replacing the one already there.
        NotificationManagerCompat.from(context).cancel(reminder.id)

        send(alarm)

        // The status guard: the reminder is no longer SCHEDULED, so the second alarm
        // is stale on arrival and nothing at all happens.
        assertEquals("nothing was posted again", 0, shadowOf(notificationManager).size())
        assertEquals(Reminder.Status.NOTIFIED, storedReminder(reminder.id).status)
    }

    @Test
    fun `an alarm for a due time the reminder no longer has delivers nothing`() {
        val dueTime = now + oneMinute
        val reminder = addReminder(dueTime)
        // What `AlarmManager` is holding, kept before the reschedule replaces it: the
        // pending intent itself is cancelled by the new one, but the intent it was
        // carrying is what the OS would deliver had the old alarm been in flight.
        val oldAlarmIntent = shadowOf(scheduledAlarms().single().pendingIntent).savedIntent

        val newDueTime = dueTime + oneMinute
        ReminderManager.run(
            context,
            ReminderCommand.Reschedule(reminder.id, newDueTime, reminder.text, 0)
        )

        now = dueTime
        deliverToReceiver(oldAlarmIntent)

        // The stale-alarm guard: the due time in the alarm is not the stored one, so
        // the delivery is ignored rather than treated as an error, and the reminder
        // keeps the alarm it was rescheduled to.
        assertEquals("nothing was posted", 0, shadowOf(notificationManager).size())
        assertEquals(Reminder.Status.SCHEDULED, storedReminder(reminder.id).status)
        assertEquals(newDueTime, scheduledAlarms().single().triggerAtMs)
    }

    // --- the reminder under test ---------------------------------------------------

    private fun addReminder(dueTime: Long): Reminder {
        val result = ReminderManager.addReminder(
            context,
            Reminder.Builder(date = Date(dueTime), text = "Feed the cat")
        )
        return (result as TransitionOutcome.Updated).reminder
    }

    private fun storedReminder(id: Int): Reminder =
        ReminderStorage.getReminders(context).single { it.id == id }

    // --- what the OS is holding ----------------------------------------------------

    private val alarmManager: AlarmManager
        get() = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    private val notificationManager: NotificationManager
        get() = context.getSystemService(NotificationManager::class.java)

    private fun scheduledAlarms(): List<ScheduledAlarm> = shadowOf(alarmManager).scheduledAlarms

    /**
     * What the alarm will send when it goes off. Robolectric has deprecated the field
     * without putting a reader in its place, so the suppression is here and nowhere else.
     */
    @Suppress("DEPRECATION")
    private val ScheduledAlarm.pendingIntent: PendingIntent
        get() = requireNotNull(operation)

    /** The action the pending intent is carrying, read the way the receiver reads it. */
    private fun actionIn(pendingIntent: PendingIntent): ReminderManager.ReminderAction? =
        ReminderManager.ReminderAction.fromJsonOrNull(
            ReminderManager.ReminderAction.getSerializedReminderActionFromIntent(
                shadowOf(pendingIntent).savedIntent
            )
        )

    /**
     * Fire the alarm the way `AlarmManager` does at the due time: send the pending
     * intent it is holding, and hand the broadcast that comes out of it to the receiver
     * it is addressed to.
     *
     * The second half is Robolectric's doing rather than the app's. Robolectric
     * delivers a broadcast to the receivers a test registered and never to the ones the
     * manifest declares, so an explicit broadcast to [ReminderBroadcastReceiver]
     * reaches nobody on its own. Nothing about the wiring is taken on trust for that:
     * the intent handed to the receiver is the one that came back out of `send()`, and
     * the class it names is checked before it is delivered.
     */
    private fun send(pendingIntent: PendingIntent) {
        val alreadySent = shadowOf(context).broadcastIntents.size
        pendingIntent.send()
        shadowOf(Looper.getMainLooper()).idle()
        val sent = shadowOf(context).broadcastIntents.drop(alreadySent)
        assertEquals("the pending intent sends one broadcast", 1, sent.size)
        assertEquals(
            "addressed to the app's own receiver",
            ComponentName(context, ReminderBroadcastReceiver::class.java),
            sent.single().component
        )
        deliverToReceiver(sent.single())
    }

    /** The intent an alarm was carrying, delivered to the receiver that receives it. */
    private fun deliverToReceiver(intent: Intent) {
        ReminderBroadcastReceiver().onReceive(context, intent)
        shadowOf(Looper.getMainLooper()).idle()
    }
}
