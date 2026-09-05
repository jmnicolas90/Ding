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
import android.app.NotificationManager
import android.content.Context
import android.service.notification.StatusBarNotification
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import app.ding.data.Reminder
import app.ding.state.ReminderCommand
import app.ding.state.TransitionOutcome
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Date

/**
 * The one question no JVM test can answer: does a real `AlarmManager` fire the alarm
 * the app set, and does a notification actually reach the system?
 *
 * `AlarmToNotificationRoundTripTest` proves the wiring under Robolectric — request
 * codes, extras, notification ids — by firing the pending intent itself. Here nothing
 * is fired by the test: the reminder is added a few seconds from now and the platform
 * is left to do the rest, so what is under test is the platform's side of the bargain,
 * on the same pure-AOSP API 36 image the app ships against.
 *
 * The notification is read back from [NotificationManager.getActiveNotifications],
 * which reports the app's own notifications, rather than from the shade with
 * UiAutomator: the shade needs a dependency of its own and breaks on rendering
 * differences, and neither has anything to do with whether the reminder fired.
 *
 * What this cannot cover is a *dead* process being woken, because a test cannot
 * survive its own process being killed. That needs an adb script around this harness,
 * and is charted separately.
 */
@RunWith(AndroidJUnit4::class)
class AlarmFiresOnDeviceTest {

    /**
     * From Android 13 on nothing is posted without it, and a fresh emulator has not
     * been asked. Exact alarms need no such rule: `USE_EXACT_ALARM` in the manifest is
     * granted at install from API 33 on.
     */
    @get:Rule
    val postNotifications: GrantPermissionRule =
        GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)

    /** How far ahead the reminder is set: long enough to be a real alarm, not a sweep. */
    private val dueInMillis = 5_000L

    /** How long the alarm is given before the test calls it a failure to fire. */
    private val patienceMillis = 60_000L

    private val pollMillis = 250L

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val notificationManager: NotificationManager
        get() = context.getSystemService(NotificationManager::class.java)

    /** The reminder this test added, so that it is taken back off the device. */
    private var addedReminderId: Int? = null

    @After
    fun tearDown() {
        addedReminderId?.let { id ->
            // Delete rather than mark done: this reminder is the test's, not the
            // user's, and Delete cancels its alarm and its notification on the way out.
            ReminderManager.run(context, ReminderCommand.Delete(id))
            notificationManager.cancel(id)
        }
    }

    @Test
    fun `a reminder due seconds from now is delivered by the platform`() {
        val dueTime = System.currentTimeMillis() + dueInMillis
        val result = ReminderManager.addReminder(
            context,
            Reminder.Builder(date = Date(dueTime), text = "Feed the cat")
        )
        // Named rather than cast blind: an Add that was refused, or whose alarm could
        // not be set, is exactly the failure this test exists to catch, and it may not
        // arrive as a ClassCastException.
        assertTrue("the reminder was stored and its alarm set, not $result", result is TransitionOutcome.Updated)
        val reminder = (result as TransitionOutcome.Updated).reminder
        addedReminderId = reminder.id

        assertNotNull(
            "no notification under id ${reminder.id} within ${patienceMillis / 1000}s of a due" +
                " time ${dueInMillis / 1000}s away: the alarm did not fire, or firing it" +
                " posted nothing",
            awaitNotification(reminder.id)
        )
    }

    /**
     * Wait for the notification posted under [id], or give up. Polling rather than a
     * listener because there is nothing to listen to from inside the app's own process:
     * the notification is posted by the broadcast receiver the alarm wakes, on its own
     * schedule, and the platform is under no obligation to tell this test about it.
     */
    private fun awaitNotification(id: Int): StatusBarNotification? {
        val giveUpAt = System.currentTimeMillis() + dueInMillis + patienceMillis
        while (System.currentTimeMillis() < giveUpAt) {
            notificationManager.activeNotifications.firstOrNull { it.id == id }?.let { return it }
            Thread.sleep(pollMillis)
        }
        return null
    }
}
