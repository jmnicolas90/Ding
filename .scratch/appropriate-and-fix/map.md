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
- **One long-lived branch: `main`.** Code changes happen in short-lived worktrees on ticket branches, one ticket each, merged back with `--no-ff`. Releases are marked by tags, not by a branch.
- Commit trailer names the model that actually wrote the code: `Co-authored-by: Opus 5 <noreply@anthropic.com>`. A non-Anthropic model uses its own name and vendor no-reply address.

### Settled while charting

These are the answers that fixed the destination. They are not tickets and were decided in conversation on 2026-09-04.

- **Scope** — bounded first milestone. Repo is ours, gate is green, every review finding fixed or ruled out. Features are a later map.
- **Name** — **Ding**. `applicationId` and `namespace` both become `app.ding`, matching the `app.loquace` house style. The GitHub repo and this working directory get renamed too.
- **Hard fork** — upstream is never merged again. Anything interesting gets cherry-picked by hand. This is what buys the freedom to raise `minSdk` and restructure at will.
- **Single trunk** — upstream's git-flow `develop`/`main` split is retired. It cost a second merge per release and bought nothing for a single maintainer publishing via GitHub releases.
- **Distribution** — public GitHub releases, built so F-Droid stays cheap later. Not the Play Store.
- **Bug-fix depth** — both the bounded fixes and the structural ones, but structural work makes its *decisions* here and its code in follow-on tickets. Room migration is out of scope.
- **CI** — GitHub Actions on the existing public GitHub remote. Free and unlimited for public repos, and the hosted runners ship the Android SDK, which avoids the ~200 lines of SDK bootstrap the self-hosted Forgejo runner needs. The workflow file stays portable if this changes.
- **Crash reporting** — ACRA is removed entirely. No replacement decided; see *Not yet specified*.
- **GrapheneOS verification** — a dependency guard in the gate now; instrumented tests on the existing `bench-pixel6-aosp` emulator (API 36, pure AOSP, Pixel 6 profile) as a later decision.

## Decisions so far

<!-- one line per closed ticket: gist + link -->

- [Stop publishing a personal email address in commit metadata](issues/01-git-identity-no-reply-address.md) — repo-local `user.email` set to `68194446+jmnicolas90@users.noreply.github.com`, real name kept; verified in an actual commit object. Done before the first commit, so no history rewrite. Commits are now safe to push publicly.
- **Collapsed to a single trunk, `main`.** Upstream ran git-flow, and the two branches were not merely integration versus release: they carried different build identities (`develop` = `felixwiemuth.simplereminder.dev`, `main` = upstream's production `felixwiemuth.simplereminder`), with the release channel encoded in the branch. Merging took `main`'s side silently; the fork's own `.dev` identity was restored on top. The `v0.9.x` tags stay in the trunk's ancestry, which is why `main` was kept rather than `develop`.
- [Raise minSdk to 31](issues/02-raise-minsdk-to-31.md) — `minSdkVersion 31`, ~190 lines of pre-31 branches deleted, including the notification priority/sound settings that Android 8 had already made inert. Lint is now clean, so the `NewApi` finding on `QuickTileService` is closed. `targetSdk` untouched (ticket 17).
- [Build the executable quality gate](issues/03-executable-quality-gate.md) — `scripts/check.sh` (G0 preflight, lint, unit tests, Google guard, debug APK, release APK) and a matching GitHub Actions workflow, both green. The GrapheneOS constraint is now enforced by `checkNoGoogleDependencies`, which walks every variant runtime classpath and fails on `com.google.android.gms`, `com.google.firebase` or a `play-services` module — matched on group coordinates, so `com.google.android.material` passes. Lint fails on errors, not on the 23 remaining warnings.
- [Define the reminder state machine and its single transition boundary](issues/09-define-reminder-state-machine.md) — `docs/reminder-state-machine.md`: three states kept and tightened, eight commands with a full transition table, one pure `transition(stored, command, now)` function plus an effect runner that persists before it acts, the due time carried in every alarm as the stale token, a missing reminder handled as cleanup rather than a crash, nag chains ended by guard. Glossary in `CONTEXT.md`, ADR 0001. Recurrence kept open as an extension point, not implemented.
- [Write CLAUDE.md](issues/04-claude-md.md) — `CLAUDE.md` at the repo root, 159 lines, adapted from `../Loquace/CLAUDE.md` and trimmed to this single-module app: fork origin, the hard constraints, the six gate stages as a one-liner and as individual Gradle invocations, the working conventions, and a real domain vocabulary section (three states, nagging, even reminder ids doubling as notification and `PendingIntent` ids) pointing at `CONTEXT.md` and the state machine doc rather than duplicating them. Written after ticket 09, so no marked gap. Branching is stated as "never directly on `main`", and `targetSdk` as still 34 pending ticket 17, rather than as facts the tree does not yet support.
- [Rename the app to Ding](issues/05-rename-to-ding.md) — `applicationId` and `namespace` both `app.ding`, source package `felixwiemuth.simplereminder.*` → `app.ding.*` moved with `git mv`, `archivesBaseName` `"Ding_" + versionName`, `app_name` and ten user-visible strings plus both store titles now say Ding. **One build identity for every build type**: upstream's release-channel scheme is deleted, `.dev` suffix and all, and the version code's channel digit with it — the four numbers are unchanged but the fields moved down a decade, so the code is `91500` rather than `915100`. Verified by `aapt dump badging`: `package: name='app.ding'`, `application-label:'Ding'`. GPL headers, `CONTRIBUTORS.md`, upstream links (tickets 06 and 07), historical changelogs and the fork-origin prose keep the old name on purpose.
- [Remove ACRA and scrub every upstream contact point](issues/06-remove-acra-and-scrub-upstream.md) — the app now has **no crash reporter at all**: `ch.acra:acra-mail` and `ch.acra:acra-dialog`, the `initAcra` block in `Main.kt` (which took `attachBaseContext` with it, nothing else needed it) and the four `acra_*` strings are gone, and no address was substituted for the upstream author's. `buildFeatures.buildConfig` stays — `Prefs` and `RemindersListActivity` read `BuildConfig.VERSION_CODE`/`VERSION_NAME`, so it was never only ACRA's. `.github/FUNDING.yml` deleted; the issue templates keep only their own text; `README.md`, `about.html` and `help.html` lost the F-Droid badge, the five screenshots hotlinked from upstream's `raw.githubusercontent.com`, both sponsor links and every discussions link, with the rest retargeted at `https://github.com/jmnicolas90/Ding` — **GitHub issues are now the only contact channel**. Attribution is untouched: `LICENSE.md`, `LICENSES/`, `CONTRIBUTORS.md`, the GPL headers and the fork-origin sentences all stay, and so does the ACRA row in the third-party licence list and its generated copy in `app/src/main/assets/open_source_licenses.html`. Ticket 07 still owns the README prose and the screenshots.

## Not yet specified

- **What replaces ACRA, if anything.** For an app whose entire value is firing reliably *in the background*, a foreground crash reporter may be the wrong instrument. The real question is how a silent failure-to-fire becomes visible at all. Revisit once the state machine (ticket 09) exists and there is something meaningful to observe.
- **Release signing and publishing.** A keystore has to exist, live somewhere safe, and be reachable from a release process without leaking into the repo. Sharpens once the rename lands and there is a first release worth cutting.
- **Moving storage work off the main thread.** The review's performance finding. Ticket 09 put every mutation behind one runner (lock, read, transition, checked write, effects), so there is now exactly one place to make asynchronous. Whether it needs `goAsync()` in the receiver, a coroutine scope, or nothing at all is still open until ticket 10 shows how long the runner takes on a realistic store.
- **Retention for completed reminders.** Done reminders accumulate forever, which is what makes the full-store rewrite grow without bound. Part cleanup, part product decision — needs the feature map's input.
- **Regenerating screenshots and store metadata** under the new name. Trivial mechanically, but nobody has decided what the app should *look* like yet.
- **Resource shrinking on the shipping APK.** `shrinkResources` is set on the debug build type and not on release, so the APK users install is never resource-shrunk. Upstream turned minification on in debug to stay under the 64K dex limit and resource shrinking appears to have come along for the ride. Either release should shrink too, or debug should stop — but which way is a size-versus-build-time call nobody has made.
- **Translations.** Upstream shipped English and a German title. Unclear whether the fork keeps, drops, or expands that.

## Out of scope

- **Room migration** — the review recommends moving reminders to a transactional row-based store. Correctness ordering says fix the transitions before changing the store, and this is a rewrite rather than a fix. Returns only as its own effort.
- **F-Droid and Play Store submission** — distribution is public GitHub releases. F-Droid is kept cheap but not pursued.
- **Staying mergeable with upstream** — hard fork. No design tax is paid to keep `git merge upstream/develop` viable.
- **Reminder feature work** — the missing functionality that motivated the fork. Templates, recurring reminders, per-reminder notification settings, media attachments, auto-complete, and the rest of upstream's abandoned roadmap. This is the *next* map, deliberately deferred until the state model can be trusted.
