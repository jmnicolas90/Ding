# 19 — The three low-severity findings

Type: task
Status: open
Blocked by: —

## Question

Grouped because each is a few lines and none deserves its own session. All three are from the review's Low table.

**`HtmlDialogFragment.java:207-221`** — the raw-resource reader is closed only on the success path, so an `IOException` leaks it, and cancellation is never checked while reading. Use try-with-resources and propagate or log the error. The review also suggests replacing the deprecated fragment-retaining `AsyncTask` with lifecycle-aware loading — that is a larger change; do it only if it stays small, otherwise note it and stop.

**`values/strings.xml:54-59`** — singular and plural duration text are two ordinary strings selected with English-only `value == 1` logic. Replace with `<plurals>` resources and `getQuantityString`. Worth doing properly even though the app currently ships one language: it is the difference between "not translated yet" and "cannot be translated correctly".

**`.idea/kotlinc.xml:3-5`** — pins Kotlin `2.1.20` while the Gradle build uses `2.2.20`, so IDE analysis can diverge from the real build. Align it, or remove the project-specific JPS version and let the Gradle import define it. Removing is preferable; a pin that has to be kept in step by hand will drift again.

While in `.idea/`: decide whether these files belong in version control at all. Nine of them are tracked. Some (`codeStyles/`, `copyright/`) are genuinely shared project settings; others are local IDE state.

Independent of everything else. Good ticket for a session with little context.

**Done when** the gate is green and all three findings are closed or explicitly deferred with a reason.
