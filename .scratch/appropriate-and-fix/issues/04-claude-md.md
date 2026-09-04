# 04 — Write CLAUDE.md

Type: task
Status: resolved
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

## Resolution (2026-09-04)

`CLAUDE.md` written at the repo root, 159 lines: what Ding is and its hard
fork origin, the hard constraints, the gate as a one-liner plus each Gradle
invocation, the working conventions, the domain vocabulary, where tickets
live and how they route, and the environment facts (SDK, JDK, locale).

Ticket 09 had settled by the time this was written, so the domain vocabulary
section is real rather than a marked gap. It states the three states,
nagging, and reminder id allocation, then points at `CONTEXT.md` and
`docs/reminder-state-machine.md` instead of duplicating them.

Two departures from the ticket text. The branching convention is written as
"never directly on `main`", because the repo collapsed to a single trunk
after this ticket was charted. And `targetSdk` is written as "still 34,
ticket 17 raises it to 36" rather than as a met constraint, because CLAUDE.md
should not describe the tree as better than it is.

Left out on purpose: everything Loquace-specific (server, web, protocol,
E2EE, deploy, its planning pipeline and skill inventory). The rename to Ding
is described as pending, since ticket 05 has not run.
