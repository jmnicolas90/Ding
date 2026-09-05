#!/usr/bin/env bash
# Copyright (C) 2026 Jean-Michel Nicolas
#
# This program is free software: you can redistribute it and/or modify
# it under the terms of the GNU General Public License as published by
# the Free Software Foundation, either version 3 of the License, or
# (at your option) any later version.
#
# This program is distributed in the hope that it will be useful,
# but WITHOUT ANY WARRANTY; without even the implied warranty of
# MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
# GNU General Public License for more details.
#
# You should have received a copy of the GNU General Public License
# along with this program.  If not, see <http://www.gnu.org/licenses/>.

# Fails when an email address appears in a tracked file, in the staged index, in
# the identity the next commit would carry, or anywhere in the commits this fork
# authored.
#
# CLAUDE.md's constraint is "no personal email address anywhere" — not in the
# tree, not in commit metadata, not in published artifacts. This is the
# enforced half of it, the way checkNoGoogleDependencies enforces the no-Google
# rule rather than leaving it to good intentions. The repo is public, so an
# address that reaches a push is published to harvesters the moment it lands,
# and taking it back means rewriting history.
#
# Four checks, and all four run: the script reports everything it finds rather
# than stopping at the first hit, so one run tells you the whole job.
#
#   1. Every tracked file in the working tree.
#   2. Every tracked file as it is staged in the index. The index is not the
#      working tree: `git add -p` can stage a hunk holding an address while the
#      file on disk is being cleaned up around it, and the commit takes what is
#      staged. Without this check that commit lands after a green gate.
#   3. The author and committer identity the *next* commit would carry, which
#      is the one check that fires before anything has been written.
#   4. Every commit this fork authored — author, committer, the whole message
#      including its trailers, and the commit's own tree. The tree matters
#      because a clone receives every historical blob: an address that was
#      committed and redacted two commits later is still published, and only
#      this check sees it.
#
# Check 4 needs real history, so a shallow clone or a clone missing either of
# the two commits that bound upstream's history is a failure, not a pass — the
# check must not look green exactly where it can see the least. That is why
# .github/workflows/ci.yml sets fetch-depth: 0 on its checkout.
#
# Upstream's own commits carry the upstream author's address. They are excluded
# from check 4 by commit range — by reachability, never by naming an address
# here, which would put in this file the very thing the file exists to keep out
# of the repo.
#
# The scan of the historical trees is the one place that rule is knowingly
# relaxed, and there it is relaxed by address value. Here is why. The fork's
# early commits carry upstream's files unchanged, so upstream's author address
# sits in the fork's own trees as well as in upstream's: in one source file from
# the fork point until ticket 06 deleted it, and in the fork's copy of ticket 06
# until that quote was redacted. Run strictly, the check reports every one of
# those commits and can never go green — and since it is the first stage of the
# gate, no commit could ever be made to fix it.
#
# Relaxing it gives up nothing that could still be recovered. The history
# rewrite of 2026-09-05 deliberately removed the maintainer's own address and
# only that. Upstream's author address is inherited history: it reaches every
# clone through upstream's own commits, which stay in this repo's ancestry for
# good — the v0.9.x tags hang off them — so no rewrite of the fork's commits
# would unpublish it. Reporting it forever would buy nothing and would bury real
# findings under noise.
#
# So the historical trees pass over exactly the set of address values the two
# boundary trees hold. That set is read out of those two commits at run time and
# is never written into this file, so this file still names no address.
#
# The exemption is deliberately narrow: historical trees and nothing else. Not
# the working tree, not the index, not an identity, not a commit message. A fork
# commit that copies that address into a new file is caught before it can land,
# by checks 1 and 2 on the tree and on the index; only the after-the-fact scan
# of history tolerates it, and only because history is the one place the fork
# cannot put it right.
#
# Both scripts/check.sh (stage G1) and .github/workflows/ci.yml run this one
# file, so the pattern, the allowlist and the commit range live in a single
# place and the two gates cannot drift apart on them.
#
# Allowed, and why:
#   - LICENSE.md, LICENSES/, CONTRIBUTORS.md — GPL attribution. None of them
#     carries an address today; they are allowed anyway because attribution is
#     the one case where an address would be legitimate rather than a leak.
#   - app/src/main/assets/open_source_licenses.html — generated from LICENSE.md
#     by scripts/generate-open-source-licenses.sh, so it can only ever carry
#     what LICENSE.md already carries.
#   - a no-reply address, as is_no_reply_address defines it and nothing wider:
#     the forge and vendor no-reply addresses that commits and co-author
#     trailers are signed with. The test is on the matched address itself and
#     not on the line it sits on, so a real address cannot hide beside a
#     no-reply one.
# The GPL copyright headers are deliberately NOT allowlisted: they carry names,
# not addresses. If one ever gains an address, the header is the thing to fix.
#
# It reports the commit, the file and the line number and never prints the
# address itself, so a failing gate does not republish what it just caught.
# That is also why tracing is turned off below and never turned back on, and why
# no comment in this file spells out an example address: this script is itself a
# tracked file, and check 1 reads it like any other.

# An inherited `bash -x` would print every matched address to stderr, which is
# exactly the republishing this script exists to prevent. Off before anything
# else runs, and nothing here turns it back on.
set +x
set -euo pipefail

cd "$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# Case-insensitive on both sides: the character classes accept either case, and
# every grep below is given -i as well. Either alone would do; together, neither
# a future edit to the pattern nor a dropped flag at one call site can quietly
# let an all-capitals address through, which the lowercase-only top-level domain
# this pattern used to end in did.
address_pattern='[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}'

# Paths where an address would be attribution the licence requires.
attribution_paths=(
  ':!LICENSE.md'
  ':!LICENSES/'
  ':!CONTRIBUTORS.md'
  ':!app/src/main/assets/open_source_licenses.html'
)

# The two commits that bound upstream's work: d34bf2f is the fork point on
# upstream's develop, 62ef3e8 the tip of upstream's main that the single-trunk
# merge brought in. Everything reachable from either is upstream's; everything
# else in this history is the fork's own and has to answer for itself.
fork_point='d34bf2f'
upstream_main_tip='62ef3e8'

failures=0

# Report one problem. Every argument is printed on its own line, so a caller can
# pass the headline, the hits and the advice as separate strings. Callers pass
# locations, never the text that matched.
fail() {
  printf '%s\n' "$@" >&2
  failures=$((failures + 1))
}

# The one test for "this is a no-reply address", used by every check, so there
# is a single answer to the question rather than one answer per call site.
#
# Strict on purpose. The old test asked whether the address *contained*
# "noreply", which a real mailbox can trivially arrange: put the word in the
# local part beside a real name, or register a domain with the word in it, and a
# deliverable address walks through the gate. An address qualifies here only
# when its local part is exactly "noreply", or its domain is exactly GitHub's
# per-user no-reply domain. Anything else is a hit. Both halves are compared
# lowercased, because neither a local part nor a domain is case-sensitive in any
# address this repo signs with.
is_no_reply_address() {
  local address="$1" local_part domain
  local_part="${address%@*}"
  domain="${address#*@}"
  [ "${local_part,,}" = 'noreply' ] || [ "${domain,,}" = 'users.noreply.github.com' ]
}

# The address values upstream's own boundary trees hold, lowercased. Filled by
# read_upstream_published_addresses and read by the historical-tree scan alone.
declare -A upstream_published_addresses=()

# Print "file:line" for every address that is neither a no-reply one nor, where
# asked, one upstream already published; prefixed with the revision when the
# scan is of a commit. $1 says where to look: "worktree", "index", or a commit
# id. $2 is "none" or "upstream" and picks the exemption. Returns 2 when git
# grep itself failed.
#
# The three callers differ only in those two arguments, so the pattern and the
# flags are spelled once and cannot drift between the tree, the index and
# history. --cached has to go before the pattern; a revision has to go after it,
# or git grep reads the option as a revision and fails.
#
# git grep searches tracked files only, which is the same set as `git ls-files`.
# -a treats every blob as text: the -I it replaces skipped whatever git calls
# binary, so a file holding one NUL byte and an address sailed through the gate.
# With -o each match is its own output line ending in the matched address, which
# is what lets the tests below look at the address rather than at the whole line
# — a real address cannot hide beside an allowed one.
addresses_in_tree() {
  local where="$1" exemption="$2"
  local raw status line location address
  local -a grep_args=(-naoEi "$address_pattern")
  case "$where" in
    worktree) ;;
    index) grep_args=(--cached "${grep_args[@]}") ;;
    *) grep_args+=("$where") ;;
  esac
  set +e
  raw="$(git grep "${grep_args[@]}" -- "${attribution_paths[@]}")"
  status=$?
  set -e
  # git grep exits 1 for "no matches", which is the good case here. Anything
  # above that is a real failure and must not pass for a clean tree.
  if [ "$status" -gt 1 ]; then
    return 2
  fi
  if [ -z "$raw" ]; then
    return 0
  fi
  # The matched address is the last colon-separated field and can hold no colon
  # itself, so dropping that field leaves the location however many colons the
  # path contains.
  while IFS= read -r line; do
    if [ -z "$line" ]; then
      continue
    fi
    address="${line##*:}"
    location="${line%:*}"
    if is_no_reply_address "$address"; then
      continue
    fi
    if [ "$exemption" = 'upstream' ] \
      && [ -n "${upstream_published_addresses[${address,,}]+set}" ]; then
      continue
    fi
    printf '%s\n' "$location"
  done <<< "$raw"
}

# Read the exempt set out of the two boundary trees. Same pattern and same flags
# as the scans, so every address the historical scan can match is one this can
# match too; without that, an inherited file would produce a hit no exemption
# could ever cover. No path exclusions: an address upstream published is
# published whichever of its files holds it. Returns 1 when git grep failed or
# when the set came out empty, because an empty set would silently turn the
# exemption off rather than mean there is nothing to exempt.
read_upstream_published_addresses() {
  local boundary raw status line address
  for boundary in "$fork_point" "$upstream_main_tip"; do
    set +e
    raw="$(git grep -naoEi "$address_pattern" "$boundary")"
    status=$?
    set -e
    if [ "$status" -gt 1 ]; then
      return 1
    fi
    if [ -z "$raw" ]; then
      continue
    fi
    while IFS= read -r line; do
      if [ -z "$line" ]; then
        continue
      fi
      address="${line##*:}"
      upstream_published_addresses["${address,,}"]=1
    done <<< "$raw"
  done
  if [ "${#upstream_published_addresses[@]}" -eq 0 ]; then
    return 1
  fi
}

# 1. The working tree. No exemption: an address upstream published is still an
# address this fork would be shipping.
check_working_tree() {
  local hits status=0
  hits="$(addresses_in_tree worktree none)" || status=$?
  if [ "$status" -ne 0 ]; then
    fail "✗ git grep failed while searching the working tree"
    return
  fi
  if [ -n "$hits" ]; then
    fail "✗ email address in tracked files (file and line only, address withheld):" \
         "$(printf '%s\n' "$hits" | sed 's/^/    /')" \
         "  If it is attribution the licence requires, it belongs in LICENSE.md," \
         "  LICENSES/ or CONTRIBUTORS.md. Otherwise remove it." \
         "  Line numbers are the working tree's; check 2 reports the index separately."
  fi
}

# 2. The index, which is what a commit actually takes. Same scan, same
# allowlist, no exemption — only the content differs, and it differs exactly in
# the case this catches: a hunk staged out of a file that has since been cleaned
# up on disk.
check_index() {
  local hits status=0
  hits="$(addresses_in_tree index none)" || status=$?
  if [ "$status" -ne 0 ]; then
    fail "✗ git grep failed while searching the index"
    return
  fi
  if [ -n "$hits" ]; then
    fail "✗ email address staged in the index (file and line only, address withheld):" \
         "$(printf '%s\n' "$hits" | sed 's/^/    /')" \
         "  The line numbers are the staged content's, not the working tree's." \
         "  fix: unstage it (git restore --staged <file>) and remove it."
  fi
}

# 3. The identity the next commit would carry. git var applies the same
# precedence a commit does — the environment, then repo config, then global — so
# this is the address that would actually be written, and asking git beats
# reimplementing that order here.
#
# user.useConfigOnly stops git falling back to a guess made from the login name
# and the host, so a failure here means "nobody configured an address", not "the
# address is fine". That is a failure too. This check has one job, to know what
# the next commit would be signed with, and it either knows or it does not;
# treating "cannot tell" as a pass makes the gate green exactly where it is
# blindest. A checkout with no identity is one where the next commit is signed
# with whatever git can piece together, and the way to find that out is to
# configure it, not to skip the question.
#
# The one place that reasoning does not hold is a hosted CI runner, which
# configures no identity and never commits; that case is handled at the call
# site, once, and out loud.
check_next_commit_identity() {
  local role="$1" git_variable="$2" ident address status=0
  ident="$(git -c user.useConfigOnly=true var "$git_variable" 2>/dev/null)" || status=$?
  if [ "$status" -ne 0 ]; then
    fail "✗ git cannot say what $role address the next commit would carry" \
         "  With user.useConfigOnly that means no address is configured here." \
         "  fix: git config user.email with your forge's no-reply address"
    return
  fi
  # An identity is "Name <address> timestamp zone", and a name may itself hold
  # an angle bracket, so take what lies between the last < and the next >. An
  # identity without both brackets is one this check cannot read, which is the
  # same "cannot tell" as above and gets the same answer.
  case "$ident" in
    *'<'*'>'*) ;;
    *) fail "✗ the $role identity the next commit would carry is not in a readable form" \
            "  expected: Name <address> timestamp zone"
       return ;;
  esac
  address="${ident##*<}"
  address="${address%%>*}"
  if ! is_no_reply_address "$address"; then
    fail "✗ the next commit's $role address is not a no-reply address (address withheld)" \
         "  fix: git config user.email with your forge's no-reply address"
  fi
}

# 4. Every commit the fork authored.
check_fork_commits() {
  local missing=0 boundary commits commit metadata author committer message
  local message_lines match address hits status

  if [ "$(git rev-parse --is-shallow-repository)" != "false" ]; then
    fail "✗ shallow clone: the fork's own commits cannot be checked" \
         "  fix: clone with full history (actions/checkout needs fetch-depth: 0)"
    return
  fi

  for boundary in "$fork_point" "$upstream_main_tip"; do
    if ! git rev-parse --verify --quiet "$boundary^{commit}" >/dev/null; then
      fail "✗ commit $boundary is missing, so upstream's own commits cannot be excluded" \
           "  fix: fetch this repository's full history"
      missing=1
    fi
  done
  if [ "$missing" -ne 0 ]; then
    return
  fi

  commits="$(git rev-list HEAD "^$fork_point" "^$upstream_main_tip")"
  if [ -z "$commits" ]; then
    return
  fi

  if ! read_upstream_published_addresses; then
    fail "✗ could not read the addresses upstream's own commits already publish" \
         "  fix: fetch this repository's full history"
    return
  fi

  while IFS= read -r commit; do
    metadata="$(git show --no-patch --format='%ae%n%ce%n%B' "$commit")"
    author="$(printf '%s\n' "$metadata" | sed -n 1p)"
    committer="$(printf '%s\n' "$metadata" | sed -n 2p)"
    message="$(printf '%s\n' "$metadata" | sed -n '3,$p')"

    if ! is_no_reply_address "$author"; then
      fail "✗ commit $commit: author address is not a no-reply address (address withheld)"
    fi
    if ! is_no_reply_address "$committer"; then
      fail "✗ commit $commit: committer address is not a no-reply address (address withheld)"
    fi

    # The whole message, subject and body and trailers alike, so a
    # Co-authored-by line with a personal address is caught like any other. No
    # exemption here: we write our own commit messages. grep exits 1 when the
    # message holds no address at all, which is the good case and must not trip
    # pipefail.
    message_lines=''
    while IFS= read -r match; do
      if [ -z "$match" ]; then
        continue
      fi
      address="${match##*:}"
      if is_no_reply_address "$address"; then
        continue
      fi
      message_lines="$message_lines${match%%:*} "
    done < <(printf '%s\n' "$message" | { grep -naoEi "$address_pattern" || true; })
    if [ -n "$message_lines" ]; then
      fail "✗ commit $commit: email address in the commit message, at message line(s) ${message_lines% }"
    fi

    status=0
    hits="$(addresses_in_tree "$commit" upstream)" || status=$?
    if [ "$status" -ne 0 ]; then
      fail "✗ git grep failed while searching the tree of commit $commit"
    elif [ -n "$hits" ]; then
      fail "✗ email address in a file at commit $commit (commit, file and line only, address withheld):" \
           "$(printf '%s\n' "$hits" | sed 's/^/    /')"
    fi
  done <<< "$commits"
}

check_working_tree
check_index
# A hosted runner has no configured identity and never commits, so there is no
# "next commit" for check 3 to be about. Skipping it there is the one exception
# to treating an unreadable identity as a failure; it is announced rather than
# silent, and what was actually pushed is still covered by check 4.
if [ "${GITHUB_ACTIONS:-}" = 'true' ]; then
  echo "· GITHUB_ACTIONS=true: skipping the next-commit identity check (nothing commits on a hosted runner; check 4 covers what was pushed)"
else
  check_next_commit_identity author GIT_AUTHOR_IDENT
  check_next_commit_identity committer GIT_COMMITTER_IDENT
fi
check_fork_commits

if [ "$failures" -ne 0 ]; then
  exit 1
fi
