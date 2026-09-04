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

# Fails when an email address appears in a tracked file.
#
# CLAUDE.md's constraint is "no personal email address anywhere". This is the
# enforced half of it, the way checkNoGoogleDependencies enforces the no-Google
# rule rather than leaving it to good intentions. The repo is public, so an
# address that lands in a tracked file is published to harvesters the moment it
# is pushed, and taking it back means rewriting history.
#
# Both scripts/check.sh (stage G1) and .github/workflows/ci.yml run this one
# file, so the pattern and the allowlist below exist in a single place and the
# two gates cannot drift apart on them.
#
# Allowed, and why:
#   - LICENSE.md, LICENSES/, CONTRIBUTORS.md — GPL attribution. None of them
#     carries an address today; they are allowed anyway because attribution is
#     the one case where an address would be legitimate rather than a leak.
#   - app/src/main/assets/open_source_licenses.html — generated from LICENSE.md
#     by generateOpenSourceLicensesFile.sh, so it can only ever carry what
#     LICENSE.md already carries.
#   - any address containing "noreply" — the GitHub and vendor no-reply
#     addresses that commits and co-author trailers are signed with.
# The GPL copyright headers are deliberately NOT allowlisted: they carry names,
# not addresses. If one ever gains an address, the header is the thing to fix.
#
# It reports the file and the line number and never prints the address itself,
# so a failing gate does not republish what it just caught.
set -euo pipefail

cd "$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# git grep searches tracked files only, which is the same set as `git ls-files`;
# -I skips binaries so an APK-shaped blob cannot produce a false match.
set +e
raw="$(git grep -nIE '[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[a-z]{2,}' -- \
         ':!LICENSE.md' \
         ':!LICENSES/' \
         ':!CONTRIBUTORS.md' \
         ':!app/src/main/assets/open_source_licenses.html')"
status=$?
set -e
# git grep exits 1 for "no matches", which is the good case here. Anything above
# that is a real failure and must not pass for a clean tree.
if [ "$status" -gt 1 ]; then
  echo "✗ git grep failed (exit $status)" >&2
  exit 1
fi

hits="$(printf '%s\n' "$raw" | grep -v noreply | cut -d: -f1,2 || true)"

if [ -n "$hits" ]; then
  echo "✗ email address in tracked files (file and line only, address withheld):" >&2
  echo "$hits" | sed 's/^/    /' >&2
  echo "  If it is attribution the licence requires, it belongs in LICENSE.md," >&2
  echo "  LICENSES/ or CONTRIBUTORS.md. Otherwise remove it." >&2
  exit 1
fi
