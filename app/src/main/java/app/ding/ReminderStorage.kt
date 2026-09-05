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

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import app.ding.data.Reminder
import app.ding.state.KNOWN_STORED_REMINDERS_FORMAT_VERSION
import app.ding.state.QuarantinedReminders
import app.ding.state.ReminderStore
import app.ding.state.StoreReading
import app.ding.state.StoredReminders
import app.ding.state.quarantineToKeep
import app.ding.state.readQuarantine
import app.ding.state.readStore
import app.ding.state.writeWithRollback
import app.ding.ui.reminderslist.RemindersListFragment

/**
 * Handles the persistent reminder storage: the reminder list and the id counter,
 * both JSON in shared preferences.
 *
 * Reading is public, for the list and the edit dialog. Writing is not: it happens
 * only through [storeIn], which [ReminderManager] hands to the one command runner,
 * so that every change to a reminder passes the transition function first.
 *
 * Reading never throws and never writes. The decoding is `readStore` in
 * `app.ding.state`, and a stored value it cannot read is reported rather than
 * repaired: the read answers with an empty list, and the runner moves the value to
 * keys of its own — see [setAsideUnreadable] — under its lock, as the first step of
 * the next command. The first command of a process is the startup Reconcile, so that
 * happens before anything writes to the normal keys, and the value the user might
 * still want is kept intact and offered to them.
 */
object ReminderStorage {
    class ReminderNotFoundException(message: String?) : RuntimeException(message)

    /**
     * The store the app runs on. Its [ReminderStore.write] reports whether the write
     * committed, so that a failed write can stop the runner before it acts, and puts
     * the previous values back when it did not, so a failed write is invisible to
     * every later read as well.
     */
    internal fun storeIn(context: Context): ReminderStore = SharedPreferencesStore(context)

    /**
     * The three preference values one store write puts in place, as they are stored.
     * A null is a key that is not there, so that a rollback can put an absent key back.
     */
    private data class StatePrefValues(
        val remindersJson: String?,
        val nextId: Int?,
        val formatVersion: Int?
    )

    /** Everything a set-aside touches: the normal keys and the keys the value is kept under. */
    private data class SetAsideValues(
        val state: StatePrefValues,
        val quarantined: QuarantinedReminders?
    )

    /**
     * What the normal keys hold once a value has been set aside: no reminders, this
     * build's format version, and the id counter back at 0. The counter belongs to
     * reminders nobody can read, so it goes with them; the ids that mattered are in
     * the raw value that is kept.
     */
    private val EMPTIED_STATE = StatePrefValues(
        remindersJson = EMPTY_REMINDERS_JSON,
        nextId = 0,
        formatVersion = KNOWN_STORED_REMINDERS_FORMAT_VERSION
    )

    private class SharedPreferencesStore(private val context: Context) : ReminderStore {
        /**
         * Reads the store, and never throws: this runs from `Main.onCreate` on every
         * process start, so an exception here is an app that cannot be launched at all.
         * Each of the three values is read through a function that is allowed to throw
         * [ClassCastException] — which is what shared preferences do when the stored
         * value is of another type — and `readStore` decides what each failure means.
         *
         * It writes nothing. A value that cannot be read comes back as an empty store
         * and [StoreReading.unreadable], for the runner to set aside under its lock.
         */
        override fun read(): StoreReading {
            val prefs = Prefs.getStatePrefs(context)
            val reading = readStore(
                readFormatVersion = { numberAt(prefs, Prefs.PREF_STATE_REMINDERS_FORMAT_VERSION) },
                readRawJson = { prefs.getString(Prefs.PREF_STATE_CURRENT_REMINDERS, null) },
                readNextId = { numberAt(prefs, Prefs.PREF_STATE_NEXTID) }
            )
            if (reading.unreadable != null) {
                // Logged here because this is where the reason is known; the value
                // itself is kept by the set-aside, which the runner does next.
                Log.e(TAG, "The stored reminders could not be read: ${reading.unreadable}")
            }
            if (reading.counterRepaired) {
                Log.w(
                    TAG,
                    "The stored id counter could not be used; it is ${reading.stored.nextId} " +
                        "from now on, which no stored reminder has. The next write keeps it."
                )
            }
            return reading
        }

        /**
         * Sets the unreadable value aside and empties the normal keys, in one commit,
         * with the same rollback as an ordinary write: a commit that does not go
         * through leaves the store exactly as unreadable as it was, so the command
         * this was the first step of fails rather than carrying on as if the value had
         * been kept.
         */
        override fun setAsideUnreadable(): StoredReminders? {
            val prefs = Prefs.getStatePrefs(context)
            val previous = readSetAsideValues(prefs)
            val existing = previous.quarantined
            val candidate = QuarantinedReminders(
                // A value of the wrong type could not be read as text, so it is kept as
                // whatever it prints as rather than thrown away.
                raw = storedText(prefs, Prefs.PREF_STATE_CURRENT_REMINDERS),
                formatVersion =
                    numberOrNothingAt(prefs, Prefs.PREF_STATE_REMINDERS_FORMAT_VERSION),
                quarantinedAt = System.currentTimeMillis()
            )
            val keep = quarantineToKeep(existing = existing, candidate = candidate)
            if (existing != null) {
                Log.w(
                    TAG,
                    "A second unreadable stored value was dropped; the one set aside at " +
                        "${existing.quarantinedAt} is kept."
                )
            }
            val result = writeWithRollback(
                previous = previous,
                next = SetAsideValues(state = EMPTIED_STATE, quarantined = keep),
                commit = ::commitSetAsideValues
            )
            Log.e(
                TAG,
                "The stored reminders could not be read; set aside for the user to keep " +
                    "or discard, committed=${result.committed}"
            )
            // The store is empty from here on, and its counter starts again from 0.
            return if (result.committed) StoredReminders(emptyList(), nextId = 0) else null
        }

        /**
         * Writes the reminder list and the id counter in one commit, and returns
         * whether that commit went through.
         *
         * `commit()` rather than `apply()` on purpose, which is what the lint
         * suppression is for: `apply()` writes in the background and returns nothing,
         * so a durable write that fails is invisible. Here the answer is the whole
         * point — a false makes the runner stop before it announces the change or
         * sets an alarm for a reminder that is not stored.
         *
         * A false from `commit()` is not the end of the work, though, because
         * shared preferences do not undo anything on it. `commit()` puts the new
         * values in the in-memory map first and only then attempts the durable
         * write, and a durable write that fails leaves that map alone. Without the
         * rollback below, a failed write would still have handed the new reminder
         * list and the new id counter to every later read in this process: a failed
         * Add would keep its phantom reminder and its advanced id, and a failed
         * Deliver would leave the reminder looking `NOTIFIED` so the alarm that has
         * already been consumed and an in-process Reconcile would both skip it.
         * That is exactly the state the runner's callers are told never happened.
         *
         * So on failure the previous values go back through a second `commit()`.
         * It restores the in-memory map whatever its own durable write does, for
         * the same reason the first one polluted it. On disk there is nothing to
         * repair beyond that: a shared-preferences commit is atomic — the file is
         * swapped with its backup rather than patched — so the file holds either
         * the old content or the new one in full, and the rollback brings both
         * halves back in line with the old one. If the rollback's own commit fails
         * the write is still reported as failed; both results are logged, because a
         * store that cannot even write back what it already held is worth seeing.
         */
        @SuppressLint("ApplySharedPref")
        override fun write(stored: StoredReminders): Boolean {
            val result = writeWithRollback(
                previous = readValues(),
                next = StatePrefValues(
                    remindersJson = Reminder.toJson(stored.reminders),
                    nextId = stored.nextId, // Reminder IDs may only be even
                    formatVersion = KNOWN_STORED_REMINDERS_FORMAT_VERSION
                ),
                commit = ::commitValues
            )
            if (!result.committed) {
                Log.e(
                    TAG,
                    "The store did not commit; put the previous reminders back: " +
                        "rollback committed=${result.rollbackCommitted}"
                )
            }
            return result.committed
        }

        /**
         * The values [commitValues] writes, read straight out of the preferences so
         * that a rollback restores them exactly rather than a re-serialisation of
         * them — a corrupt counter included, since a rollback puts back what was
         * there and the next read repairs it again. These three keys are the whole of
         * what that editor touches. A reminder list of another type comes back as its
         * text, which is the form it would be kept in anyway; a number of another type
         * comes back as nothing, since it is recomputed on the next read regardless.
         */
        private fun readValues(): StatePrefValues {
            val prefs = Prefs.getStatePrefs(context)
            return StatePrefValues(
                remindersJson = storedText(prefs, Prefs.PREF_STATE_CURRENT_REMINDERS),
                nextId = numberOrNothingAt(prefs, Prefs.PREF_STATE_NEXTID),
                formatVersion =
                    numberOrNothingAt(prefs, Prefs.PREF_STATE_REMINDERS_FORMAT_VERSION)
            )
        }

        /**
         * The version is written with the reminders, in the same commit, so that the
         * store always says which format it is in. Nothing writes it on a read, and a
         * build that meets a store with no version at all reads it as its own.
         */
        @SuppressLint("ApplySharedPref")
        private fun commitValues(values: StatePrefValues): Boolean =
            Prefs.getStatePrefs(context).edit().putState(values).commit()

        /** The three quarantine keys and the three normal ones, as they stand now. */
        private fun readSetAsideValues(prefs: SharedPreferences): SetAsideValues =
            SetAsideValues(state = readValues(), quarantined = quarantineIn(prefs))

        @SuppressLint("ApplySharedPref")
        private fun commitSetAsideValues(values: SetAsideValues): Boolean {
            val quarantined = values.quarantined
            return Prefs.getStatePrefs(context).edit()
                .putState(values.state)
                .putStringOrRemove(Prefs.PREF_STATE_REMINDERS_UNREADABLE, quarantined?.raw)
                .putIntOrRemove(
                    Prefs.PREF_STATE_REMINDERS_UNREADABLE_FORMAT_VERSION,
                    quarantined?.formatVersion
                )
                .putLongOrRemove(
                    Prefs.PREF_STATE_REMINDERS_UNREADABLE_AT,
                    quarantined?.quarantinedAt
                )
                .commit()
        }

        override fun announceChange() {
            Prefs.setRemindersUpdated(true, context)
            LocalBroadcastManager.getInstance(context)
                .sendBroadcast(RemindersListFragment.getRemindersUpdatedBroadcastIntent())
        }
    }

    /**
     * Returns an immutable list of the saved reminders, or an empty list when the
     * stored value cannot be read. Reading goes through the store so that there is one
     * decoding of the reminders, and it changes nothing at all: the value that could
     * not be read is set aside by the runner, under its lock, and a read that wrote
     * would be a write outside that lock — one that lands on the normal keys while a
     * command is in flight and takes the reminder it just added with it.
     */
    fun getReminders(context: Context): List<Reminder> =
        SharedPreferencesStore(context).read().stored.reminders

    /** What is stored for no reminders at all, and what a value set aside leaves behind. */
    private const val EMPTY_REMINDERS_JSON = "[]"

    private const val TAG = "ReminderStorage"

    /**
     * The value at [key] as text, or null when there is nothing there. A value of
     * another type comes back as whatever it prints as rather than as nothing: the
     * text is the part worth keeping.
     */
    private fun storedText(prefs: SharedPreferences, key: String): String? =
        prefs.all[key]?.toString()

    /**
     * The number at [key] the way shared preferences give it: null when the key is not
     * there, and [ClassCastException] when it holds a value of another type. That is
     * not caught here on purpose — the reading functions in `app.ding.state` are the
     * ones that decide what a value of the wrong type means.
     */
    private fun numberAt(prefs: SharedPreferences, key: String): Int? =
        if (prefs.contains(key)) prefs.getInt(key, 0) else null

    /**
     * The number at [key], or null when it is missing or is not a number. The snapshot
     * form, for the values a rollback puts back and the metadata kept with a value set
     * aside: both of those run while the store is already known to be damaged, so
     * neither may throw.
     */
    private fun numberOrNothingAt(prefs: SharedPreferences, key: String): Int? =
        prefs.all[key] as? Int

    private fun SharedPreferences.Editor.putState(values: StatePrefValues) =
        putStringOrRemove(Prefs.PREF_STATE_CURRENT_REMINDERS, values.remindersJson)
            .putIntOrRemove(Prefs.PREF_STATE_NEXTID, values.nextId)
            .putIntOrRemove(Prefs.PREF_STATE_REMINDERS_FORMAT_VERSION, values.formatVersion)

    private fun SharedPreferences.Editor.putStringOrRemove(key: String, value: String?) =
        if (value == null) remove(key) else putString(key, value)

    private fun SharedPreferences.Editor.putIntOrRemove(key: String, value: Int?) =
        if (value == null) remove(key) else putInt(key, value)

    private fun SharedPreferences.Editor.putLongOrRemove(key: String, value: Long?) =
        if (value == null) remove(key) else putLong(key, value)

    /**
     * The stored value that could not be read, or null when there is none. The
     * reminders list activity offers it to the user to share or to discard; nothing
     * else reads it, and nothing at all overwrites it.
     *
     * Every field is read defensively, because this runs while a value is being set
     * aside and again every time the list opens: a crash here is a crash at startup or
     * on the one screen that hands the user their data back. The raw text is what
     * matters, so metadata that cannot be read is unknown rather than fatal.
     */
    fun getQuarantinedReminders(context: Context): QuarantinedReminders? =
        quarantineIn(Prefs.getStatePrefs(context))

    private fun quarantineIn(prefs: SharedPreferences): QuarantinedReminders? = readQuarantine(
        // The time is asked for as well, so that a value whose own text cannot be read
        // is still reported instead of looking like nothing ever happened.
        isSetAside = {
            prefs.contains(Prefs.PREF_STATE_REMINDERS_UNREADABLE) ||
                prefs.contains(Prefs.PREF_STATE_REMINDERS_UNREADABLE_AT)
        },
        readRaw = { prefs.getString(Prefs.PREF_STATE_REMINDERS_UNREADABLE, null) },
        readFormatVersion = {
            if (prefs.contains(Prefs.PREF_STATE_REMINDERS_UNREADABLE_FORMAT_VERSION)) {
                prefs.getInt(Prefs.PREF_STATE_REMINDERS_UNREADABLE_FORMAT_VERSION, 0)
            } else {
                null
            }
        },
        readQuarantinedAt = {
            if (prefs.contains(Prefs.PREF_STATE_REMINDERS_UNREADABLE_AT)) {
                prefs.getLong(Prefs.PREF_STATE_REMINDERS_UNREADABLE_AT, 0)
            } else {
                null
            }
        }
    )

    /**
     * Deletes the stored value that could not be read. Only the user asks for this, and
     * only after confirming it: it is the one thing in the app that throws reminders
     * away for good.
     */
    fun discardQuarantinedReminders(context: Context) {
        Prefs.getStatePrefs(context).edit()
            .remove(Prefs.PREF_STATE_REMINDERS_UNREADABLE)
            .remove(Prefs.PREF_STATE_REMINDERS_UNREADABLE_FORMAT_VERSION)
            .remove(Prefs.PREF_STATE_REMINDERS_UNREADABLE_AT)
            .apply()
    }

    /**
     * Get the reminder with the specified ID.
     *
     * The edit dialog is the one place that reports a missing reminder to the user;
     * everywhere else a missing reminder is cleanup, decided by the transition
     * function, and never an exception.
     *
     * @param context
     * @param id
     * @return
     * @throws ReminderNotFoundException if no reminder with the given ID exists
     */
    @JvmStatic
    @Throws(ReminderNotFoundException::class)
    fun getReminder(context: Context, id: Int): Reminder =
        getReminders(context).find { r -> r.id == id }
            ?: throw ReminderNotFoundException("Reminder with id $id does not exist.")
}
