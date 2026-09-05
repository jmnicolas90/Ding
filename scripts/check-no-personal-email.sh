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

# Fails when an email address appears in a tracked file, in the identity the
# next commit would carry, or anywhere in the commits this fork authored.
#
# CLAUDE.md's constraint is "no personal email address anywhere" — not in the
# tree, not in commit metadata, not in published artifacts. This is the
# enforced half of it, the way checkNoGoogleDependencies enforces the no-Google
# rule rather than leaving it to good intentions. The repo is public, so an
# address that reaches a push is published to harvesters the moment it lands,
# and taking it back means rewriting history.
#
# Three checks, and all three run: the script reports everything it finds
# rather than stopping at the first hit, so one run tells you the whole job.
#
#   1. Every tracked file in the working tree.
#   2. The author and committer identity the *next* commit would carry, which
#      is the one check that fires before anything has been written. It is
#      silent where no identity is configured at all, because that is a
#      checkout nobody commits from rather than an address anybody chose.
#   3. Every commit this fork authored — author, committer, the whole message
#      including its trailers, and the commit's own tree. The tree matters
#      because a clone receives every historical blob: an address that was
#      committed and redacted two commits later is still published, and only
#      this check sees it.
#
# Check 3 needs real history, so a shallow clone or a clone missing either of
# the two commits that bound upstream's history is a failure, not a pass — the
# check must not look green exactly where it can see the least. That is why
# .github/workflows/ci.yml sets fetch-depth: 0 on its checkout.
#
# Upstream's own commits carry the upstream author's address. They are excluded
# by commit range, never by naming an address here, which would put in this
# file the very thing the file exists to keep out of the repo.
#
# The range alone is not quite enough for the historical trees, because the
# fork's early commits carry upstream's files unchanged — the upstream author's
# address sat in Main.kt from the fork point until ticket 06 deleted it, so
# every fork commit before that has it in its tree. Reporting those is noise: a
# clone receives that address from upstream's own commits, which are in this
# repo's ancestry for good (the v0.9.x tags hang off them), so rewriting the
# fork's commits would not take it back. Check 3 therefore passes over an
# address that upstream's own trees already contain. That set is read out of
# the two excluded commits at run time — the same by-reachability rule as the
# commit range, and still no address written down here.
#
# The exemption is deliberately narrow. It applies to the historical trees and
# to nothing else: check 1 holds every address in the working tree to account,
# including upstream's, which is what ticket 06 removed and what must not come
# back; and a commit message is written by us, so nothing excuses an address in
# one.
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
#   - any address containing "noreply" — the GitHub and vendor no-reply
#     addresses that commits and co-author trailers are signed with. The test
#     is on the matched address itself and not on the line it sits on, so a
#     real address cannot hide beside a no-reply one.
# The GPL copyright headers are deliberately NOT allowlisted: they carry names,
# not addresses. If one ever gains an address, the header is the thing to fix.
#
# It reports the commit, the file and the line number and never prints the
# address itself, so a failing gate does not republish what it just caught.
set -euo pipefail

cd "$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

address_pattern='[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[a-z]{2,}'

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

# Report one problem. Every argument is printed on its own line, so a caller
# can pass the headline, the hits and the advice as separate strings.
fail() {
  printf '%s\n' "$@" >&2
  failures=$((failures + 1))
}

# Print "file:line" for every address in a tree that is neither a no-reply one
# nor one of the exempt addresses, prefixed with the revision when one is
# given. $1 is the newline-separated exempt set, which is empty for every
# caller but the historical trees; the rest is passed through to git grep, so
# no further argument means the working tree and one revision means that
# commit's tree. Returns 2 when git grep itself failed.
#
# git grep searches tracked files only, which is the same set as `git ls-files`;
# -I skips binaries so an APK-shaped blob cannot produce a false match. With -o
# each match is its own output line ending in the matched address, which is what
# lets the no-reply and exempt tests look at the address rather than at the
# whole line — a real address cannot hide beside an allowed one.
addresses_in_tree() {
  local exempt="$1"
  shift
  local raw status
  set +e
  raw="$(git grep -nIoE "$address_pattern" "$@" -- "${attribution_paths[@]}")"
  status=$?
  set -e
  # git grep exits 1 for "no matches", which is the good case here. Anything
  # above that is a real failure and must not pass for a clean tree.
  if [ "$status" -gt 1 ]; then
    return 2
  fi
  # The matched address is the last colon-separated field and can hold no colon
  # itself, so dropping that field leaves the location however many colons the
  # path contains. The exempt set goes through the environment rather than
  # through -v so awk does not read escape sequences in it.
  printf '%s\n' "$raw" | EXEMPT_ADDRESSES="$exempt" awk -F: '
    BEGIN {
      count = split(ENVIRON["EXEMPT_ADDRESSES"], list, "\n")
      for (i = 1; i <= count; i++) if (list[i] != "") exempt[list[i]] = 1
    }
    $0 == "" { next }
    index($NF, "noreply") != 0 { next }
    $NF in exempt { next }
    { sub(/:[^:]*$/, ""); print }
  '
}

# Every address upstream's own trees hold, read out of the two excluded commits
# rather than written down. These are upstream's to publish and are in this
# repo's ancestry for good, so the fork's early commits carrying them is not
# something the fork can put right.
addresses_upstream_published() {
  local boundary raw status all=''
  for boundary in "$fork_point" "$upstream_main_tip"; do
    set +e
    raw="$(git grep -nIoE "$address_pattern" "$boundary")"
    status=$?
    set -e
    if [ "$status" -gt 1 ]; then
      return 2
    fi
    all="$all$raw"$'\n'
  done
  printf '%s\n' "$all" | awk -F: '$0 != "" { print $NF }' | sort -u
}

# 1. The working tree. No exemption: an address upstream published is still an
# address this fork would be shipping.
check_working_tree() {
  local hits status=0
  hits="$(addresses_in_tree '')" || status=$?
  if [ "$status" -ne 0 ]; then
    fail "✗ git grep failed while searching the working tree"
    return
  fi
  if [ -n "$hits" ]; then
    fail "✗ email address in tracked files (file and line only, address withheld):" \
         "$(printf '%s\n' "$hits" | sed 's/^/    /')" \
         "  If it is attribution the licence requires, it belongs in LICENSE.md," \
         "  LICENSES/ or CONTRIBUTORS.md. Otherwise remove it."
  fi
}

# 2. The identity the next commit would carry. git var applies the same
# precedence a commit does — the environment, then repo config, then global —
# so this is the address that would actually be written, and asking git beats
# reimplementing that order here.
#
# user.useConfigOnly stops git falling back to a guess made from the login name
# and the host, which is how the check tells "somebody configured an address"
# from "nobody has said anything". Only the first is this check's business: a
# guess is not a personal address, nothing is configured on a CI checkout, and
# nothing commits there anyway. A commit made under a guessed identity is still
# caught, by check 3, on the next run of the gate and so before any push.
check_next_commit_identity() {
  local role="$1" git_variable="$2" ident address
  ident="$(git -c user.useConfigOnly=true var "$git_variable" 2>/dev/null)" || return 0
  # An identity is "Name <address> timestamp zone", and a name may itself hold
  # an angle bracket, so take what lies between the last < and the next >.
  address="${ident##*<}"
  address="${address%%>*}"
  case "$address" in
    *noreply*) ;;
    *) fail "✗ the next commit's $role address is not a no-reply address (address withheld)" \
            "  fix: git config user.email with your forge's no-reply address" ;;
  esac
}

# 3. Every commit the fork authored.
check_fork_commits() {
  local missing=0 boundary commits commit metadata author committer message
  local message_lines hits status inherited

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

  status=0
  inherited="$(addresses_upstream_published)" || status=$?
  if [ "$status" -ne 0 ]; then
    fail "✗ git grep failed while reading the addresses upstream published"
    return
  fi

  while IFS= read -r commit; do
    metadata="$(git show --no-patch --format='%ae%n%ce%n%B' "$commit")"
    author="$(printf '%s\n' "$metadata" | sed -n 1p)"
    committer="$(printf '%s\n' "$metadata" | sed -n 2p)"
    message="$(printf '%s\n' "$metadata" | sed -n '3,$p')"

    case "$author" in
      *noreply*) ;;
      *) fail "✗ commit $commit: author address is not a no-reply address (address withheld)" ;;
    esac
    case "$committer" in
      *noreply*) ;;
      *) fail "✗ commit $commit: committer address is not a no-reply address (address withheld)" ;;
    esac

    # The whole message, subject and body and trailers alike, so a
    # Co-authored-by line with a personal address is caught like any other.
    # grep exits 1 when the message holds no address at all, which is the good
    # case and must not trip pipefail.
    message_lines="$(printf '%s\n' "$message" \
      | { grep -noE "$address_pattern" || true; } \
      | awk -F: 'index($NF, "noreply") == 0 { print $1 }')"
    if [ -n "$message_lines" ]; then
      fail "✗ commit $commit: email address in the commit message, at message line(s) $(printf '%s' "$message_lines" | tr '\n' ' ')"
    fi

    status=0
    hits="$(addresses_in_tree "$inherited" "$commit")" || status=$?
    if [ "$status" -ne 0 ]; then
      fail "✗ git grep failed while searching the tree of commit $commit"
    elif [ -n "$hits" ]; then
      fail "✗ email address in a file at commit $commit (commit, file and line only, address withheld):" \
           "$(printf '%s\n' "$hits" | sed 's/^/    /')"
    fi
  done <<< "$commits"
}

check_working_tree
check_next_commit_identity author GIT_AUTHOR_IDENT
check_next_commit_identity committer GIT_COMMITTER_IDENT
check_fork_commits

if [ "$failures" -ne 0 ]; then
  exit 1
fi
