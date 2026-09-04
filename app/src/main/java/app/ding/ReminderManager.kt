/*
 * Copyright (C) 2018-2025 Felix Wiemuth and contributors (see CONTRIBUTORS.md)
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
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.ding.data.Reminder
import app.ding.data.Reminder.Status
import app.ding.state.AlarmKind
import app.ding.state.NotificationKind
import app.ding.state.ReminderCommand
import app.ding.state.ReminderCommandRunner
import app.ding.state.ReminderEffect
import app.ding.state.ReminderEffectExecutor
import app.ding.state.TransitionOutcome
import app.ding.ui.EditReminderDialogActivity
import app.ding.util.AlarmManagerUtil
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.Date

/**
 * The app's way in to reminder state. Every change goes through [run], [addReminder]
 * or [reconcileAllReminders], which hand the work to the one runner (lock, read,
 * decide, write, then act) in `app.ding.state`.
 *
 * What lives here is the Android half: the alarms, the notifications and the
 * pending intents. The decisions themselves are in
 * [app.ding.state.transition], which knows nothing about Android.
 */
object ReminderManager {

    /**
     * ID of the main notification channel "Reminder".
     */
    const val NOTIFICATION_CHANNEL_REMINDER = "Reminder"

    private const val OFFSET_REQUEST_CODE_ADD_REMINDER_DIALOG_ACTIVITY_PENDING_INTENT = Reminder.MAX_REMINDER_ID + 1

    /**
     * The one runner, kept for the life of the process so that its lock actually
     * excludes concurrent commands.
     */
    private var runner: ReminderCommandRunner? = null

    @Synchronized
    private fun runner(context: Context): ReminderCommandRunner {
        val applicationContext = context.applicationContext
        return runner ?: ReminderCommandRunner(
            ReminderStorage.storeIn(applicationContext),
            AlarmsAndNotifications(applicationContext)
        ).also { runner = it }
    }

    /**
     * Run a command against the stored reminders. The only public way to change a
     * reminder, apart from [addReminder], which has to allocate an id first.
     */
    fun run(context: Context, command: ReminderCommand): TransitionOutcome =
        runner(context).run(command)

    /**
     * Add the reminder described by the given builder and schedule it. A new ID is
     * assigned by the store.
     *
     * @return [TransitionOutcome.Updated] with the stored reminder, or
     *     [TransitionOutcome.Refused] if the due time is not in the future
     */
    @JvmStatic
    fun addReminder(context: Context, reminderBuilder: Reminder.Builder): TransitionOutcome =
        runner(context).add(
            reminderBuilder.date.time,
            reminderBuilder.text,
            reminderBuilder.naggingRepeatInterval
        )

    /**
     * Bring alarms and notifications back in line with the stored reminders: schedule
     * every future reminder, deliver every past-due one, re-show every reminder that
     * was delivered and not dealt with. Run once per process start.
     */
    @JvmStatic
    fun reconcileAllReminders(context: Context) {
        Log.d("Reconcile", "Bringing alarms and notifications back in line with the store")
        runner(context).reconcileAll()
    }

    /**
     * Request code for a pending intent to be used to start [app.ding.ui.EditReminderDialogActivity].
     */
    private fun getRequestCodeEditReminderDialogActivityPendingIntent(reminderID: Int): Int {
        return OFFSET_REQUEST_CODE_ADD_REMINDER_DIALOG_ACTIVITY_PENDING_INTENT + reminderID
    }

    /**
     * Describes an action to be performed on a reminder. Provides [PendingIntent]s to perform
     * the different actions at a later time.
     * Used when actions have to be initiated from outside the app (e.g. for scheduled actions
     * or from a notification).
     */
    @Serializable
    sealed class ReminderAction {
        abstract val reminderId: Int

        /**
         * Deliver the reminder: show its notification and move it to [Status.NOTIFIED].
         * Carries the due time it was scheduled for, so that an alarm set for a due time
         * the store no longer holds is recognised as stale and ignored.
         */
        @Serializable
        data class Deliver(
            override val reminderId: Int,
            val expectedDueTime: Long = 0
        ) : ReminderAction() {
            /**
             * Get a minimal [PendingIntent] suitable to cancel one created with [toPendingIntent]
             * (i.e., a matching one). Pending intents match on request code and intent, not on
             * extras, so this cancels whichever alarm holds the reminder's slot.
             */
            fun getCancelPendingIntent(context: Context): PendingIntent =
                makePendingIntent(context)
        }

        /**
         * Repeat the notification and schedule the next repetition according to the reminder's
         * [Reminder.naggingRepeatInterval]. Carries the same expected due time as [Deliver].
         */
        @Serializable
        data class Nag(
            override val reminderId: Int,
            val expectedDueTime: Long = 0
        ) : ReminderAction()

        /**
         * Mark the reminder done (set its status to [Status.DONE] and cancel any current
         * notifications or scheduled actions).
         */
        @Serializable
        data class MarkDone(override val reminderId: Int) : ReminderAction()

        fun toJson(): String = Json.encodeToString(this)

        companion object {
            private const val EXTRA_STRING_ACTION =
                "app.ding.ReminderManager.extra.ACTION"

            fun fromJson(serialized: String): ReminderAction = Json.decodeFromString(serialized)
            fun getSerializedReminderActionFromIntent(intent: Intent): String =
                requireNotNull(intent.getStringExtra(EXTRA_STRING_ACTION)) { "Intent does not contain extra $EXTRA_STRING_ACTION" }
        }

        /**
         * The command this action asks for.
         */
        fun toCommand(): ReminderCommand = when (this) {
            is Deliver -> ReminderCommand.Deliver(reminderId, expectedDueTime)
            is Nag -> ReminderCommand.Nag(reminderId, expectedDueTime)
            is MarkDone -> ReminderCommand.MarkDone(reminderId)
        }

        /**
         * Run the action. A reminder that is no longer stored, or an alarm that no longer
         * matches the stored due time, is cleaned up rather than treated as an error.
         */
        fun run(context: Context) {
            run(context, toCommand())
        }

        /**
         * Get the request code used for notifications and pending intents for this reminder.
         * It depends on the action and relies on reminder IDs being even.
         */
        private fun getRequestCode(): Int =
            when (this) {
                is Deliver, is Nag -> reminderId
                is MarkDone -> reminderId + 1
            }

        protected fun makePendingIntent(context: Context, extras: Bundle? = null): PendingIntent {
            val intent = Intent()
            intent.setClass(context.applicationContext, ReminderBroadcastReceiver::class.java)
            extras?.let { intent.putExtras(it) }
            /* Using a mutable pending intent might be necessary because of scheduling with AlarmManager and the use in notifications
               (see https://developer.android.com/guide/components/intents-filters#DeclareMutabilityPendingIntent).
               As we use an explicit intent, this should be fine security-wise.
             */
            val flags = PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_MUTABLE
            return PendingIntent.getBroadcast(
                context,
                getRequestCode(),
                intent,
                flags
            )
        }

        /**
         * Create a pending intent that will start [ReminderBroadcastReceiver] to process this action.
         * Sets correct request code.
         * Uses flag [PendingIntent.FLAG_CANCEL_CURRENT] to make sure no old intent is reused.
         *
         * @param context
         * @return
         */
        fun toPendingIntent(context: Context): PendingIntent {
            val extras = Bundle().apply {
                putString(EXTRA_STRING_ACTION, toJson())
            }
            return makePendingIntent(context, extras)
        }
    }

    /**
     * Process a serialized reminder action stored in the given intent.
     * @See [processReminderAction]
     */
    fun processReminderAction(context: Context, intent: Intent) {
        processReminderAction(context, ReminderAction.getSerializedReminderActionFromIntent(intent))
    }

    /**
     * Process a serialized reminder action.
     */
    fun processReminderAction(context: Context, serializedReminderAction: String) {
        val reminderAction: ReminderAction = ReminderAction.fromJson(serializedReminderAction)
        reminderAction.run(context)
    }

    /**
     * Carries out what the transition function decided: puts alarms in reminders' slots
     * and notifications on the screen.
     */
    private class AlarmsAndNotifications(private val context: Context) : ReminderEffectExecutor {
        override fun execute(effect: ReminderEffect) {
            when (effect) {
                is ReminderEffect.SetAlarm -> setAlarm(context, effect)
                is ReminderEffect.CancelAlarm -> cancelAlarm(context, effect.reminderId)
                is ReminderEffect.ShowNotification ->
                    sendNotification(context, effect.reminder, effect.kind)

                is ReminderEffect.CancelNotification ->
                    NotificationManagerCompat.from(context).cancel(effect.reminderId)
            }
        }
    }

    /**
     * Put an alarm in the reminder's slot, replacing whatever is there. Deliver and Nag
     * share the slot because they share the request code.
     */
    private fun setAlarm(context: Context, effect: ReminderEffect.SetAlarm) {
        val action = when (effect.kind) {
            AlarmKind.DELIVER -> ReminderAction.Deliver(effect.reminderId, effect.expectedDueTime)
            AlarmKind.NAG -> ReminderAction.Nag(effect.reminderId, effect.expectedDueTime)
        }
        AlarmManagerUtil.scheduleExact(context, Date(effect.at), action.toPendingIntent(context))
    }

    /**
     * Empty the reminder's alarm slot.
     */
    private fun cancelAlarm(context: Context, reminderId: Int) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.cancel(ReminderAction.Deliver(reminderId).getCancelPendingIntent(context))
    }

    /**
     * Send a notification with swipe and click actions related to the reminder.
     *
     * @param context
     * @param reminder
     * @param kind why the notification is being shown: a delivery and a nag alert, a
     *     re-show after the process was restarted is silent
     */
    private fun sendNotification(context: Context, reminder: Reminder, kind: NotificationKind) {
        val displayOriginalDueTime = when (kind) {
            NotificationKind.DELIVER -> Prefs.isDisplayOriginalDueTimeNormal(context)
            NotificationKind.NAG -> Prefs.isDisplayOriginalDueTimeNag(context)
            NotificationKind.RESHOW -> Prefs.isDisplayOriginalDueTimeRecreate(context)
        }
        val silent = kind == NotificationKind.RESHOW
        val markDoneAction = ReminderAction.MarkDone(reminder.id)
        val markDoneIntent = markDoneAction.toPendingIntent(context)
        val editReminderIntent = EditReminderDialogActivity.getIntentEditReminder(context, reminder.id)
        val editReminderPendingIntent = PendingIntent.getActivity(
            context,
            getRequestCodeEditReminderDialogActivityPendingIntent(reminder.id),
            editReminderIntent,
            /* Using a mutable pending intent might be necessary because of scheduling with AlarmManager and the use in notifications
               (see https://developer.android.com/guide/components/intents-filters#DeclareMutabilityPendingIntent).
               As we use an explicit intent, this should be fine security-wise.
             */
            PendingIntent.FLAG_MUTABLE
        )
        val builder = NotificationCompat.Builder(
            context,
            NOTIFICATION_CHANNEL_REMINDER
        ).also {
            if (displayOriginalDueTime)
                it.setWhen(reminder.date.time).setShowWhen(true)
        }
            .setSilent(silent)
            // Defence in depth against a duplicate delivery alerting twice. The guard in
            // the transition function is what prevents the duplicate; this only limits
            // the damage if one ever gets through.
            .setOnlyAlertOnce(true)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(context.getString(R.string.notification_title))
            .setContentText(reminder.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(reminder.text))
            .setContentIntent(editReminderPendingIntent)
            .setDeleteIntent(markDoneIntent)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)

        val notificationManager = NotificationManagerCompat.from(context)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU // Permission was added in API 33
            || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        ) {
            notificationManager.notify(reminder.id, builder.build())
        } else {
            Log.e("Notifications", "Cannot send notification for reminder: permission not granted.")
        }
    }

    @JvmStatic
    fun createNotificationChannel(context: Context) {
        val name: CharSequence = context.getString(R.string.channel_name)
        val description = context.getString(R.string.channel_description)
        val importance = NotificationManager.IMPORTANCE_HIGH
        val channel = NotificationChannel(NOTIFICATION_CHANNEL_REMINDER, name, importance)
        channel.description = description
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
    }
}
