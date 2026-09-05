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
import androidx.annotation.VisibleForTesting
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.ding.data.Reminder
import app.ding.data.Reminder.Status
import app.ding.state.AlarmKind
import app.ding.state.CommandResult
import app.ding.state.EffectsFailed
import app.ding.state.NotificationKind
import app.ding.state.PersistenceFailed
import app.ding.state.ReconcileResult
import app.ding.state.ReminderCommand
import app.ding.state.ReminderCommandRunner
import app.ding.state.ReminderEffect
import app.ding.state.ReminderEffectExecutor
import app.ding.state.TransitionOutcome
import app.ding.state.describe
import app.ding.state.notificationAlerts
import app.ding.state.notificationAlertsOnlyOnce
import app.ding.ui.EditReminderDialogActivity
import app.ding.util.AlarmManagerUtil
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
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

    /**
     * The clock every command is decided against. Read through a lambda rather than
     * handed to the runner once, so that [useClock] reaches a runner that already
     * exists as well as the next one.
     */
    private var clock: () -> Long = System::currentTimeMillis

    @Synchronized
    private fun runner(context: Context): ReminderCommandRunner {
        val applicationContext = context.applicationContext
        return runner ?: ReminderCommandRunner(
            ReminderStorage.storeIn(applicationContext),
            AlarmsAndNotifications(applicationContext),
            ::logEffectFailure
        ) { clock() }.also { runner = it }
    }

    /**
     * Throw away the runner this process was using and decide commands against [clock]
     * from now on, so that the next command builds a runner over the context it is
     * given and reads the time from there.
     *
     * Both halves are for tests, which need to hold time still and to start from a
     * store of their own: the runner and the clock both live as long as the process,
     * and a test that inherited either from the test before it would be reading another
     * test's reminders or the real wall clock. Nothing in the app calls this, and the
     * default is the system clock, so it changes no behaviour on a device.
     */
    @VisibleForTesting
    @Synchronized
    internal fun restartWithClock(clock: () -> Long) {
        this.clock = clock
        runner = null
    }

    /**
     * Say that an effect could not be carried out. The runner has already gone on to
     * the next one — the alarms of every other reminder are in the same list — so this
     * is the only record that it did not happen.
     *
     * Error level: an alarm that was not set or a notification that was not shown is a
     * reminder the user does not get, which is the worst thing this app can do, even
     * when the next reconciliation puts it right.
     *
     * The reminder's own text is not logged. The id is enough to follow one reminder
     * through the log, and the text is what the user wrote.
     */
    private fun logEffectFailure(effect: ReminderEffect, failure: Exception) {
        Log.e(
            "Effects",
            "${effect.describe()} could not be carried out; the effects after it were " +
                "still run, and the next reconciliation asks for this one again",
            failure
        )
    }

    /**
     * Run a command against the stored reminders. The only public way to change a
     * reminder, apart from [addReminder], which has to allocate an id first.
     */
    fun run(context: Context, command: ReminderCommand): CommandResult =
        runner(context).run(command)

    /**
     * Add the reminder described by the given builder and schedule it. A new ID is
     * assigned by the store.
     *
     * @return [TransitionOutcome.Updated] with the stored reminder, which holds the
     *     alarm for its due time; [EffectsFailed] around that same outcome when the
     *     reminder was stored and its alarm could not be set, which leaves it
     *     `SCHEDULED` with an empty slot until the next Reconcile — saved, but not
     *     scheduled, and the caller may not report it as set;
     *     [TransitionOutcome.Refused] if the due time is not in the future or the store
     *     has no id left to give; or [PersistenceFailed] if the store did not commit —
     *     in those last two no reminder was created and no alarm was set
     */
    @JvmStatic
    fun addReminder(context: Context, reminderBuilder: Reminder.Builder): CommandResult =
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
        when (runner(context).reconcileAll()) {
            is ReconcileResult.Reconciled -> Unit
            // There is no user in front of a startup sweep, so the failure is logged
            // and left alone. Every reminder keeps the state it has on disk, so the
            // next Reconcile — the next process start — tries the same work again.
            PersistenceFailed ->
                Log.e("Reconcile", "The store did not commit; alarms and notifications are unchanged")
        }
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
     *
     * An action is written into a pending intent held by `AlarmManager` or by a
     * notification, and both outlive the build that wrote them. The name each subtype
     * is written under is therefore pinned with [SerialName] rather than left to follow
     * the class name, and [fromJsonOrNull] still reads the names earlier builds wrote.
     */
    @Serializable
    sealed class ReminderAction {
        abstract val reminderId: Int

        /**
         * Deliver the reminder: show its notification and move it to [Status.NOTIFIED].
         * Carries the due time it was scheduled for, so that an alarm set for a due time
         * the store no longer holds is recognised as stale and ignored. It is null in a
         * payload written before alarms carried their due time.
         */
        @Serializable
        @SerialName("Deliver")
        data class Deliver(
            override val reminderId: Int,
            val expectedDueTime: Long? = null
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
        @SerialName("Nag")
        data class Nag(
            override val reminderId: Int,
            val expectedDueTime: Long? = null
        ) : ReminderAction()

        /**
         * Mark the reminder done (set its status to [Status.DONE] and cancel any current
         * notifications or scheduled actions).
         */
        @Serializable
        @SerialName("MarkDone")
        data class MarkDone(override val reminderId: Int) : ReminderAction()

        fun toJson(): String = Json.encodeToString(this)

        companion object {
            private const val EXTRA_STRING_ACTION =
                "app.ding.ReminderManager.extra.ACTION"

            /** The field kotlinx.serialization names the subtype in. */
            private const val SUBTYPE_FIELD = "type"

            /** What [Deliver] was called before it was renamed to the glossary's word. */
            private const val OLD_NAME_FOR_DELIVER = "Notify"

            /**
             * The action the payload asks for, or null if it cannot be read.
             *
             * The answer is consumed inside a broadcast receiver, so an unreadable
             * payload has to be an answer rather than an exception: the caller runs
             * Reconcile instead, which brings alarms and notifications back in line
             * with the store anyway.
             */
            fun fromJsonOrNull(serialized: String?): ReminderAction? {
                if (serialized == null) {
                    return null
                }
                return try {
                    Json.decodeFromJsonElement<ReminderAction>(
                        withCurrentSubtypeName(Json.parseToJsonElement(serialized))
                    )
                } catch (e: IllegalArgumentException) {
                    // kotlinx.serialization reports every unreadable payload with a
                    // SerializationException, which is an IllegalArgumentException.
                    null
                }
            }

            /**
             * The same payload with a subtype name this build knows.
             *
             * Earlier builds left the name to kotlinx.serialization, which writes the
             * class's full package path, and called the delivery action Notify. An
             * alarm set by such a build is still in `AlarmManager` after an upgrade, so
             * only the last part of the name is looked at and the old delivery name is
             * translated. A payload that does not name a subtype is left alone, and
             * fails to decode as it should.
             */
            private fun withCurrentSubtypeName(payload: JsonElement): JsonElement {
                val fields = payload.jsonObject
                val name = (fields[SUBTYPE_FIELD] as? JsonPrimitive)?.contentOrNull
                    ?: return payload
                val shortName = name.substringAfterLast('.')
                val currentName =
                    if (shortName == OLD_NAME_FOR_DELIVER) "Deliver" else shortName
                return JsonObject(fields + (SUBTYPE_FIELD to JsonPrimitive(currentName)))
            }

            fun getSerializedReminderActionFromIntent(intent: Intent): String? =
                intent.getStringExtra(EXTRA_STRING_ACTION)
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
            when (val result = run(context, toCommand())) {
                is TransitionOutcome -> Log.d("ReminderAction", "$this: $result")
                // Each failed effect has already been logged at error level by
                // [logEffectFailure]. There is no user in front of a broadcast, and the
                // store holds what the transition decided, so the next Reconcile asks
                // for the same effects again.
                is EffectsFailed -> Log.d(
                    "ReminderAction",
                    "$this: stored, but ${result.describeFailures()} could not be carried out"
                )
                // This runs in a broadcast receiver, with no user to tell and nothing
                // safe to retry here. The reminder keeps the state it has on disk, so
                // the next Reconcile — at the next process start — picks the work up
                // again, and the alarm that is still in its slot is unchanged too.
                PersistenceFailed ->
                    Log.e("ReminderAction", "$this: the store did not commit; nothing was done")
            }
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
     * Process a serialized reminder action. A payload that cannot be read — an intent
     * without one, or one written in a format this build no longer understands — is
     * logged and answered with a reconciliation, never with an exception: this runs in
     * a broadcast receiver, where throwing would crash the app and lose the alarm.
     * Reconcile is the safe answer because it brings every reminder's alarm and
     * notification back in line with the store, including the one this alarm was for.
     */
    fun processReminderAction(context: Context, serializedReminderAction: String?) {
        val reminderAction = ReminderAction.fromJsonOrNull(serializedReminderAction)
        if (reminderAction == null) {
            Log.e(
                "ReminderAction",
                "Could not read the action in [$serializedReminderAction]; reconciling instead"
            )
            reconcileAllReminders(context)
            return
        }
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
            .setSilent(!notificationAlerts(kind))
            // Off for a nag, which replaces a notification that is still on screen and
            // has to be heard doing it. On for a delivery, as defence in depth against a
            // duplicate delivery alerting twice; the guard in the transition function is
            // what prevents the duplicate, this only limits the damage if one gets
            // through. See `NotificationAlerting.kt`.
            .setOnlyAlertOnce(notificationAlertsOnlyOnce(kind))
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
