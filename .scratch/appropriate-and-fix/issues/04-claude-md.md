# 04 — Write CLAUDE.md

Type: task
Status: open
Blocked by: 03

## Question

Every future session starts here, so it has to carry what cannot be re-derived from the code.

Adapt `../Loquace/CLAUDE.md`, trimmed to a single-module Android app. It must contain:

- **What the app is** and that it is a hard fork of `felixwiemuth/SimpleReminder` at `d34bf2f` — upstream is never merged.
- **The hard constraints**: GrapheneOS/AOSP compatibility and the no-Google rule; `minSdk 31` / `targetSdk 36`; no personal email anywhere.
- **The gate commands** — `scripts/check.sh` as the one-liner, and the individual Gradle invocations.
- **Working conventions**: plain language and no invented jargon; ticket sizing for one fresh agent at low context; short-lived worktrees, one ticket each, never directly on `develop`; never commit red gates; the commit trailer `Co-authored-by: Opus 5 <noreply@anthropic.com>`, with non-Anthropic models using their own vendor's no-reply address.
- **Domain vocabulary** — the reminder lifecycle (`SCHEDULED`, `NOTIFIED`, `DONE`), what nagging is, how reminder IDs are allocated and why they double as notification and `PendingIntent` IDs.

The domain vocabulary section is the part that cannot be copied from Loquace and is worth the most. It may be cheaper to write after ticket 09 has settled the state machine; if so, write everything else now and leave a marked gap rather than inventing terminology that ticket 09 then contradicts.

**Done when** a fresh agent reading only `CLAUDE.md` can run the gate and knows what it must not break.
