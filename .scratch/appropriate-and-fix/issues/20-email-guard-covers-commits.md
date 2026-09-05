# 20 — Make the email guard cover commit metadata and every fork-authored tree

Type: task
Status: resolved
Blocked by: —

## Question

Global review finding (build axis, high), `scripts/check-no-personal-email.sh`.
See `../reviews/2026-09-05-global-review-build.md`, finding 1.

G1 greps the tracked files of the current tree and nothing else. That is how
the maintainer's personal address got published anyway: two ticket files quoted
it literally in the map-charting commit, the redaction commit fixed the tree two
commits later, and the blobs stayed in history and were pushed. History was
rewritten on 2026-09-05 (the fork's commits only, `main` force-pushed with a
lease; the backup bundle is outside the repo). Nothing stops it happening again,
and nothing at all checks author, committer, trailer or message body.

Fix, all in the same script so `scripts/check.sh` and CI cannot drift:

- **The identity the next commit would carry.** `git var GIT_AUTHOR_IDENT` and
  `git var GIT_COMMITTER_IDENT` must both use a no-reply address. This is the
  check that would have caught ticket 01's problem before the first commit.
- **Every commit the fork authored.** The range is
  `git rev-list HEAD ^d34bf2f ^62ef3e8`: `d34bf2f` is the fork point on
  upstream's `develop`, `62ef3e8` is the tip of upstream's `main` that the
  single-trunk merge brought in. Upstream's own commits carry the upstream
  author's address and are excluded **by commit range, never by naming an
  address** in the script. For each commit in the range: author, committer and
  the full message including trailers contain no address other than a no-reply
  one, and the commit's *tree* passes the same tracked-file grep as the working
  tree does today, so a leak that was redacted later is still caught. 48 commits
  today; the cost is milliseconds each.
- **A shallow clone is a failure, not a pass.** If
  `git rev-parse --is-shallow-repository` is true, or either exclusion commit is
  missing, exit 1 saying so. Set `fetch-depth: 0` on `actions/checkout` in
  `.github/workflows/ci.yml` so CI has the history.
- Keep the rule that the script reports commit, file and line but never prints
  the address it found.

Verify by hand in a throwaway branch of the worktree, and record the result in
the resolution: a commit with a personal author address, a commit with a
personal address in a `Co-authored-by:` trailer, and a commit that adds a file
holding an address which a later commit deletes, each make the script exit 1 and
name the commit; `main` passes; CI is green with full history.

**Done when** the script fails on all three synthetic cases and passes on
`main`, G1 in `scripts/check.sh` and the CI step both run it, and CI fetches
full history.

## Resolution (2026-09-05)

`scripts/check-no-personal-email.sh` now runs three checks instead of one, and
runs all three before it exits so a single run tells you the whole job. G1 in
`scripts/check.sh` and the CI step run that same one file, as before.

**1. The working tree**, unchanged in what it covers and slightly sharper in how
it decides: `git grep -o` puts each match on its own line, so the no-reply test
now looks at the matched address rather than at the whole line, and a real
address can no longer hide beside a no-reply one.

**2. The identity the next commit would carry.** Both
`git var GIT_AUTHOR_IDENT` and `git var GIT_COMMITTER_IDENT` must use a no-reply
address. This is the check that would have caught ticket 01 before the first
commit, and it still would: the machine's global `user.email` is a personal
address, and only the repo-local override makes this repository pass. It is run
with `user.useConfigOnly=true`, which makes git refuse to guess an identity from
the login name and the host, and that is how the check tells "somebody
configured an address" from "nobody has said anything". Where nothing at all is
configured the check is silent — that is a CI checkout, which configures no
identity and commits nothing, and a commit made under a guessed identity is
still caught by check 3 on the next run of the gate, so before any push.

**3. Every commit the fork authored**, the range `HEAD ^d34bf2f ^62ef3e8`, 49
commits today. For each one: author, committer, the whole message including its
trailers, and the commit's own tree under the same allowlist as the working
tree, so an address that was committed and redacted two commits later is still
caught. It costs 0.63 seconds for the whole history, so it stays where it is,
first in the gate and before anything starts a JVM.

**A shallow clone or a missing exclusion commit is a failure, not a pass.**
`git rev-parse --is-shallow-repository` and a `rev-parse --verify` of each of
the two boundary commits, each with the fix in the message.
`.github/workflows/ci.yml` therefore checks out with `fetch-depth: 0`; the
default is a depth-1 clone, which would otherwise turn this check into a
guaranteed red.

**Upstream is excluded by reachability, never by naming an address**, which
would put in this file the very thing the file exists to keep out of the repo.
The commit range does that for the commits. It is not quite enough for the
historical *trees*, and this is the one place the ticket's specification had to
give: the fork's early commits carry upstream's files unchanged, so the upstream
author's address sits in `Main.kt` from the fork point until ticket 06 deleted
it, and in the fork's own copy of ticket 06 until that quote was redacted. Run
strictly, the check reported 21 commits and could never go green. Reporting them
is noise, because a clone receives that address from upstream's own commits,
which stay in this repo's ancestry for good — the v0.9.x tags hang off them — so
no rewrite of the fork's commits would take it back. The historical trees
therefore pass over an address that upstream's own trees already contain, and
that set is read out of the two excluded commits at run time rather than written
down, so the rule is still reachability and still names nothing. The exemption
is narrow on purpose: it does not apply to the working tree, which holds every
address to account including upstream's — that is what ticket 06 removed and
what must not come back — and it does not apply to commit messages, which we
write ourselves.

### Verified by hand

On a throwaway branch in the worktree, deleted afterwards; no synthetic commit
reached the ticket branch and no invented address is in any committed file. The
addresses used were under the reserved `.invalid` top-level domain, so they
cannot belong to anyone.

| Case | Result |
|---|---|
| 1 — a commit whose author address is a personal one | exit 1, `commit 99edc1d…: author address is not a no-reply address (address withheld)` |
| 2 — a commit with a personal address in a `Co-authored-by:` trailer | exit 1, `commit 676582a…: email address in the commit message, at message line(s) 3` |
| 3 — one commit adds a file holding an address, the next deletes it | exit 1, `email address in a file at commit f093de6…` naming the file and line 1; the working-tree check passed, so only the historical tree caught it |
| the tip of this branch, nothing synthetic | exit 0, 0.63 s |

Five more, run the same way:

| Case | Result |
|---|---|
| a personal address in `GIT_AUTHOR_EMAIL` | exit 1, "the next commit's author address is not a no-reply address" |
| a repository with the machine's personal global `user.email` and no local override | exit 1, on both author and committer — ticket 01's exact situation |
| a repository with no identity configured anywhere | the identity check says nothing, as intended for a CI checkout |
| a `--depth 1` clone | exit 1, "shallow clone: the fork's own commits cannot be checked" |
| a repository without the two exclusion commits | exit 1, naming both missing commits |

CI could not be verified from the worktree, since that needs a push. The
workflow change is the one line `fetch-depth: 0` on `actions/checkout@v5`.

### Left out

- **The staged index.** The review's recommendation mentioned it; this ticket's
  fix list did not, and staged content is the working tree's content in all but
  a partial `git add -p`. Check 3 catches it one gate run later either way.
- **Tags, built artifacts and tracked binaries.** `-I` still skips binaries, and
  nothing walks an APK. Separate work if it is wanted.
- **Rewriting history again.** The upstream address in the fork's early trees
  could only be removed by rewriting all 49 commits, and it would still reach
  every clone from upstream's own commits, so the rewrite would buy nothing.
