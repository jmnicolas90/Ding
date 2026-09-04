# Appropriate the fork and fix the reviewed bugs

Label: wayfinder:map

## Destination

A repo that is unmistakably **Ding** — its own name, package, identity and docs, carrying no trace of upstream ownership beyond the GPL attribution it is legally obliged to keep — that builds and passes a **green, executable quality gate** both on this machine and in CI, and in which **every finding of the 02-09-2026 code review** has been either fixed or consciously ruled out of scope.

Reminder *features* are not part of this map. The missing functionality that motivated the fork is a separate effort, charted once the state model is trustworthy.

## Notes

**Domain.** Single-module Android app, Kotlin + some legacy Java. GPL-3.0-or-later hard fork of `felixwiemuth/SimpleReminder` at `d34bf2f` (2025-10-20). Source review: `simple-reminder-code-review-02-09-2026.md` at the repo root.

**This map carries execution.** Unlike the wayfinder default, tickets here deliver working, gate-green changes — not just decisions. The user's brief was to *do* the appropriation and *fix* the bugs. The `grilling` tickets are the exception: they resolve a decision, and their implementation is a separate ticket.

**Which skill works which ticket.** The routing is per-ticket, not per-map — do not re-enter `/wayfinder` for the whole map.

- **`Type: grilling`** (09, 16, 18) → **`/wayfinder`**. Genuine fog; the answer does not exist yet. This is what the skill is for.
- **`Type: task`** (everything else) → **`/implement`**. The decision was already made during charting, so these are agent-ready in the sense `/to-tickets` means it. `/implement` drives `/tdd` where there is a seam, then `/code-review`s the diff before committing. Wayfinder's collapse step has already happened for these and running it again adds ceremony, not thinking.
- Some `task` tickets are smaller than `/implement` — ticket 01 is two `git config` commands with nothing to test and no diff worth reviewing. Just do it.

Whichever route, do the bookkeeping by hand afterwards: set `Status: resolved` on the ticket and append one line to *Decisions so far* below. Two lines; not worth a skill.

**Skills every session should consult.** `/grilling` and `/domain-modeling` for any `grilling` ticket; `/tdd` for anything touching reminder state or storage.

**Hard constraints.**
- **GrapheneOS / AOSP compatible, always.** No Google, GMS, Play Services or Firebase dependency may ever enter the graph. The app is Google-free today and has run reliably on GrapheneOS for years — this is a property to *keep*, not to build.
- `minSdk 31`, `targetSdk 36`.
- **No personal email address anywhere** in the repo, in commit metadata, or in published artifacts.
- Never commit red gates.

**Working conventions** (adopted from `../Loquace/CLAUDE.md`, trimmed to a single-module app):
- Plain language, no invented jargon. Name things by what they do.
- A ticket must fit one fresh agent at low context. If it looks bigger, split it first.
- Code changes happen in short-lived worktrees, one ticket each, never directly on `develop`.
- Commit trailer names the model that actually wrote the code: `Co-authored-by: Opus 5 <noreply@anthropic.com>`. A non-Anthropic model uses its own name and vendor no-reply address.

### Settled while charting

These are the answers that fixed the destination. They are not tickets and were decided in conversation on 2026-09-04.

- **Scope** — bounded first milestone. Repo is ours, gate is green, every review finding fixed or ruled out. Features are a later map.
- **Name** — **Ding**. `applicationId` and `namespace` both become `app.ding`, matching the `app.loquace` house style. The GitHub repo and this working directory get renamed too.
- **Hard fork** — upstream is never merged again. Anything interesting gets cherry-picked by hand. This is what buys the freedom to raise `minSdk` and restructure at will.
- **Distribution** — public GitHub releases, built so F-Droid stays cheap later. Not the Play Store.
- **Bug-fix depth** — both the bounded fixes and the structural ones, but structural work makes its *decisions* here and its code in follow-on tickets. Room migration is out of scope.
- **CI** — GitHub Actions on the existing public GitHub remote. Free and unlimited for public repos, and the hosted runners ship the Android SDK, which avoids the ~200 lines of SDK bootstrap the self-hosted Forgejo runner needs. The workflow file stays portable if this changes.
- **Crash reporting** — ACRA is removed entirely. No replacement decided; see *Not yet specified*.
- **GrapheneOS verification** — a dependency guard in the gate now; instrumented tests on the existing `bench-pixel6-aosp` emulator (API 36, pure AOSP, Pixel 6 profile) as a later decision.

## Decisions so far

<!-- one line per closed ticket: gist + link -->

- [Stop publishing a personal email address in commit metadata](issues/01-git-identity-no-reply-address.md) — repo-local `user.email` set to `68194446+jmnicolas90@users.noreply.github.com`, real name kept; verified in an actual commit object. Done before the first commit, so no history rewrite. Commits are now safe to push publicly.
- [Raise minSdk to 31](issues/02-raise-minsdk-to-31.md) — `minSdkVersion 31`, ~190 lines of pre-31 branches deleted, including the notification priority/sound settings that Android 8 had already made inert. Lint is now clean, so the `NewApi` finding on `QuickTileService` is closed. `targetSdk` untouched (ticket 17).

## Not yet specified

- **What replaces ACRA, if anything.** For an app whose entire value is firing reliably *in the background*, a foreground crash reporter may be the wrong instrument. The real question is how a silent failure-to-fire becomes visible at all. Revisit once the state machine (ticket 09) exists and there is something meaningful to observe.
- **Release signing and publishing.** A keystore has to exist, live somewhere safe, and be reachable from a release process without leaking into the repo. Sharpens once the rename lands and there is a first release worth cutting.
- **Moving storage work off the main thread.** The review's performance finding. Depends entirely on what boundary ticket 09 draws — the fix could be `goAsync()`, a coroutine scope, or nothing if the boundary makes writes cheap.
- **Retention for completed reminders.** Done reminders accumulate forever, which is what makes the full-store rewrite grow without bound. Part cleanup, part product decision — needs the feature map's input.
- **Regenerating screenshots and store metadata** under the new name. Trivial mechanically, but nobody has decided what the app should *look* like yet.
- **Translations.** Upstream shipped English and a German title. Unclear whether the fork keeps, drops, or expands that.

## Out of scope

- **Room migration** — the review recommends moving reminders to a transactional row-based store. Correctness ordering says fix the transitions before changing the store, and this is a rewrite rather than a fix. Returns only as its own effort.
- **F-Droid and Play Store submission** — distribution is public GitHub releases. F-Droid is kept cheap but not pursued.
- **Staying mergeable with upstream** — hard fork. No design tax is paid to keep `git merge upstream/develop` viable.
- **Reminder feature work** — the missing functionality that motivated the fork. Templates, recurring reminders, per-reminder notification settings, media attachments, auto-complete, and the rest of upstream's abandoned roadmap. This is the *next* map, deliberately deferred until the state model can be trusted.
