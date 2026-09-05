# 20 — Make the email guard cover commit metadata and every fork-authored tree

Type: task
Status: open
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
