# 06 — Remove ACRA and scrub every upstream contact point

Type: task
Status: resolved
Blocked by: 03

## Question

The repo currently routes its users, its money, and its crash reports to the upstream author.

**Remove ACRA entirely.** `Main.kt` `attachBaseContext` configures `mailSender { mailTo = ... }` with the upstream author's personal address, plus a confirmation dialog. Nothing leaves the device without the user pressing send, so this is a correctness and ownership problem rather than a data leak — but it ships a stranger's address prefilled. Decided while charting: no crash reporting at all, and no personal address substituted in. Drop `ch.acra:acra-mail` and `ch.acra:acra-dialog`, the `initAcra` block, `buildConfigClass` if it is only there for ACRA, and the `acra_*` strings.

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

## Review findings (2026-09-05)

- **Personal addresses survived in tracked files under `.scratch/`, in a public
  repo** (high) — **fixed.** This ticket and ticket 01 quoted the maintainer's
  and the upstream author's addresses literally while arguing about them; both
  now describe the address instead of spelling it out. No-reply addresses stay.
- **The no-personal-email constraint was documented but not enforced** (high,
  follow-on of the first) — **fixed.** New gate stage G1, a grep over every
  tracked file, allowlisting only `LICENSE.md`, `LICENSES/`, `CONTRIBUTORS.md`,
  the licences page generated from `LICENSE.md`, and no-reply addresses; it
  reports file and line and never the address. It lives in
  `scripts/check-no-personal-email.sh` so that `scripts/check.sh` and
  `.github/workflows/ci.yml` run the same code rather than two copies of a
  regex. The GPL headers are not allowlisted — checked, none carries an
  address. Tested by planting one in a tracked file and watching G1 go red.
- **ACRA was still in the third-party licence table** (medium) — **fixed.** The
  row is gone from `LICENSE.md` and from the rendered
  `app/src/main/assets/open_source_licenses.html`. There is no ACRA file in
  `LICENSES/` to remove; it was covered by the shared `LICENSES/APACHE-2.0`,
  which three other dependencies still need.

Two notes for ticket 08, which owns `generateOpenSourceLicensesFile.sh`:

- The html was hand-edited to match, because pandoc is not installed on this
  machine and could not regenerate it. The edit is the same row deletion plus
  the odd/even zebra classes pandoc would have emitted for the rows after it.
- `OPEN_SOURCE_LICENSES.md.patch` did not apply before this change either — two
  typos ("thiry-party", "umodified") had been fixed in `LICENSE.md` but not in
  the patch, and a blank line had moved. It was regenerated against the edited
  `LICENSE.md` and now applies clean. Worse, running it is destructive: the
  patch renames `LICENSE.md` to `OPEN_SOURCE_LICENSES.md`, so `git apply`
  *deletes* `LICENSE.md`. The script is out of scope here, so this is recorded
  rather than fixed.

**Open question, for the maintainer.** Redacting the tree does not redact
history: both addresses are in the published commits from `ec8353f` onwards,
and GitHub serves author metadata through its API. Rewriting the history of a
public repo is a call for the person whose address it is, and is not made here.
