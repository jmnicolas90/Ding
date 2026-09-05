/*
 * Copyright (C) 2018-2025 Felix Wiemuth and contributors (see CONTRIBUTORS.md)
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

package app.ding;

import static android.content.Context.MODE_PRIVATE;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.StringRes;
import androidx.core.app.ActivityCompat;
import androidx.preference.PreferenceManager;

import app.ding.data.NagIntervalSetting;
import app.ding.data.Reminder;
import app.ding.data.TimePickerTextSizeSetting;

/**
 * Stores preferences and current status of the app.
 *
 * @author Felix Wiemuth
 */
public class Prefs {

    private static final String TAG = "Prefs";

    public static class Defaults {
        /**
         * Default size of the reminder dialog's time display, in sp. Also the value in
         * {@code preferences_ui.xml}.
         * <p>
         * Half of the platform's own 60sp time header, which is what the previous default of
         * 12 came out to on a typical phone back when the number was scaled by the screen
         * density twice: 12 x 2.625 x 2.625 px on this 420 dpi screen, about 31sp. The number
         * is now the size itself, so it no longer changes meaning from one screen to the next.
         */
        public static final int REMINDER_DIALOG_TIMEPICKER_TEXTSIZE = 30;
        public static final int REMINDER_DIALOG_TIMEPICKER_HEIGHT = 175;
        /**
         * Default nag interval in minutes. Also the value in {@code preferences.xml}.
         */
        public static final int NAGGING_REPEAT_INTERVAL = 1;
    }

    public static final String PREF_KEY_RUN_ON_BOOT = "run_on_boot";

    /**
     * Name of preferences that store the internal state of the app, like scheduled notifications.
     */
    private static final String PREFS_STATE = "state";

    /**
     * The version of the format reminders are saved at key {@link #PREF_STATE_CURRENT_REMINDERS}.
     */
    static final String PREF_STATE_REMINDERS_FORMAT_VERSION = "remindersFormatVersion";

    /**
     * A stored reminder list that could not be read, kept exactly as it was found so that
     * the user can still export it. {@link ReminderStorage} moves the value here before
     * anything writes to {@link #PREF_STATE_CURRENT_REMINDERS} again, and only ever holds
     * one: a second unreadable value is dropped and the first one kept.
     */
    static final String PREF_STATE_REMINDERS_UNREADABLE = "reminders_unreadable";

    /**
     * The format version the value at {@link #PREF_STATE_REMINDERS_UNREADABLE} was stored at.
     */
    static final String PREF_STATE_REMINDERS_UNREADABLE_FORMAT_VERSION = "reminders_unreadable_format_version";

    /**
     * When the value at {@link #PREF_STATE_REMINDERS_UNREADABLE} was set aside, in epoch
     * milliseconds.
     */
    static final String PREF_STATE_REMINDERS_UNREADABLE_AT = "reminders_unreadable_at";

    /**
     * The next ID for a reminder. Durable on its own: it survives an unreadable
     * {@link #PREF_STATE_CURRENT_REMINDERS} being set aside, because the IDs it has
     * already handed out are still live outside the store as notification IDs and
     * pending intent request codes, and no ID may ever be handed out twice.
     */
    static final String PREF_STATE_NEXTID = "nextid";

    /**
     * GSON-serialized list of {@link app.ding.data.Reminder}s.
     */
    static final String PREF_STATE_CURRENT_REMINDERS = "reminders";

    /**
     * Indicates whether the list of reminders {@link #PREF_STATE_CURRENT_REMINDERS} has been updated.
     */
    private static final String PREF_STATE_REMINDERS_UPDATED = "remindersUpdated";
    private static final String PREF_STATE_WELCOME_MESSAGE_SHOWN = "welcomeMessageShown";
    private static final String PREF_STATE_ADD_REMINDER_DIALOG_USED = "AddReminderDialogUsed";

    private static final String PREF_STATE_BATTERY_OPTIMIZATION_DONT_SHOW_AGAIN = "battery_optimization_dont_show_again";
    private static final String PREF_STATE_SCHEDULE_EXACT_PERMISSION_DONT_SHOW_AGAIN = "schedule_exact_permission_dont_show_again";
    private static final String PREF_STATE_RUN_ON_BOOT_DONT_SHOW_AGAIN = "run_on_boot_dont_show_again";

    public static final int PERMISSION_REQUEST_CODE_BOOT = 1;

    static SharedPreferences getStatePrefs(Context context) {
        return context.getSharedPreferences(PREFS_STATE, MODE_PRIVATE);
    }

    public static boolean isRemindersUpdated(Context context) {
        return getStatePrefs(context).getBoolean(PREF_STATE_REMINDERS_UPDATED, false);
    }

    @SuppressLint("ApplySharedPref")
    public static void setRemindersUpdated(boolean b, Context context) {
        getStatePrefs(context).edit().putBoolean(PREF_STATE_REMINDERS_UPDATED, b).commit();
    }

    /**
     * Checks whether the welcome message has been shown and if not, saves the version at which it now is shown.
     *
     * @param context
     * @return
     */
    public static boolean checkAndUpdateWelcomeMessageShown(Context context) {
        int lastShown = getStatePrefs(context).getInt(PREF_STATE_WELCOME_MESSAGE_SHOWN, -1);
        if (lastShown == -1) {
            getStatePrefs(context).edit().putInt(PREF_STATE_WELCOME_MESSAGE_SHOWN, BuildConfig.VERSION_CODE).apply();
            return false;
        } else {
            return true;
        }
    }

    public static boolean isAddReminderDialogUsed(Context context) {
        return getStatePrefs(context).getBoolean(PREF_STATE_ADD_REMINDER_DIALOG_USED, false);
    }

    public static void setAddReminderDialogUsed(Context context) {
        getStatePrefs(context).edit().putBoolean(PREF_STATE_ADD_REMINDER_DIALOG_USED, true).apply();
    }

    public static boolean isBatteryOptimizationDontShowAgain(Context context) {
        return getStatePrefs(context).getBoolean(PREF_STATE_BATTERY_OPTIMIZATION_DONT_SHOW_AGAIN, false);
    }

    public static void setBatteryOptimizationDontShowAgain(Context context) {
        getStatePrefs(context).edit().putBoolean(PREF_STATE_BATTERY_OPTIMIZATION_DONT_SHOW_AGAIN, true).apply();
    }

    public static boolean isScheduleExactPermissionDontShowAgain(Context context) {
        return getStatePrefs(context).getBoolean(PREF_STATE_SCHEDULE_EXACT_PERMISSION_DONT_SHOW_AGAIN, false);
    }

    public static void setScheduleExactPermissionDontShowAgain(Context context) {
        getStatePrefs(context).edit().putBoolean(PREF_STATE_SCHEDULE_EXACT_PERMISSION_DONT_SHOW_AGAIN, true).apply();
    }

    public static boolean isRunOnBootDontShowAgain(Context context) {
        return getStatePrefs(context).getBoolean(PREF_STATE_RUN_ON_BOOT_DONT_SHOW_AGAIN, false);
    }

    public static void setRunOnBootDontShowAgain(Context context) {
        getStatePrefs(context).edit().putBoolean(PREF_STATE_RUN_ON_BOOT_DONT_SHOW_AGAIN, true).apply();
    }

    public static boolean isRunOnBoot(Context context) {
        return getBooleanPref(R.string.prefkey_run_on_boot, false, context);
    }

    /**
     * Sets the run-on-boot preference and enables/disables the boot receiver.
     * @param context
     * @param value
     */
    public static void setRunOnBoot(Context context, Boolean value) {
        edit(context).putBoolean(Prefs.PREF_KEY_RUN_ON_BOOT, value).apply();
        BootReceiver.setBootReceiverEnabled(context, value);
    }

    /**
     * Try to enable running on boot; if the required permission is not granted, ask the user on the given activity.
     *
     * @param context
     * @param activity
     */
    public static void enableRunOnBoot(Context context, Activity activity) {
        // If the required permission is not granted yet, ask the user
        if (!BootReceiver.isPermissionGranted(context.getApplicationContext())) {
            ActivityCompat.requestPermissions(activity, new String[]{Manifest.permission.RECEIVE_BOOT_COMPLETED}, PERMISSION_REQUEST_CODE_BOOT);
        }
        // If permission is now given, enable run on boot
        if (BootReceiver.isPermissionGranted(context.getApplicationContext())) {
            setRunOnBoot(context, true);
            PreferenceManager.getDefaultSharedPreferences(context).edit().putBoolean(PREF_KEY_RUN_ON_BOOT, true).apply();
        } else {
            Toast.makeText(context, R.string.toast_permission_not_granted, Toast.LENGTH_LONG).show();
        }
    }

    /**
     * Check the system settings on whether battery optimization is disabled for this app.
     *
     * @param context
     * @return
     */
    public static boolean isIgnoringBatteryOptimization(Context context) {
        PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        return pm.isIgnoringBatteryOptimizations(context.getPackageName());
    }

    public static Intent getIntentDisableBatteryOptimization(Context context) {
        @SuppressLint("BatteryLife") Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
        intent.setData(Uri.parse("package:" + context.getPackageName()));
        return intent;
    }

    public static Intent getIntentScheduleExactSettings(Context context) {
        @SuppressLint("BatteryLife") Intent intent = new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM);
        intent.setData(Uri.parse("package:" + context.getPackageName()));
        return intent;
    }

    /**
     * The default nag interval in minutes, always within the bound {@link Reminder} enforces.
     * <p>
     * This is read when the reminder dialog opens and when the settings summary is drawn, so
     * it may not throw and may not hand out a value a reminder would refuse. Three things can
     * be wrong with what is stored: an older build let the settings input store any number up
     * to {@code Integer.MAX_VALUE}, the preferences file can be edited by hand, and a value of
     * another type in it makes the read itself throw {@link ClassCastException}. What counts
     * as usable is {@code naggingRepeatIntervalFromStored} in {@link NagIntervalSetting},
     * which has no Android in it and is tested on a plain JVM.
     * <p>
     * A value that cannot be used is replaced by the default in storage, once, rather than
     * only passed over. Passing it over left the settings editor showing text the app was not
     * using and made every later read log the same complaint again.
     */
    public static int getNaggingRepeatInterval(Context context) {
        String defaultValue = String.valueOf(Defaults.NAGGING_REPEAT_INTERVAL);
        Integer interval = NagIntervalSetting.naggingRepeatIntervalFromStored(
                () -> getStringPref(R.string.prefkey_nagging_repeat_interval, defaultValue, context));
        if (interval != null) {
            return interval;
        }
        Log.w(TAG, "Stored nag interval is not a whole number of "
                + Reminder.MIN_NAGGING_REPEAT_INTERVAL + ".." + Reminder.MAX_NAGGING_REPEAT_INTERVAL
                + " minutes, or is of another type; storing the default of "
                + Defaults.NAGGING_REPEAT_INTERVAL + " minute(s) in its place.");
        // commit(), not apply(): the result is the only way to know whether the unusable
        // value is really gone, and saying so in the log is worth one small synchronous write
        // on a path that is taken once, when the stored value is already broken.
        boolean stored = edit(context)
                .putString(context.getString(R.string.prefkey_nagging_repeat_interval), defaultValue)
                .commit();
        if (!stored) {
            Log.w(TAG, "Could not store the default nag interval; the unusable value is still there.");
        }
        return Defaults.NAGGING_REPEAT_INTERVAL;
    }

    /**
     * The size of the reminder dialog's time display, <b>in sp</b>, always within
     * {@code MIN_TIME_PICKER_TEXT_SIZE_SP}..{@code MAX_TIME_PICKER_TEXT_SIZE_SP}.
     * <p>
     * sp, not dp: it is text, so it scales with the user's system font size. The caller
     * hands it to {@code setTextSize(TypedValue.COMPLEX_UNIT_SP, ...)} and converts
     * nothing itself.
     * <p>
     * This is read while the reminder dialog is opening and while the settings summary is
     * drawn, so it may not throw. It used to be {@code Integer.parseInt} of whatever was
     * stored, which threw {@link NumberFormatException} on a hand-edited preferences file
     * and {@link ClassCastException} on a value of another type. What counts as usable is
     * {@code timePickerTextSizeFromStored} in {@link TimePickerTextSizeSetting}, which has
     * no Android in it and is tested on a plain JVM.
     * <p>
     * A value that cannot be used is replaced by the default in storage, once, rather than
     * only passed over — the same choice as {@link #getNaggingRepeatInterval}, and for the
     * same reason: passing it over leaves the settings editor showing text the app is not
     * using.
     */
    public static int getReminderDialogTimePickerTextSize(Context context) {
        String defaultValue = String.valueOf(Defaults.REMINDER_DIALOG_TIMEPICKER_TEXTSIZE);
        Integer sizeSp = TimePickerTextSizeSetting.timePickerTextSizeFromStored(
                () -> getStringPref(R.string.prefkey_reminder_dialog_timepicker_text_size, defaultValue, context));
        if (sizeSp != null) {
            return sizeSp;
        }
        Log.w(TAG, "Stored time display size is not a whole number of "
                + TimePickerTextSizeSetting.MIN_TIME_PICKER_TEXT_SIZE_SP + ".."
                + TimePickerTextSizeSetting.MAX_TIME_PICKER_TEXT_SIZE_SP
                + " sp, or is of another type; storing the default of "
                + Defaults.REMINDER_DIALOG_TIMEPICKER_TEXTSIZE + " sp in its place.");
        // commit(), not apply(): see getNaggingRepeatInterval.
        boolean stored = edit(context)
                .putString(context.getString(R.string.prefkey_reminder_dialog_timepicker_text_size), defaultValue)
                .commit();
        if (!stored) {
            Log.w(TAG, "Could not store the default time display size; the unusable value is still there.");
        }
        return Defaults.REMINDER_DIALOG_TIMEPICKER_TEXTSIZE;
    }

    public static int getReminderDialogTimePickerHeight(Context context) {
        return Integer.parseInt(getStringPref(R.string.prefkey_reminder_dialog_timepicker_height, String.valueOf(Defaults.REMINDER_DIALOG_TIMEPICKER_HEIGHT), context));
    }

    public static boolean isDisplayOriginalDueTimeNormal(Context context) {
        return getBooleanPref(R.string.prefkey_display_original_due_time_normal, false, context);
    }

    public static boolean isDisplayOriginalDueTimeNag(Context context) {
        return getBooleanPref(R.string.prefkey_display_original_due_time_nag, false, context);
    }

    public static boolean isDisplayOriginalDueTimeRecreate(Context context) {
        return getBooleanPref(R.string.prefkey_display_original_due_time_recreate, false, context);
    }

    public static void resetAllDontShowAgain(Context context) {
        // Reset to default value by removing preferences (holds the preference file small)
        getStatePrefs(context).edit()
                .remove(PREF_STATE_BATTERY_OPTIMIZATION_DONT_SHOW_AGAIN)
                .remove(PREF_STATE_SCHEDULE_EXACT_PERMISSION_DONT_SHOW_AGAIN)
                .remove(PREF_STATE_RUN_ON_BOOT_DONT_SHOW_AGAIN)
                .apply();
    }

    /**
     * Get editor for default shared preferences.
     *
     * @return
     */
    private static SharedPreferences.Editor edit(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context).edit();
    }

    /**
     * Get a string from settings preferences using a key from a string resource.
     *
     * @param key      {@link} the resource id of the key
     * @param defValue the default value to be used if the preference is not set
     * @param context
     * @return
     */
    public static String getStringPref(@StringRes int key, String defValue, Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context).getString(context.getString(key), defValue);
    }

    /**
     * Get a boolean from default preferences using a key from a string resource.
     *
     * @param key      {@link} the resource id of the key
     * @param defValue the default value to be used if the preference is not set
     * @param context
     * @return
     */
    public static boolean getBooleanPref(@StringRes int key, boolean defValue, Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context).getBoolean(context.getString(key), defValue);
    }

    /**
     * Get an int from default preferences using a key from a string resource.
     *
     * @param key      {@link} the resource id of the key
     * @param defValue the default value to be used if the preference is not set
     * @param context
     * @return
     */
    public static int getIntPref(@StringRes int key, int defValue, Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context).getInt(context.getString(key), defValue);
    }
}
