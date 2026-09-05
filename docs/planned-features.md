# Features Ding wants

This is the fork's own list, as against `upstream-planned-features.md`, which
reproduces upstream's roadmap verbatim and is input rather than intent. What is
written here is not a commitment either: it is raw material for the feature map
that comes after the current one, which is about appropriating the fork and
fixing the reviewed bugs, not about adding features.

An entry belongs here when the work is a new user-visible capability rather than
a bug. A behaviour the documentation claims and the code does not have is a bug,
and gets a ticket; the entry below exists because the claim was taken out of the
documentation instead of the behaviour being built now.

- **A mark-done button on the notification.** Today a reminder is marked done by
  swiping its notification away or from the reminders list, and clicking the
  notification opens the edit dialog. There is no button for marking it done, so
  a user who wants to keep the notification's text on screen while dealing with
  the reminder has no one-tap way to finish it, and swiping is the only gesture
  that works. The pieces are already there: `ReminderAction.MarkDone` exists,
  request code `id + 1` is reserved for it, and it goes through the command
  runner like every other state change — the swipe's delete intent is that same
  action. What is missing is `addAction` on the notification builder, a string
  for the button, and a test that pressing it marks the reminder done exactly
  once. Found by the 2026-09-05 review (user-facing paths, finding 5); ticket 25
  corrected the documentation.
