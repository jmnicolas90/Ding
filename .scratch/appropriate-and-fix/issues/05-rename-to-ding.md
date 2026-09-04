# 05 — Rename the app to Ding

Type: task
Status: open
Blocked by: 03

## Question

Mechanical, wide, and cheap exactly once — there is no divergence from upstream yet and the gate from ticket 03 will catch breakage. It gets more annoying with every commit that lands first.

`applicationId` and `namespace` both become `app.ding`, matching the `app.loquace` house style.

Surface, from the survey done while charting:

- `app/build.gradle`: `applicationId "felixwiemuth.simplereminder.dev"`, `namespace 'felixwiemuth.simplereminder'`, `archivesBaseName "SimpleReminder_" + versionName`
- The Java/Kotlin package `felixwiemuth.simplereminder.*` → `app.ding.*`, across ~60 source files and the `app/src/main/java/` directory tree. `app/src/test/java/` too.
- `app_name` in `values/strings.xml`, plus roughly a dozen user-visible strings that spell out "SimpleReminder" — welcome messages, battery-optimization advice, the ACRA prompt (which ticket 06 deletes outright; coordinate so the work is not done twice).
- `metadata/en-US/title.txt` and `metadata/de/title.txt`.
- The GitHub repository name, the `origin` remote URL, and this working directory.

Keep the GPL copyright headers exactly as they are. They are Felix Wiemuth's and stay — renaming the app does not change who wrote the code.

The `.dev` suffix on the old `applicationId` came from upstream's release-channel scheme in `app/build.gradle`. Decide whether Ding keeps that scheme or drops it for a plain `app.ding`; the version-code arithmetic above it is built around the channel digit, so dropping it means simplifying that too.

Note (added when the fork collapsed to a single trunk): half of that scheme is already gone. Upstream encoded the channel in the *branch* — `develop` built `.dev`, `main` built the production ID — and there is only one branch now. So the choice is no longer "keep upstream's scheme"; it is whether Ding needs more than one build identity at all, and if it does, that has to become a build type or product flavour rather than a branch.

**Done when** the gate is green, `grep -ri simplereminder` returns only GPL headers and deliberate historical references, and a debug APK installs as Ding.
