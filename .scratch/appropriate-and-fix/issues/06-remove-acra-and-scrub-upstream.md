# 06 — Remove ACRA and scrub every upstream contact point

Type: task
Status: resolved
Blocked by: 03

## Question

The repo currently routes its users, its money, and its crash reports to the upstream author.

**Remove ACRA entirely.** `Main.kt` `attachBaseContext` configures `mailSender { mailTo = "felixwiemuth@hotmail.de" }` plus a confirmation dialog. Nothing leaves the device without the user pressing send, so this is a correctness and ownership problem rather than a data leak — but it ships a stranger's address prefilled. Decided while charting: no crash reporting at all, and no personal address substituted in. Drop `ch.acra:acra-mail` and `ch.acra:acra-dialog`, the `initAcra` block, `buildConfigClass` if it is only there for ACRA, and the `acra_*` strings.

**Scrub the rest**:
- `.github/FUNDING.yml` → `github: [felixwiemuth]`. Delete the file; donations to the upstream author are not this fork's business.
- `.github/ISSUE_TEMPLATE/` — `bug_report.md`, `feature_request.md`, `config.yml`. The config points at upstream discussions. Retarget or delete.
- `README.md` — the F-Droid badge linking to `felixwiemuth.simplereminder`, five screenshots **hotlinked from upstream's `raw.githubusercontent.com`** (they will break or, worse, silently change), the feedback section pointing at upstream issues and discussions, and the sponsor link. Ticket 07 rewrites the prose; this ticket's job is making sure no upstream URL survives it.

**No personal email address anywhere** in the result — not the maintainer's personal address, not in a mailto, an issue template, or a README contact line. GitHub issues are the contact channel.

`CONTRIBUTORS.md` and the GPL headers are **not** scrubbed. Attribution is a licence obligation; see ticket 07.

**Done when** the gate is green, the app has no crash reporter, and no upstream URL or personal address remains outside licence and attribution files.

## Resolution (2026-09-05)

### Crash reporting removed, nothing put in its place

- `Main.kt`: the three `org.acra.*` imports and the whole `initAcra` block are
  gone. `attachBaseContext` went with them — it only existed to host that
  block, and its `super` call is what the framework does anyway.
- `app/build.gradle`: `def acraVersion`, `ch.acra:acra-mail` and
  `ch.acra:acra-dialog` deleted.
- `strings.xml`: `acra_title`, `acra_prompt`, `acra_comment_prompt` and the
  commented-out `acra_email` deleted.
- `buildFeatures { buildConfig true }` **stays**. It was not only ACRA's:
  `Prefs.java` reads `BuildConfig.VERSION_CODE` and
  `RemindersListActivity.kt` reads `BuildConfig.VERSION_NAME`. Only the
  `buildConfigClass = BuildConfig::class.java` line inside `initAcra` went.
- There was no ACRA proguard rule, manifest entry, settings entry or lint
  suppression to remove — checked, none existed.
- `./gradlew :app:dependencies --configuration debugRuntimeClasspath` no
  longer mentions ACRA.

The app now has no crash reporting at all, and no email address was
substituted for the upstream author's.

### Upstream contact points scrubbed

- `.github/FUNDING.yml` deleted.
- `.github/ISSUE_TEMPLATE/bug_report.md` says Ding instead of SimpleReminder.
  `feature_request.md` no longer sends people to "the discussions". Neither
  file carried a URL, so both were kept.
- `.github/ISSUE_TEMPLATE/config.yml` kept unchanged: it is one line,
  `blank_issues_enabled: false`, with no contact links and no upstream URL.
- `README.md`: F-Droid badge linking to `felixwiemuth.simplereminder` removed;
  the five screenshots hotlinked from upstream's `raw.githubusercontent.com`
  removed (ticket 07 regenerates them); the Donations section removed; the
  Feedback section rewritten to say GitHub issues are the only contact
  channel; the planned-features and issue links retargeted at
  `https://github.com/jmnicolas90/Ding`.
- `app/src/main/res/raw/about.html`: sponsor link and discussions link
  removed, contributors / star / issues / README links retargeted at
  `jmnicolas90/Ding`. The commented-out blocks were retargeted too — a
  comment is still a URL in the file.
- `app/src/main/res/raw/help.html`: "Donate on Github" bullet removed, "Ask a
  question" now points at Ding's issues.
- `CONTRIBUTING.md` and `metadata/` were checked and carried no upstream URL,
  so neither was touched.
- `CLAUDE.md`: the no-personal-email constraint now records that it holds,
  instead of naming itself broken pending this ticket.

### Deliberate remaining references

- `LICENSE.md`, `LICENSES/`, `CONTRIBUTORS.md`, the GPL copyright headers and
  the "fork of SimpleReminder by Felix Wiemuth" sentences: attribution the
  licence requires. Not scrubbed, per this ticket.
- The ACRA row in `LICENSE.md`'s third-party table, and therefore the same row
  in `app/src/main/assets/open_source_licenses.html`, which
  `generateOpenSourceLicensesFile.sh` renders from `LICENSE.md`. This ticket
  does not scrub `LICENSE.md`, and hand-editing only the rendered copy would
  put it out of step with its generator. Now that ACRA is not a dependency the
  row is stale and worth a follow-up, but it has to be done at the source.
- Historical changelog entries under `metadata/en-US/changelogs/` and in
  `changelog_master.xml` that name SimpleReminder or F-Droid. They record what
  upstream shipped; no URL, no address.
- `README.md` still says "Simple Reminder" in places. Ticket 07 owns the prose;
  this ticket only removed URLs.
