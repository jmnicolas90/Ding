# Global Codex review, 2026-09-05 — User-facing paths and documentation truth

Adversarial review by Codex (GPT-5.4) of the whole diff from the fork commit `d34bf2f` to the tip of `main` on 2026-09-05, before the history rewrite of the same day. Job `review-mto40zn5-hsxpti`. One of three reviews run in parallel, each with its own axis; the other two are in this directory.

**Verdict:** needs-attention

**Summary:** No-ship: primary dialog recovery paths are broken, lifecycle restoration can lose drafts, quarantine recovery becomes escapable, and user documentation can cause reminders to be scheduled at the wrong time. Review ledger: cold delivery fixed; persistence partial; edit semantics partial; Direct Boot open #16; API lint fixed #2; nag overflow fixed #14; corrupt-store recovery partial; full-store main-thread I/O untracked; text scaling fixed #15; licence generation fixed #8; test gap open #18; three low findings open #19.

## Findings

### 1. [high] The save button cannot retry after any refused or failed submission

File: `app/src/main/java/app/ding/util/OneTimeClickListener.kt`

OneTimeClickListener permanently sets `clicked` before invoking `onDone`. Add and edit now deliberately remain open after a past-due refusal or PersistenceFailed, but correcting the input and pressing the visible Add/OK button does nothing. This directly falsifies map.md:72 and ticket 12's claim that retry is "one press"; only the separate keyboard action bypasses this listener.

Recommendation: Use a submission guard that is reset whenever `onDone` returns without completing the activity, and route both the button and IME action through that same guard.

### 2. [medium] Abandoning a failed dialog reports RESULT_OK

File: `app/src/main/java/app/ding/ui/ReminderDialogActivity.kt`

The back callback invokes `completeActivity()`, which always sets RESULT_OK. After PersistenceFailed—or even without submitting—pressing Back therefore reports a successful add/edit despite no committed change, contradicting both dialog class contracts and potentially misleading any result-based caller.

Recommendation: Set RESULT_OK only after TransitionOutcome.Updated; cancellation/back should call `finish()` with RESULT_CANCELED.

### 3. [medium] Configuration restoration discards or mixes the dialog draft

File: `app/src/main/java/app/ding/ui/EditReminderDialogActivity.kt`

The edit activity ignores `savedInstanceState` and unconditionally reloads the stored reminder on every creation. The base activity likewise reconstructs selectedDate, selection mode and nag interval rather than saving its draft model. Rotation or process restoration can therefore lose an unsaved custom nag interval/date or restore widget text that no longer matches the internal due time used on submission.

Recommendation: Persist the complete dialog draft and original reminder ID/due time with SavedStateHandle or onSaveInstanceState, and initialize from storage only on the first creation. Add rotation and process-recreation tests.

### 4. [medium] Sharing quarantined data dismisses the mandatory warning for the rest of the activity

File: `app/src/main/java/app/ding/ui/reminderslist/RemindersListActivity.kt`

AlertDialog buttons dismiss automatically, so Share closes the warning before launching the chooser. The quarantine check runs only during `onCreate`; cancelling or completing the chooser returns to an interactive empty list without re-showing the warning, even though the raw data remains quarantined. This falsifies ticket 13's statement that the message "comes back until the data is discarded" and lets users add data while unaware the apparent empty list is a recovery state.

Recommendation: Keep explicit ownership of the dialog and re-show it after the share activity returns while quarantine remains, using an activity-result launcher and lifecycle-safe DialogFragment.

### 5. [medium] The documented notification action button does not exist

File: `CLAUDE.md`

CLAUDE.md says: "Mark done ... whether by swiping the notification away, its action button, or the list." CONTEXT.md and the state-machine command table repeat this. The notification builder only installs a content intent for editing and a delete intent for dismissal; it never adds a visible Mark done action. Users therefore cannot follow one of the documented completion paths.

Recommendation: Either add a visible Mark done action wired once through ReminderCommandRunner and test it, or remove the action-button claim from CLAUDE.md, CONTEXT.md and docs/reminder-state-machine.md.

### 6. [medium] Clock height accepts values that can break the reminder dialog and still crashes on corrupt storage

File: `app/src/main/java/app/ding/ui/UISettingsFragment.kt`

The settings editor accepts every positive Int, including values that make the clock billions of dp high. The runtime getter is also a bare Integer.parseInt, so an unusable or wrong-typed stored preference crashes add/edit dialog creation instead of falling back, logging and repairing. This is explicitly acknowledged as unfixed in ticket 15 but has no follow-up ticket.

Recommendation: Define a defensible dp range in one pure setting parser shared by editor and reader; make the reader repair invalid or wrong-typed values once, matching the nag and text-size settings.

### 7. [medium] The picker ignores the device's 12/24-hour preference

File: `app/src/main/java/app/ding/ui/ReminderDialogActivity.kt`

The dialog unconditionally forces 24-hour mode. A user accustomed to AM/PM can select the wrong half-day, while the AM/PM-label sizing code is unreachable. Ticket 15 calls this "a separate finding," but no open ticket tracks it.

Recommendation: Initialize the picker with `DateFormat.is24HourFormat(this)` and ensure text parsing/help reflects the active mode, or explicitly document and validate a deliberate 24-hour-only policy.

### 8. [high] README's absolute-time example is interpreted as a relative delay

File: `README.md`

README says: "pick a time — absolute (`12:30`) or relative (`+1:30`)". In code, `.` is the absolute separator and `:` is the relative separator; `12:30` therefore means 12 hours 30 minutes from now, not 12:30 on the clock. The same false syntax appears in help.html. Following the primary usage instructions can schedule a reminder many hours late.

Recommendation: Change the absolute example and help text to `12.30`, or change the parser so colon syntax has the documented absolute meaning and require `+` for relative input.

### 9. [medium] The in-app test instruction now guarantees a refusal

File: `app/src/main/res/raw/help.html`

Help says: "add a reminder without changing the date or time. The reminder will be shown immediately." The dialog initializes to the current minute, while Add now refuses every due time less than or equal to the current instant. Following this instruction produces the "must be in the future" toast and an inert button because of the one-shot listener.

Recommendation: Tell users to select a future minute for testing and verify the help path against the actual Add guard.

### 10. [medium] The review ledger falsely claims every upstream finding is tracked or closed

File: `CLAUDE.md`

CLAUDE.md claims: "every one of its findings is tracked as a ticket, fixed or consciously ruled out of scope." The upstream medium finding about full-store synchronous JSON I/O remains live; map.md:84-85 calls off-main-thread work and retention "not yet specified," but no open ticket owns either. Ruling Room out at map.md:92 does not rule out the finding or its non-Room mitigations.

Recommendation: Create an open ticket covering receiver/main-thread I/O and unbounded completed-reminder retention, or explicitly rule out the complete risk with measured evidence and correct the ledger claim.

## Briefing given to the reviewer

```
Global adversarial review of everything Ding changed since it forked from felixwiemuth/SimpleReminder at d34bf2f (2025-10-20). Axis 3 of 3: the user-facing paths and whether the documentation tells the truth about the code.

Spec: docs/reminder-state-machine.md, CONTEXT.md, CLAUDE.md, .scratch/appropriate-and-fix/map.md (Decisions so far), the tickets under .scratch/appropriate-and-fix/issues/, and simple-reminder-code-review-02-09-2026.md, the review of the upstream code that every ticket answers.

Scope: app/src/main/java/app/ding/ui/** (dialogs, list, settings fragments, view holders, actions), Prefs.java, data/TimePickerTextSizeSetting.kt, data/NagIntervalSetting.kt, state/DialogDueTime.kt, res/values/strings.xml, res/xml/preferences*, res/layout/*, res/raw/*.html and app/src/main/assets/*, README.md, CONTEXT.md, CLAUDE.md, docs/**, .scratch/appropriate-and-fix/**.

Decisions fixed (do not re-litigate): the due time decides edit versus reschedule in every state; a past due time is refused with the existing toast; PersistenceFailed keeps the dialog open with input intact and shows one message; an unreadable store shows a dialog that cannot be dismissed by accident with Share raw data and Discard behind a second confirmation; nag interval 1..1440 minutes, time display size 8..96 sp with default 30; unusable stored preferences fall back, log and are repaired once.

Look hard for:
- Dialog lifecycle: rotation or configuration change while the add or edit dialog is open, process death and restore, the dialog result after PersistenceFailed, double submission despite OneTimeClickListener, an edit dialog opened for a reminder that was marked done or deleted meanwhile, and the notification tap opening the dialog for a reminder that no longer exists.
- The unreadable-store dialog: re-showing while the share chooser is open, Discard without the second confirmation, what happens if the user backgrounds the app mid-dialog, what the share intent carries and to whom, and the list content while the dialog is up.
- Settings: the validation the editor applies versus what the read accepts, the repair write racing a user edit, the toast text versus the actual bounds, the preference titles and summaries versus the units used, the 24-hour view forced in the picker and its AM/PM labels, and the still-unbounded clock height preference.
- List and notification actions: mark done from the list, swipe, the notification button, and the notification dismissal each going through the runner exactly once, list refresh after a broadcast, and a stale row after a failed write.
- Strings: every new user-visible string in English and whether German or other locales now show mixed languages; strings that still say SimpleReminder where the decision says Ding, and the reverse.
- Documentation truth: take each claim in CLAUDE.md's domain vocabulary section and in map.md's Decisions so far and check it against the code as it is now, not as the ticket described it; list every claim that is false, stale or unverifiable. Check every ticket marked Status: resolved actually delivered its definition of done.
- The review ledger: for every finding in simple-reminder-code-review-02-09-2026.md, say whether it is fixed in code, tracked by an open ticket, explicitly ruled out in map.md, or none of those. None of those is a finding.
- Help and about pages under res/raw and the README describing behaviour that no longer exists or exists differently.

Non-goals: the transition function, runner, alarms and notifications internals (review axis 1); build, gate, guards and attribution (axis 2); visual design; translations policy; feature work.

Report each finding with file and line, the user-visible consequence, and its severity. For documentation findings quote the false sentence and say what is true.
```
