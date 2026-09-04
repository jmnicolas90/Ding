# 06 — Remove ACRA and scrub every upstream contact point

Type: task
Status: open
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
