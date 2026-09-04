# 03 — Build the executable quality gate, locally and in CI

Type: task
Status: open
Blocked by: 02

## Question

This is the safety net for an AI-developed app: the thing that converts "the agent says it's fixed" into "the gate says it's fixed". Right now there is no CI at all.

Two halves, mirroring `../Loquace/scripts/check.sh`:

**Local pre-commit gate — `scripts/check.sh`.** `set -euo pipefail`, fail-fast, one command that runs everything. Follow Loquace's hard-won detail: every command in a subshell needs an explicit `|| exit 1`, because `set -e` does not apply on the left of `||` and the gate would report green with lint red.

**GitHub Actions — `.github/workflows/ci.yml`.** Push and pull-request triggers. `actions/setup-java@v4` with Temurin 21, `gradle/actions/setup-gradle@v4`, then lint, unit tests, `assembleDebug` and `assembleRelease`. Release is worth its own step: R8 and resource shrinking only run there, so breakage is invisible to the debug gate. GitHub's `ubuntu-latest` image ships the Android SDK, so no bootstrap is needed — keep the workflow file portable in case it ever has to move to the Forgejo runner.

**The Google-dependency guard.** The one check that protects the GrapheneOS constraint from a future agent innocently adding a dependency that drags in Play Services. Resolve the runtime classpath and fail if any artifact matches `com.google.android.gms`, `com.google.firebase`, or `play-services`. Note the trap: `com.google.android.material` is AndroidX Material Components and must **not** trip the guard — match on group coordinates, not on the string "google".

Lint currently emits 33 warnings alongside the (now-fixed) error. Decide whether the gate fails on warnings; recommendation is no for now, so the gate goes green immediately rather than requiring a cleanup campaign first.

**Done when** `scripts/check.sh` exits 0 locally, CI is green on a pushed branch, and a deliberately added `play-services-base` dependency makes both go red.
