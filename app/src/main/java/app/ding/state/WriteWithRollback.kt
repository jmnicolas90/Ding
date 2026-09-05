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
package app.ding.state

/**
 * How a [ReminderStore] keeps its promise that a failed write leaves nothing
 * behind, on top of a commit that does not keep it by itself.
 *
 * Shared preferences make the new values visible to every later read before they
 * try the durable write, and a durable write that fails does not take them back.
 * So the only way to give the store's caller the snapshot it had before is to
 * write the previous values again. This file has no Android imports, so the
 * decision is testable on a plain JVM.
 */

/** What a write attempt left in the store. */
data class WriteWithRollbackResult<T>(
    /** True when the store holds the new values, which is the only success. */
    val committed: Boolean,
    /** What every later read now returns: the new values, or the previous ones. */
    val stored: T,
    /**
     * Whether the rollback's own commit reported success, or null when no rollback
     * was needed. A false here is worth logging but does not change the answer: the
     * write failed either way.
     */
    val rollbackCommitted: Boolean?
)

/**
 * Commit [next]; if that reports failure, commit [previous] again to put the store
 * back where it was.
 *
 * The rollback is worth doing even when its own commit fails, because the two
 * halves of a shared-preferences commit fail independently. The visible values are
 * set before the durable write is attempted and are not tied to its result, so
 * committing [previous] restores what later reads see whatever the disk does. The
 * durable half is atomic — the file is swapped with its backup, never patched in
 * place — so on disk the store holds either [previous] or [next] in full, and the
 * rollback moves it back towards [previous]. What cannot happen either way is the
 * one thing that matters: a half-written store, or a store whose visible values
 * disagree with the answer this function gave its caller.
 *
 * @param commit writes the given values, and reports whether the durable write
 *   went through. It is expected to make them visible either way.
 */
fun <T> writeWithRollback(
    previous: T,
    next: T,
    commit: (T) -> Boolean
): WriteWithRollbackResult<T> {
    if (commit(next)) {
        return WriteWithRollbackResult(committed = true, stored = next, rollbackCommitted = null)
    }
    val rollbackCommitted = commit(previous)
    return WriteWithRollbackResult(
        committed = false,
        stored = previous,
        rollbackCommitted = rollbackCommitted
    )
}
