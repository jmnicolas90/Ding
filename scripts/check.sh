#!/usr/bin/env bash
# Pre-commit gate: the one command that has to be green before committing.
#
# G0 preflight -> G1 lint -> G2 unit tests -> G3 Google guard -> G4 debug APK
# -> G5 release APK. Fail-fast: the first red gate stops the run.
#
# The stages mirror .github/workflows/ci.yml step for step, on purpose. If the
# two ever drift, one of them is lying about whether the tree is good.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
GRADLE="$ROOT/gradlew"

fail() { echo "✗ GATE $1 FAILED" >&2; exit 1; }

# NOTE: every command inside the subshells below is explicitly `|| exit 1`.
# `set -e` does NOT apply inside a compound command on the left of `||`, so a
# bare command there would fail silently and the subshell would exit with the
# *last* command's status — reporting a gate green with its first step red.

echo "── G0 preflight ─────────────────────────────"
(
  # compileSdk = 36 needs platforms/android-36 specifically. Android Studio
  # installs android-36.1, a distinct package that does NOT substitute — so the
  # raw Gradle error reads as "platform missing" while an android-36.1
  # directory sits right there. Name the real fix instead.
  sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
  if [ -z "$sdk" ] && [ -f "$ROOT/local.properties" ]; then
    sdk="$(sed -n 's/^sdk\.dir=//p' "$ROOT/local.properties" | tail -1)"
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
) || fail G0
echo "✓ G0 preflight"

echo "── G1 lint ──────────────────────────────────"
# Errors only. The pre-existing warnings do not fail the gate — see the `lint`
# block in app/build.gradle for why.
(
  cd "$ROOT" || exit 1
  "$GRADLE" -q :app:lintDebug || exit 1
) || fail G1
echo "✓ G1 lint"

echo "── G2 unit tests ────────────────────────────"
(
  cd "$ROOT" || exit 1
  "$GRADLE" -q :app:testDebugUnitTest || exit 1
) || fail G2
echo "✓ G2 unit tests"

echo "── G3 Google guard ──────────────────────────"
# The GrapheneOS constraint, enforced rather than documented.
(
  cd "$ROOT" || exit 1
  "$GRADLE" -q :app:checkNoGoogleDependencies || exit 1
) || fail G3
echo "✓ G3 Google guard"

echo "── G4 debug APK ─────────────────────────────"
(
  cd "$ROOT" || exit 1
  "$GRADLE" -q :app:assembleDebug || exit 1
) || fail G4
echo "✓ G4 debug APK"

echo "── G5 release APK ───────────────────────────"
# Worth its own gate: the release build runs R8 for real (the debug build is
# debuggable, so its optimization and obfuscation passes are disabled even
# though minifyEnabled is on). A ProGuard rule that is missing or wrong is
# invisible to every gate above this one.
(
  cd "$ROOT" || exit 1
  "$GRADLE" -q :app:assembleRelease || exit 1
) || fail G5
echo "✓ G5 release APK"

echo "─────────────────────────────────────────────"
echo "All gates green."
