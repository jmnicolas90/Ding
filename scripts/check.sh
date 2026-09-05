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

# Pre-commit gate: the one command that has to be green before committing.
#
# G0 preflight -> G1 email guard -> G2 lint -> G3 unit tests -> G4 Google guard
# -> G5 debug APK -> G6 release APK. Fail-fast: the first red gate stops the run.
#
# The stages mirror .github/workflows/ci.yml stage for stage, on purpose (the
# runners are noisier — no -q — but they run the same tasks in the same order).
# If the two ever drift, one of them is lying about whether the tree is good.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
GRADLE="$ROOT/gradlew"

# Run one gate. Everything after the label is the command to run, passed as
# separate words rather than a string, so nothing goes through a second round
# of word-splitting.
#
# The subshell is a single `cd ... && "$@"` chain on purpose. Loquace's gate
# script was bitten by the alternative: `set -e` does NOT apply inside a
# compound command on the left of `||`, so a subshell of several bare commands
# exits with the *last* one's status and reports a gate green with its first
# step red. Here there is only ever one command, so there is no status to lose.
gate() {
  local id="$1" label="$2"
  shift 2
  echo "── $id $label ──"
  if ! (cd "$ROOT" && "$@"); then
    echo "✗ GATE $id FAILED" >&2
    exit 1
  fi
  echo "✓ $id $label"
}

# G0 is the one gate that is not a Gradle task, so it is spelled out rather than
# run through `gate`. It is several commands, which is exactly the case the
# comment above warns about — hence the explicit `|| exit 1` on each one.
preflight() {
  echo "── G0 preflight ──"
  (
    # compileSdk = 36 needs platforms/android-36 specifically. Android Studio
    # installs android-36.1, a distinct package that does NOT substitute — so
    # the raw Gradle error reads as "platform missing" while an android-36.1
    # directory sits right there. Name the real fix instead.
    sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
    if [ -z "$sdk" ] && [ -f "$ROOT/local.properties" ]; then
      # tr strips the trailing CR a CRLF local.properties would leave behind.
      sdk="$(sed -n 's/^sdk\.dir=//p' "$ROOT/local.properties" | tail -1 | tr -d '\r')" || exit 1
    fi
    if [ -z "$sdk" ]; then
      echo "✗ no Android SDK: set ANDROID_HOME, or put sdk.dir=... in local.properties" >&2
      exit 1
    fi
    if [ ! -d "$sdk/platforms/android-36" ]; then
      echo "✗ missing \$ANDROID_HOME/platforms/android-36 (android-36.1 will NOT do)" >&2
      echo "  fix: \"$sdk/cmdline-tools/latest/bin/sdkmanager\" 'platforms;android-36'" >&2
      exit 1
    fi
  ) || { echo "✗ GATE G0 FAILED" >&2; exit 1; }
  echo "✓ G0 preflight"
}

preflight

# G1 is the other gate that is not a Gradle task. It runs here, before anything
# that starts a JVM, because it costs well under a second and because an address
# that reaches a public push cannot be taken back. It covers the tracked files,
# the identity the next commit would carry, and every commit this fork authored
# — metadata, message and tree — so it needs full history and fails on a shallow
# clone. The check itself lives in its own script, which the CI workflow runs
# too, so the pattern, the allowlist and the commit range have one home instead
# of two that drift.
gate G1 "email guard" "$ROOT/scripts/check-no-personal-email.sh"

# Both variants: the ticket's own argument for a separate release APK stage —
# that release-only breakage is invisible to a debug gate — applies to lint too.
# Errors only. The pre-existing warnings do not fail the gate; see the `lint`
# block in app/build.gradle for why.
gate G2 lint          "$GRADLE" -q :app:lintDebug :app:lintRelease
gate G3 "unit tests"  "$GRADLE" -q :app:testDebugUnitTest
# The GrapheneOS constraint, enforced rather than documented.
gate G4 "Google guard" "$GRADLE" -q :app:checkNoGoogleDependencies
gate G5 "debug APK"   "$GRADLE" -q :app:assembleDebug
# G6 is worth its own gate, though not for the reason the ticket gave. Both build
# types set minifyEnabled with the same proguardFiles, so G5 already exercises the
# keep rules; what the debug build skips, being debuggable, is R8's optimization
# and obfuscation passes. Breakage that only those passes can cause is invisible
# to every gate above this one.
#
# Note what neither gate covers: shrinkResources is set on debug and *not* on
# release, so resource shrinking never runs on the shipping APK. That is a build
# configuration bug, not a gate bug — see the ticket 03 notes.
gate G6 "release APK" "$GRADLE" -q :app:assembleRelease

echo "─────────────────────────────"
echo "All gates green."
