# 01 — Stop publishing a personal email address in commit metadata

Type: task
Status: resolved
Blocked by: —

## Question

`git config user.email` is the maintainer's personal address, this repo is public on GitHub, and GitHub serves commit author emails in plaintext through its API. Every commit would permanently publish that address to spam harvesters, and rewriting it later means rewriting history.

There are **zero commits** by this author in the repo so far. This is the one moment the fix is free.

Set a repository-local identity before any other ticket commits anything:

- `git config user.email "68194446+jmnicolas90@users.noreply.github.com"` (the no-reply address GitHub issues for this account; the numeric ID is confirmed from the GitHub API)
- Decide whether `user.name` stays `Jean-Michel NICOLAS` or becomes the GitHub handle. The real name is far less harvestable than the address and is a reasonable thing to keep — this is a preference, not a security question.

Repository-local rather than global, so other repos are unaffected.

**Done when** a test commit in a scratch branch shows the no-reply address in `git log --format='%ae'`, and the branch is discarded.

## Answer

Done on 2026-09-04, before any commit was authored in this repo — so no rewrite was needed and the address never entered history.

**What was set** (repository-local, via `git config --local`):

- `user.email` = `68194446+jmnicolas90@users.noreply.github.com`
- `user.name` = `Jean-Michel NICOLAS` — kept. A real name is not harvestable the way an address is, it is the reversible half of this decision, and it keeps `git log` honest about who the human author is. The address was the whole problem.

Local rather than global by design: `git config --global user.email` is still the maintainer's personal address, so every other repo on this machine is unaffected — **and still exposed if any of them is public.** Out of scope here, but worth a look.

**Verified two ways.** `git var GIT_AUTHOR_IDENT` and `GIT_COMMITTER_IDENT` both report the no-reply address; and a throwaway empty commit on `scratch/verify-identity` was inspected with `git log -1 --format='%an <%ae> / %cn <%ce>'`, confirming author *and* committer carry it. Grepping that commit's identity fields for `proton.me` returned nothing. The branch was deleted and the tree left on `develop`, clean.

**Fact later tickets depend on:** commits from this repo are safe to push publicly. The map and everything else can now be committed.

**Optional follow-up, needs your GitHub account:** enabling *Settings → Emails → "Block command line pushes that expose my email"* makes GitHub reject a push that carries a private address, which turns this from a convention into an enforced rule. Can't be checked or set from here — no `gh` CLI and no auth.
