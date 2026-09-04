# 07 — Rewrite the README and get the fork's attribution right

Type: task
Status: open
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

