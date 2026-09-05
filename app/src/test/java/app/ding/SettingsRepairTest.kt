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

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.annotation.StringRes
import androidx.preference.PreferenceManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * What the settings reads do about a stored value they cannot use — a hand-edited
 * preferences file, a restored backup, an older build's input — and what they leave
 * behind them.
 *
 * The decision each one makes is a pure function with its own test on a plain JVM
 * (`BooleanSettingTest`, `NagIntervalSettingTest`, `TimePickerTextSizeSettingTest`).
 * What those cannot see is the wrapper around it in `Prefs.java`: whether the default
 * really comes back, whether the repair lands under the key that was read, and whether
 * it was written with `commit()` rather than `apply()`. The three switch reads matter
 * most, because they are on the delivery path: the notification is built after the
 * store has already committed the reminder as delivered, so a read that threw there
 * would consume the alarm and show nothing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SettingsRepairTest {

    private lateinit var context: Context
    private lateinit var settings: SharedPreferences

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        settings = PreferenceManager.getDefaultSharedPreferences(context)
    }

    @Test
    fun `a delivery notification's switch of another type reads as its default`() {
        assertRepairedToFalse(R.string.prefkey_display_original_due_time_normal) {
            Prefs.isDisplayOriginalDueTimeNormal(it)
        }
    }

    @Test
    fun `a nag notification's switch of another type reads as its default`() {
        assertRepairedToFalse(R.string.prefkey_display_original_due_time_nag) {
            Prefs.isDisplayOriginalDueTimeNag(it)
        }
    }

    @Test
    fun `a re-shown notification's switch of another type reads as its default`() {
        assertRepairedToFalse(R.string.prefkey_display_original_due_time_recreate) {
            Prefs.isDisplayOriginalDueTimeRecreate(it)
        }
    }

    @Test
    fun `the re-shown switch declares a default that Prefs does not share`() {
        // Ticket 22 found this and left it alone: which of the two is right is a
        // product question, and changing either would change what a notification looks
        // like for everyone who has never opened that sub-screen. It is pinned here so
        // that it stays a decision rather than becoming a surprise — a build that means
        // to settle it changes this test on purpose.
        assertFalse(
            "with nothing stored, Prefs answers false",
            Prefs.isDisplayOriginalDueTimeRecreate(context)
        )

        // Nothing does this to that screen today: it is a sub-screen, and `Main` seeds
        // the top-level ones only. This is what would happen if something did.
        PreferenceManager.setDefaultValues(context, R.xml.preferences_notifications, true)

        val key = context.getString(R.string.prefkey_display_original_due_time_recreate)
        assertEquals("the switch's own default is the opposite", true, settings.all[key])
        assertTrue(
            "and then the app follows the switch",
            Prefs.isDisplayOriginalDueTimeRecreate(context)
        )
    }

    @Test
    fun `a nag interval of another type reads as its default`() {
        val key = context.getString(R.string.prefkey_nagging_repeat_interval)
        val writes = storeSomethingUnusableAt(key)

        assertEquals(
            Prefs.Defaults.NAGGING_REPEAT_INTERVAL,
            Prefs.getNaggingRepeatInterval(writes.context)
        )

        assertRepairedTo(key, Prefs.Defaults.NAGGING_REPEAT_INTERVAL.toString(), writes)
    }

    @Test
    fun `a time display size of another type reads as its default`() {
        val key = context.getString(R.string.prefkey_reminder_dialog_timepicker_text_size)
        val writes = storeSomethingUnusableAt(key)

        assertEquals(
            Prefs.Defaults.REMINDER_DIALOG_TIMEPICKER_TEXTSIZE,
            Prefs.getReminderDialogTimePickerTextSize(writes.context)
        )

        assertRepairedTo(
            key,
            Prefs.Defaults.REMINDER_DIALOG_TIMEPICKER_TEXTSIZE.toString(),
            writes
        )
    }

    /**
     * Put a value of a type the read does not expect under [key], read it through
     * [read], and check what the wrapper promises: the default comes back, and it is
     * stored under that same key in its place.
     */
    private fun assertRepairedToFalse(@StringRes key: Int, read: (Context) -> Boolean) {
        val keyName = context.getString(key)
        val writes = storeSomethingUnusableAt(keyName)

        assertFalse("the default comes back rather than an exception", read(writes.context))

        assertRepairedTo(keyName, false, writes)
    }

    /**
     * The repair happened: the default is under the key that was read, as a value of
     * the type the read expects, and it was written with `commit()`.
     *
     * `commit()` rather than `apply()` is part of what these reads promise, because the
     * answer is the only way to know the unusable value is really gone — `apply()`
     * writes in the background and returns nothing, so a durable write that failed
     * would be invisible and the log would say the repair worked.
     */
    private fun assertRepairedTo(key: String, expected: Any, writes: RecordedWrites) {
        assertEquals("the default is stored under the key that was read", expected, settings.all[key])
        assertEquals("with commit(), whose answer says whether it worked", 1, writes.settings.commits)
        assertEquals("and never with apply(), which answers nothing", 0, writes.settings.applies)
    }

    /**
     * A string where the caller expects something else, which is all it takes: shared
     * preferences answer a read of the wrong type with [ClassCastException], and a
     * restored backup or a hand-edited file is enough to get there.
     *
     * @return the context to read through, and the settings that remember how they
     *     were written to. The write above is made straight to the real settings, so
     *     only the repair itself is counted.
     */
    private fun storeSomethingUnusableAt(key: String): RecordedWrites {
        assertTrue(settings.edit().putString(key, "not what the app expects").commit())
        val recording = RecordingSettings(settings)
        return RecordedWrites(SettingsContext(context, recording), recording)
    }

    private class RecordedWrites(val context: Context, val settings: RecordingSettings)

    /**
     * A context whose settings are [settings]. Every name gets the same ones, which is
     * enough here: the reads under test all go through the default settings, and
     * nothing in this test touches the ones the reminders are stored in.
     */
    private class SettingsContext(base: Context, private val settings: SharedPreferences) :
        ContextWrapper(base) {
        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = settings
    }

    /**
     * Settings that remember how their writes were made, over the real ones so that
     * what is written is really stored.
     *
     * Every `put` is overridden to hand back this editor rather than the one
     * underneath, because the calls under test are one chain —
     * `edit().putBoolean(..).commit()` — and a `put` that answered with the real editor
     * would take the `commit()` with it.
     */
    private class RecordingSettings(private val real: SharedPreferences) :
        SharedPreferences by real {
        var commits = 0
        var applies = 0

        override fun edit(): SharedPreferences.Editor = RecordingEditor(real.edit())

        private inner class RecordingEditor(private val realEditor: SharedPreferences.Editor) :
            SharedPreferences.Editor by realEditor {
            override fun putString(key: String?, value: String?) = also { realEditor.putString(key, value) }
            override fun putStringSet(key: String?, values: MutableSet<String>?) =
                also { realEditor.putStringSet(key, values) }

            override fun putInt(key: String?, value: Int) = also { realEditor.putInt(key, value) }
            override fun putLong(key: String?, value: Long) = also { realEditor.putLong(key, value) }
            override fun putFloat(key: String?, value: Float) = also { realEditor.putFloat(key, value) }
            override fun putBoolean(key: String?, value: Boolean) = also { realEditor.putBoolean(key, value) }
            override fun remove(key: String?) = also { realEditor.remove(key) }
            override fun clear() = also { realEditor.clear() }

            override fun commit(): Boolean {
                commits++
                return realEditor.commit()
            }

            override fun apply() {
                applies++
                realEditor.apply()
            }
        }
    }
}
