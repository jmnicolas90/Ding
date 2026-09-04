# 07 — Rewrite the README and get the fork's attribution right

Type: task
Status: resolved
Blocked by: 05, 06

## Question

Two things at once, and they pull in opposite directions.

**The README must become Ding's.** Currently it is SimpleReminder's: an F-Droid badge, upstream screenshots, a "Planned features" list that is upstream's abandoned roadmap, contribution and donation sections pointing at another person. Rewrite for: what Ding is, that it is a fork and why, how to build it, what it requires (Android 12+), and that it is Google-free and runs on GrapheneOS. Screenshots need regenerating under the new name — that is fog, so leave a gap rather than shipping upstream's images.

**The attribution must stay correct.** GPL-3.0-or-later is not optional and neither is credit. The fork keeps: `LICENSE.md`, `LICENSES/`, every per-file copyright header, and `CONTRIBUTORS.md` naming Felix Wiemuth and the upstream contributors. What changes is that Ding's own copyright is *added* alongside, not substituted for, theirs. The README should state plainly that Ding is a fork of SimpleReminder by Felix Wiemuth, GPL-3.0-or-later, with a link to the original.

Getting this wrong in either direction is bad: erasing upstream is a licence violation, and leaving the repo looking like upstream's is the thing this map exists to fix.

Note the upstream "Planned features" list is worth **preserving somewhere** before deleting it from the README — it is a strong first draft of the feature map that comes after this one.

**Done when** a stranger landing on the repo understands what Ding is, that it descends from SimpleReminder, and under what licence — without any live link to upstream's issue tracker as though it were ours.

Note (added by ticket 03): fork-authored files now exist that upstream never had — `scripts/check.sh`
and `.github/workflows/ci.yml`. `CONTRIBUTING.md` requires the project copyright header on every new
file, but that header names Felix Wiemuth and 2018-2025, which is not true of these. Every existing
`.sh` and `.yml` in the repo carries no header at all, so the rule has only ever been honoured for
Gradle files. Decide the copyright line for fork-authored files here, then apply it to those two.


## Resolution (2026-09-05)

### The README is Ding's

Rewritten from scratch, 85 lines: what Ding is (a reminder app whose whole value
is firing reliably in the background — set it in seconds, get notified at the
due time, nagged until dealt with), a short list of the features that actually
exist today, the fork relationship, requirements, how to build, where to get it,
feedback, licence.

- **Fork stated plainly**, with a link to
  `https://github.com/felixwiemuth/SimpleReminder` labelled as the original
  project, the commit it was taken at (`d34bf2f`), and the reason: upstream
  development had stopped and the maintainer wanted to keep the app alive and
  extend it. No speculation past that.
- **Requirements**: Android 12 or later, `minSdk 31`. Google-free — no Play
  Services, no Firebase, nothing from GMS — running on GrapheneOS and plain
  AOSP, and said as an enforced property rather than a promise.
- **Building**: `./gradlew assembleDebug`, `scripts/check.sh` for the full gate,
  plus the SDK and JDK requirement from `CLAUDE.md`.
- **Distribution**: GitHub releases at `https://github.com/jmnicolas90/Ding/releases`,
  explicitly not F-Droid and not the Play Store.
- **Feedback**: GitHub issues on this repository only. No link to upstream's
  issue tracker, discussions or sponsor page anywhere in the file — the only
  upstream URL left is the one naming the original project.

### Attribution added alongside, never substituted

The copyright line for fork-authored files is **`Copyright (C) 2026 Jean-Michel
Nicolas`** — the git author name, no email address, ever. Applied as:

- **New GPL headers** on the four files upstream never had: `scripts/check.sh`,
  `scripts/check-no-personal-email.sh`, `scripts/codex-review.sh` (carried over
  from the maintainer's other project, so fork-authored) and
  `.github/workflows/ci.yml`. Same notice `CONTRIBUTING.md` prescribes, with `#`
  comment characters, below the shebang where there is one.
- **No upstream header was removed, edited or given a fork line.** The files
  this ticket touched that upstream wrote — `README.md`, `CONTRIBUTING.md`,
  `CONTRIBUTORS.md`, `about.html`, `help.html` — are lightly touched prose, so
  they keep upstream's header alone.
- `CONTRIBUTING.md`'s "Creating and changing files" rule now says this: a new
  file carries the fork's line, headers inherited from SimpleReminder are never
  removed or edited including their years, a substantially changed file may gain
  the fork's line *underneath* upstream's, and no header ever carries an email
  address. The file also stopped calling the project SimpleReminder and stopped
  offering to display a contributor's email address.
- `CONTRIBUTORS.md` keeps every upstream name and link. A "Fork" section names
  Jean-Michel Nicolas as Ding's maintainer, and the section below it now reads
  as history: Felix Wiemuth developed and maintained the original project.
- `README.md`'s licence section: GPL-3.0-or-later, Felix Wiemuth and
  contributors for the original, Jean-Michel Nicolas for the fork's changes,
  pointing at `LICENSE.md`, `LICENSES/` and `CONTRIBUTORS.md`. The full GPL
  notice was dropped from the README — it is in `LICENSE.md`, `LICENSES/GPL3`
  and every file header — which is what kept the file short.
- `about.html`, minimally: one sentence saying Ding is a fork of SimpleReminder
  by Felix Wiemuth under the GPL v3 or later, and the fork's copyright line
  under upstream's in the displayed credit.

### Upstream's roadmap preserved, then deleted

`docs/upstream-planned-features.md` holds the "Planned features" list verbatim
under a two-line preface saying where it came from (SimpleReminder by Felix
Wiemuth, commit `d34bf2f`) and that it is raw input for the next map, not a
commitment. The list is gone from the README.

### Gaps left on purpose

- **Screenshots.** Upstream's images are not shipped and none were invented. The
  README carries one marked line, "Screenshots: to be regenerated under the Ding
  name". The five upstream PNGs under `metadata/en-US/images/` were left alone,
  as instructed — they are still upstream's app under Ding's store title, which
  the same regeneration will fix.
- **German store metadata.** `metadata/de/` contains only `title.txt`, already
  "Ding", so consistency was free and there is nothing else to translate. The
  English `full_description.txt` lost its "See the website for many more planned
  features!" line, which pointed at the list this ticket deleted; it now states
  the licence, the fork and the Google-free property instead.
- **Dead anchors this rewrite would have created** were retargeted rather than
  left: `about.html` and `help.html` linked to `README.md#contributing` and
  `#planned-features`, and now point at `CONTRIBUTING.md` and the preserved
  list. `CONTRIBUTING.md`'s own back-link to `README.md#contributing` points at
  the README's Feedback section.
- **`CLAUDE.md` was not touched.** Nothing in it describes the README, and the
  gate and constraints it documents are unchanged.

## Review findings (2026-09-05)

- **Upstream screenshots shipped as Ding's** — fixed: the five PNGs under `metadata/en-US/images/phoneScreenshots/` were byte-identical to upstream's at `d34bf2f` and store tooling would have published them as Ding's, so they are deleted; the README keeps its marked gap line ("Screenshots: to be regenerated under the Ding name") and nothing else referenced the files.
- **`docs/upstream-planned-features.md` had no header while `CONTRIBUTING.md` demanded one** — fixed on both sides: the header rule now says it covers Kotlin, Java, Gradle, shell, YAML and XML resource files and not Markdown documentation, and the roadmap file opens with an attribution preface naming SimpleReminder by Felix Wiemuth at commit `d34bf2f`, copyright Felix Wiemuth, GPL-3.0-or-later, reproduced for the fork's own planning with no fork copyright claimed over it.
- **The roadmap claimed to be verbatim but was not** — fixed: the "Planned features" section is now reproduced inside a fenced plain-text block exactly as upstream wrote it, restoring the dropped `#20` issue reference and the closing "And much more!" line, with the historical URL preserved as text rather than a live link; `diff` against the section extracted from `git show d34bf2f:README.md` is empty.
- **"More than one upstream link remains live" in `about.html` and `CONTRIBUTORS.md`** — rejected: links to the original project from the about page and from the contributors file are attribution the licence obliges the fork to keep, and the ticket only forbids links to upstream's issue tracker or discussions presented as ours.
