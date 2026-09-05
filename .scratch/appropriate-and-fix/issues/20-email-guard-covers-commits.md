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
  *Superseded: the index is scanned now — see finding 5 below.*
- **Tags, built artifacts and tracked binaries.** `-I` still skips binaries, and
  nothing walks an APK. Separate work if it is wanted. *Superseded in part:
  tracked binaries are scanned now — see finding 3 below. Tags and built
  artifacts are still out.*
- **Rewriting history again.** The upstream address in the fork's early trees
  could only be removed by rewriting all 49 commits, and it would still reach
  every clone from upstream's own commits, so the rewrite would buy nothing.

## Review findings (2026-09-05)

Six findings on `scripts/check-no-personal-email.sh`, all accepted, all fixed in
one pass. Verified by hand on a throwaway branch inside the worktree, deleted
afterwards. Every invented address used the reserved `.invalid` top-level
domain, so none of them can belong to anyone, and none reached a committed file
— including the script's own comments, which name no address either, since the
script is a tracked file the working-tree scan reads like any other.

- **The upstream exemption had to be decided, and it is decided by address
  value** (blocking) — settled by the maintainer, and it is the one place where
  this ticket's "by reachability, never by naming an address" is knowingly
  relaxed. Say so plainly rather than pretend otherwise.

  The fork's early commits carry upstream's files unchanged, so upstream's
  author address sits in the fork's own trees as well as in upstream's. Run the
  historical scan with no exemption at all and it reports 21 commits and 39
  file-and-line hits: `Main.kt` under both the old package path and the renamed
  one, and the fork's own copy of ticket 06. G1 is the first stage of the gate,
  so a check that can never go green is a check no commit could ever be made to
  fix.

  A stricter alternative was tried first and deadlocks the same way: exempting
  *files* by blob id, so that only byte-for-byte upstream content passes. That
  leaves 22 blobs the fork itself authored — its copy of ticket 06 across 19
  commits, and `Main.kt` across the 3 commits after the package rename — each
  quoting the address in content the fork wrote, and each unfixable for the same
  reason.

  The decision: the historical-tree scan exempts exactly the set of address
  values the two boundary trees `d34bf2f` and `62ef3e8` hold. That set is read
  out of those two commits at run time and is never written into the script, so
  the script still names no address; it comes to one value today. The reasoning
  is that the history rewrite of 2026-09-05 deliberately removed the
  maintainer's own address and only that. Upstream's author address is inherited
  history: it reaches every clone through upstream's own commits, which stay in
  this repository's ancestry for good — the v0.9.x tags hang off them — so no
  rewrite of the fork's commits could unpublish it. Reporting it for ever buys
  nothing and buries real findings under noise.

  The exemption applies to historical trees and to nothing else: not the working
  tree, not the index, not an identity, not a commit message. A fork commit that
  copies that address into a new file is caught before it can land, by the
  working-tree and index scans at commit time; only the after-the-fact scan of
  history tolerates it, and only because history is the one place the fork
  cannot put it right. The script's header comment now sets out this whole
  argument.

- **Address candidates were matched case-sensitively, and "no-reply" was a
  substring test** (high) — fixed. The pattern ended in a lowercase-only
  top-level domain class, so an all-capitals address matched nothing; the
  character classes now accept either case and every grep is given `-i` as well,
  so neither a future edit to the pattern nor a dropped flag at one call site
  can quietly reopen the hole. The no-reply test is now one function used by all
  four checks instead of a `*noreply*` case pattern repeated at each call site,
  and it is strict: an address qualifies only when its local part is exactly
  `noreply` or its domain is exactly `users.noreply.github.com`, both compared
  lowercased. The old test let a deliverable mailbox through for the price of
  putting the word in the local part or registering a domain containing it.

- **`-I` skipped blobs git calls binary** (high) — fixed. The scans use `-a`,
  so every tracked blob is read as text. A file holding one NUL byte and an
  address used to sail through the gate. No real tracked binary in this
  repository produces a false match: the working-tree scan reports only the nine
  no-reply addresses it always did, and the historical scan of all 50 fork
  commits stays green.

- **A failing `git var` was treated as clean** (high) — fixed. An identity git
  cannot report, or one this script cannot read, is now a failure with its own
  message rather than a silent pass. The check has one job — know what the next
  commit would be signed with — and it either knows or it does not; passing on
  "cannot tell" made the gate green exactly where it was blindest. The single
  exception is a GitHub Actions runner, which configures no identity and commits
  nothing: when `GITHUB_ACTIONS` is `true` the check is skipped and one line
  says so, and what was actually pushed is still covered by the scan of the
  commits.

- **The index went unchecked** (medium) — fixed. A fourth scan runs
  `git grep --cached` over the same allowlist with the same pattern (`--cached`
  goes before the pattern; a revision goes after it). A hunk staged with
  `git add -p` out of a file since cleaned up on disk is what this catches, and
  the working-tree scan by definition cannot see it.

- **An inherited shell trace would have printed every address it matched**
  (medium) — fixed. `set +x` runs before anything else and nothing turns tracing
  back on. No diagnostic path echoes a matched line: every message reports a
  commit, a file and a line number, or a message line number, and says the
  address is withheld.

### Verified by hand

| Case | Result |
|---|---|
| an all-capitals address in a tracked file | reported; under the old pattern it matched nothing |
| a `noreply` local part, either case | allowed |
| `…+noreply@…`, `noreply.person@…` and `person@noreply.…` | all three reported; the old substring test allowed all three |
| a per-user `users.noreply.github.com` address, either case | allowed |
| a domain of `users.noreply.github.com.invalid` | reported |
| a tracked file holding a NUL byte and an address | reported by the working-tree and index scans; the old `-I` found nothing in it |
| an address staged in the index and cleaned up on disk | reported by the index scan alone, the working-tree scan silent; nothing caught this before |
| a commit whose author address is a personal one | exit 1, `commit a305422…: author address is not a no-reply address (address withheld)` |
| a commit with a personal address in a `Co-authored-by:` trailer | exit 1, `commit 836e34d…: email address in the commit message, at message line(s) 3` |
| one commit adds a file holding an address, the next deletes it | exit 1, `email address in a file at commit f7a9851…` naming the file and line 1; the working-tree scan passed |
| a fork commit copying upstream's inherited address into a new file | the working-tree and index scans both report it; once committed and deleted again the historical scan is silent — the exemption doing exactly its one job and nothing wider |
| no identity configured anywhere | exit 1 on both roles, "git cannot say what … address the next commit would carry"; the old script said nothing |
| the same, with `GITHUB_ACTIONS=true` | exit 0, one line saying the identity check is skipped |
| a personal address in `GIT_AUTHOR_EMAIL` | exit 1 on the author only |
| a clone carrying the machine's personal global `user.email` and no local override | exit 1 on both roles — ticket 01's exact situation |
| an identity whose name itself holds angle brackets | exit 0; the address is still read correctly |
| a `--depth 1` clone | exit 1, "shallow clone: the fork's own commits cannot be checked" |
| a repository without the two boundary commits | exit 1, naming both |
| the guard run under `bash -x` | 5 lines of output, none carrying the address; the same script with its `set +x` line removed printed 8191 trace lines, 8 of them carrying it |
| the branch tip, nothing synthetic | exit 0, 0.75 s for 50 commits |

### Still left out

- **Tags and built artifacts.** Nothing walks an APK, and no tag is checked
  beyond the commits it points into. Separate work if it is wanted.
- **CI.** Still not verifiable from a worktree, since that needs a push. The
  `fetch-depth: 0` on `actions/checkout` was already in place from the first
  pass and is unchanged.
- **The internal `git grep` failure path.** The branch that fires when git grep
  exits above 1 is covered by inspection; provoking it means breaking git.
