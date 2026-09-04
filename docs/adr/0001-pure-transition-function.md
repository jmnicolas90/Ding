# Reminder state changes go through one pure transition function

Reminder state was mutated from the application's startup sweep, the alarm
receiver, the storage object and the UI, each calling `AlarmManager` and the
notification manager directly, with a mutable `status` field anyone could
set. We decided that every change of reminder state passes through a single
pure function `transition(stored, command, now) -> outcome × effects` with no
Android imports, wrapped by one runner that locks, reads, writes, checks the
commit, and only then executes the effects. The smaller alternative, a
method-per-transition facade on the existing manager, would have kept every
guard entangled with Android calls and forced the cold-start, stale-alarm
and nag-chain tests onto Robolectric or a device. The pure function makes
those plain JVM tests with a fixed clock, which is the property this fork
needs before any feature work starts. The cost is a new module and a larger
diff in ticket 10. Details: `docs/reminder-state-machine.md`.

Considered and rejected alongside: a stored generation counter as the
stale-alarm token. The due time already stored serves, and the only case a
counter adds (rescheduled to the identical millisecond) is not observable.
