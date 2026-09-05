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
import app.ding.data.Reminder
import app.ding.state.TransitionOutcome
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowAlarmManager.ScheduledAlarm
import java.util.Date

/**
 * What every Robolectric test of the Android half needs before it can say anything: a
 * clock the test holds still, a notification channel, a reminder in the store, and a
 * way to fire the alarm `AlarmManager` is holding.
 *
 * Three tests were written on it and each carried its own copy — the third copy is what
 * this class replaces. It holds the driving and none of the asserting: what a test is
 * actually about stays in the test.
 *
 * The starting position it leaves behind is a fresh install with nothing granted.
 * Robolectric grants no permission by itself and its `AlarmManager` shadow answers
 * `canScheduleExactAlarms` with false, so a subclass that wants either says so, and one
 * that is about the permission being absent has to do nothing.
 *
 * [org.robolectric.annotation.Config] is deliberately not here: the SDK level a test
 * pins itself to is part of what the test is about — `ExactAlarmPermissionGrantTest`
 * exists only on 31 and 32 — so each subclass names its own.
 */
@RunWith(RobolectricTestRunner::class)
abstract class RobolectricReminderHarness {

    /** A fixed instant to hang the test's times off; nothing depends on which one. */
    private val startOfTest = 1788598800000L // 2026-09-05T09:00:00Z

    protected val oneMinute = 60_000L

    /** What [ReminderManager] reads as the current time. A test moves it by hand. */
    protected var now = startOfTest

    protected lateinit var context: Application

    @Before
    fun holdTimeStillAndCreateTheChannel() {
        context = RuntimeEnvironment.getApplication()
        // The runner is process-wide and so is its clock, so this both holds time still
        // and makes the next command build a runner over this test's own store.
        ReminderManager.restartWithClock { now }
        // What `Main.onCreate` does on a device: a notification posted to a channel that
        // was never created is dropped.
        ReminderManager.createNotificationChannel(context)
    }

    @After
    fun giveTheClockBack() {
        ReminderManager.restartWithClock(System::currentTimeMillis)
    }

    // --- the reminder under test ---------------------------------------------------

    protected fun addReminder(dueTime: Long, text: String = "Feed the cat"): Reminder {
        val result = ReminderManager.addReminder(
            context,
            Reminder.Builder(date = Date(dueTime), text = text)
        )
        // Named rather than cast blind: an Add whose alarm could not be set answers with
        // EffectsFailed around the same outcome, and that is the failure the tests exist
        // to catch — it may not arrive as a ClassCastException.
        assertTrue("the reminder was stored and its alarm set, not $result", result is TransitionOutcome.Updated)
        return (result as TransitionOutcome.Updated).reminder
    }

    protected fun storedReminder(id: Int): Reminder =
        ReminderStorage.getReminders(context).single { it.id == id }

    // --- what the OS is holding ----------------------------------------------------

    protected val alarmManager: AlarmManager
        get() = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    protected val notificationManager: NotificationManager
        get() = context.getSystemService(NotificationManager::class.java)

    protected fun scheduledAlarms(): List<ScheduledAlarm> = shadowOf(alarmManager).scheduledAlarms

    /** How many notifications are on the screen. */
    protected fun notificationsOnScreen(): Int = shadowOf(notificationManager).size()

    /**
     * What the alarm will send when it goes off. Robolectric has deprecated the field
     * without putting a reader in its place, so the suppression is here and nowhere else.
     */
    @Suppress("DEPRECATION")
    protected val ScheduledAlarm.pendingIntent: PendingIntent
        get() = requireNotNull(operation)

    protected fun grantNotificationPermission() {
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    // --- driving the OS ------------------------------------------------------------

    /**
     * Fire the alarm the way `AlarmManager` does at the due time: send the pending
     * intent it is holding, and hand the broadcast that comes out of it to the receiver
     * it is addressed to.
     *
     * The second half is Robolectric's doing rather than the app's. Robolectric delivers
     * a broadcast to the receivers a test registered and never to the ones the manifest
     * declares, so an explicit broadcast to [ReminderBroadcastReceiver] reaches nobody on
     * its own. Nothing about the wiring is taken on trust for that: the intent handed to
     * the receiver is the one that came back out of `send()`, and the class it names is
     * checked before it is delivered.
     */
    protected fun send(pendingIntent: PendingIntent) {
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
    protected fun deliverToReceiver(intent: Intent) {
        ReminderBroadcastReceiver().onReceive(context, intent)
        shadowOf(Looper.getMainLooper()).idle()
    }
}
