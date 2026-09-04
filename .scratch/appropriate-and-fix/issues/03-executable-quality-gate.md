# 03 — Build the executable quality gate, locally and in CI

Type: task
Status: resolved
Blocked by: 02

## Question

This is the safety net for an AI-developed app: the thing that converts "the agent says it's fixed" into "the gate says it's fixed". Right now there is no CI at all.

Two halves, mirroring `../Loquace/scripts/check.sh`:

**Local pre-commit gate — `scripts/check.sh`.** `set -euo pipefail`, fail-fast, one command that runs everything. Follow Loquace's hard-won detail: every command in a subshell needs an explicit `|| exit 1`, because `set -e` does not apply on the left of `||` and the gate would report green with lint red.

**GitHub Actions — `.github/workflows/ci.yml`.** Push and pull-request triggers. `actions/setup-java@v4` with Temurin 21, `gradle/actions/setup-gradle@v4`, then lint, unit tests, `assembleDebug` and `assembleRelease`. Release is worth its own step: R8 and resource shrinking only run there, so breakage is invisible to the debug gate. GitHub's `ubuntu-latest` image ships the Android SDK, so no bootstrap is needed — keep the workflow file portable in case it ever has to move to the Forgejo runner.

**The Google-dependency guard.** The one check that protects the GrapheneOS constraint from a future agent innocently adding a dependency that drags in Play Services. Resolve the runtime classpath and fail if any artifact matches `com.google.android.gms`, `com.google.firebase`, or `play-services`. Note the trap: `com.google.android.material` is AndroidX Material Components and must **not** trip the guard — match on group coordinates, not on the string "google".

Lint currently emits 33 warnings alongside the (now-fixed) error. Decide whether the gate fails on warnings; recommendation is no for now, so the gate goes green immediately rather than requiring a cleanup campaign first.

**Done when** `scripts/check.sh` exits 0 locally, CI is green on a pushed branch, and a deliberately added `play-services-base` dependency makes both go red.

## Outcome

Done. `scripts/check.sh` runs six stages — G0 preflight, G1 lint, G2 unit tests, G3 Google
guard, G4 debug APK, G5 release APK — and exits 0. `.github/workflows/ci.yml` runs the same
stages in the same order and is green on this branch. Adding `play-services-base` turns both
red at G3, naming the transitive `play-services-basement` and `-tasks` it drags in behind it;
`com.google.android.material` does not trip the guard.

The gate does **not** fail on warnings, as recommended. Recorded as an explicit `lint` block in
`app/build.gradle` rather than left to AGP's defaults, so the decision is visible where someone
would go to change it.

Corrections to the ticket's expectations:

- **The lint warning count is 23, not 33.** Ticket 02 deleted about ten of them along with the
  pre-31 branches.
- **The release-stage rationale was backwards.** The ticket said R8 and resource shrinking only
  run in release. In this project *both* build types set `minifyEnabled` with the same
  `proguardFiles`, so the debug stage already exercises the keep rules; only R8's optimization
  and obfuscation passes, which a debuggable build skips, are genuinely release-only. The stage
  still earns its place, on the narrower claim.
- **`shrinkResources` is set on debug and not on release**, so resource shrinking never runs on
  the shipping APK. Found while checking the claim above. A build-configuration decision, not a
  gate defect — parked under *Not yet specified*.
- **Lint runs on both variants**, not just debug. The ticket's own argument for a separate
  release APK stage applies to lint, and `:app:lintRelease` passes today.

The guard takes its classpaths from the AGP variant API rather than a hard-coded pair, so a
build type or flavour added later is guarded the day it appears, and an empty classpath list
fails loudly instead of passing vacuously.

Left for ticket 07: the two new files carry no GPL header. `CONTRIBUTING.md` asks for one on
every new file, but every existing `.sh` and `.yml` in the repo lacks it, and the honest
copyright line for fork-authored files is an attribution question, not a gate question.
