# Ding

A reminder app whose whole value is firing reliably in the background. This
glossary is the language for reminder state; the state machine itself is in
`docs/reminder-state-machine.md`.

## Language

**Reminder**:
One thing the user wants to be told about at a due time. Identified by an
even integer id.

**Due time**:
The instant a reminder is meant to be delivered. Also the identity of a
delivery: an alarm carrying a different due time than the stored one is
stale.
_Avoid_: date, time, when

**Scheduled**:
The state of a reminder not yet delivered. It has an alarm pending.
_Avoid_: pending, upcoming, overdue

**Notified**:
The state of a reminder that has been delivered and not yet dealt with.
_Avoid_: shown, fired, active

**Done**:
The state of a finished reminder. A resting state, not terminal: it can be
rescheduled.
_Avoid_: dismissed, completed, closed

**Deliver**:
The transition that shows the notification and moves a reminder from
Scheduled to Notified.
_Avoid_: show, notify, send, fire

**Nag**:
A repeat delivery of a Notified reminder at a fixed interval counted from
its due time, until it is dealt with.
_Avoid_: repeat, remind again

**Mark done**:
The transition to Done, by swiping the notification away or from the reminders
list. The notification carries no button for it: clicking it opens the reminder
for editing.
_Avoid_: dismiss, complete, acknowledge

**Reschedule**:
Giving a reminder a new due time. Lands in Scheduled from any state.
_Avoid_: update, save, re-arm

**Edit**:
Changing a reminder's text or nag settings without touching its due time.
Never changes state.
_Avoid_: update, save

**Delete**:
Removing a reminder from the store. Cancels its alarm and notification.
_Avoid_: remove, cancel

**Reconcile**:
The sweep that brings alarms and notifications back in line with the store.
Three things ask for it: every process start; the user granting the
notification permission, which is what makes a delivery that was suppressed
while it was denied appear; and an alarm arriving with a payload the app
cannot read.
_Avoid_: reschedule all, reshow, restore

**First unlock**:
The first time the user unlocks the device after a reboot. Before it the
store is unreadable and the app cannot run, so nothing fires; Reconcile runs
at first unlock at the earliest.
_Avoid_: boot, logging in

**Stale alarm**:
An alarm whose expected due time no longer matches the store, or whose
reminder is not in a state that can accept it. Ignored, never an error.

**Alarm slot**:
The single alarm a reminder owns at any time. Holds either a Deliver or a
Nag alarm.
