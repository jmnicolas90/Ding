# 05 — Rename the app to Ding

Type: task
Status: resolved
Blocked by: 03

## Question

Mechanical, wide, and cheap exactly once — there is no divergence from upstream yet and the gate from ticket 03 will catch breakage. It gets more annoying with every commit that lands first.

`applicationId` and `namespace` both become `app.ding`, matching the `app.loquace` house style.

Surface, from the survey done while charting:

- `app/build.gradle`: `applicationId "felixwiemuth.simplereminder.dev"`, `namespace 'felixwiemuth.simplereminder'`, `archivesBaseName "SimpleReminder_" + versionName`
- The Java/Kotlin package `felixwiemuth.simplereminder.*` → `app.ding.*`, across ~60 source files and the `app/src/main/java/` directory tree. `app/src/test/java/` too.
- `app_name` in `values/strings.xml`, plus roughly a dozen user-visible strings that spell out "SimpleReminder" — welcome messages, battery-optimization advice, the ACRA prompt (which ticket 06 deletes outright; coordinate so the work is not done twice).
- `metadata/en-US/title.txt` and `metadata/de/title.txt`.
- The GitHub repository name, the `origin` remote URL, and this working directory.

Keep the GPL copyright headers exactly as they are. They are Felix Wiemuth's and stay — renaming the app does not change who wrote the code.

The `.dev` suffix on the old `applicationId` came from upstream's release-channel scheme in `app/build.gradle`. Decide whether Ding keeps that scheme or drops it for a plain `app.ding`; the version-code arithmetic above it is built around the channel digit, so dropping it means simplifying that too.

Note (added when the fork collapsed to a single trunk): half of that scheme is already gone. Upstream encoded the channel in the *branch* — `develop` built `.dev`, `main` built the production ID — and there is only one branch now. So the choice is no longer "keep upstream's scheme"; it is whether Ding needs more than one build identity at all, and if it does, that has to become a build type or product flavour rather than a branch.

**Done when** the gate is green, `grep -ri simplereminder` returns only GPL headers and deliberate historical references, and a debug APK installs as Ding.

## Resolution (2026-09-04)

**One build identity.** `applicationId` and `namespace` are both exactly `app.ding`, for every
build type. Upstream's release-channel scheme is gone: `releaseChannel` and
`versionCodeReleaseChannel` are deleted, and with them the `.dev` suffix. Ding does not need a
second identity — there is one branch and one app — and a per-build-type applicationId was
considered and rejected rather than left implicit. The version code's channel digit went with
it: the four numbers (major 0, minor 9, point 15, sub 0) are unchanged, but the fields above the
dead digit each moved down one decade, so the packing is now
`sub + 10^2*point + 10^4*minor + 10^7*major` and the code is `91500` where it used to be
`915100`. Lower than before, which would matter for an in-place upgrade and does not here: the
applicationId changed, so nothing upgrades from the old package. `versionName` dropped the
channel segment and is now `major.minor.point` plus the git description, e.g.
`0.9.15-9433a30-dirty`. `archivesBaseName` is `"Ding_" + versionName`.

**The package moved** from `felixwiemuth.simplereminder.*` to `app.ding.*`, with `git mv` on the
directory trees so history follows the files: 34 files under `app/src/main/java`, 2 under
`app/src/test/java`, no `androidTest` tree. Every fully qualified reference moved with them —
`AndroidManifest.xml` task affinities, `proguard-rules.pro`, `xml/preferences.xml` fragment
names, both `xml/shortcuts_*.xml` (target class *and* target package, which had the `.dev`
suffix baked in), and the `tools:context` in the two menu resources. Nothing in `.idea/`,
`.github/`, `scripts/` or `build.gradle` referenced the old package.

**User-visible name.** `app_name` and the ten strings in `values/strings.xml` that spelled the
app out — welcome messages, battery-optimization advice and summaries, the ACRA prompt — now say
Ding. There is no `values-de/`; upstream's German translation is only the store title. In
`raw/help.html`, the two launcher-icon labels and the "How can I support …" heading were
renamed; the upstream links around them were not. `metadata/en-US/title.txt`,
`metadata/de/title.txt` and the one occurrence in `metadata/en-US/full_description.txt` say
Ding.

**Verified**: the full gate is green, and `aapt dump badging` on the debug APK reports
`package: name='app.ding' versionCode='91500' versionName='0.9.15-9433a30-dirty'` and
`application-label:'Ding'`. No device or emulator was attached, so the install was not exercised.

**Deliberately still saying SimpleReminder**, each for a reason:

- Every GPL copyright header, `CONTRIBUTORS.md`, `LICENSE.md`, `LICENSES/`. Attribution, and a
  legal obligation.
- Upstream URLs — `README.md`, `raw/about.html`, the remaining links in `raw/help.html`,
  `.github/ISSUE_TEMPLATE/bug_report.md`, `.github/FUNDING.yml`, `CONTRIBUTING.md`. Ticket 06
  scrubs the contact points, ticket 07 rewrites the README and attribution.
- Historical release notes: `res/xml/changelog_master.xml` and `metadata/en-US/changelogs/*`.
  These record what upstream shipped under that name; rewriting them would falsify a changelog.
- The fork-origin sentence in `CLAUDE.md`, `CONTEXT.md`, the code review
  `simple-reminder-code-review-02-09-2026.md`, the tickets and map under `.scratch/`, and the
  agent definitions under `.claude/agents/`. All of these describe where the code came from.

`CLAUDE.md`'s "the rename is pending" paragraph is now a statement that it is done, with the
list of what deliberately still carries the old name.

Not done here, and not in this ticket's gift: the GitHub repository rename and the working
directory. The orchestrator renames the repository to `jmnicolas90/Ding` after this merges. No
tracked file referenced the `ForkedReminder` URL, so there was nothing to update; the git remote
was left alone.

## Review findings (2026-09-05)

- (medium) The debug install was never exercised because no device was attached — **verified by the orchestrator**: installed on the `bench-pixel6-aosp` emulator (API 36, pure AOSP), `pm list packages` shows `app.ding` with versionCode 91500, the launcher resolves to `app.ding/.ui.reminderslist.RemindersListActivity`, and both that activity and `AddReminderDialogActivity` start with status ok and no `AndroidRuntime` errors in logcat.
