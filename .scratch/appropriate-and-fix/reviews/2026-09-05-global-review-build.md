# Global Codex review, 2026-09-05 — Build, gate, identity and attribution

Adversarial review by Codex (GPT-5.4) of the whole diff from the fork commit `d34bf2f` to the tip of `main` on 2026-09-05, before the history rewrite of the same day. Job `review-mto40gbh-62skv6`. One of three reviews run in parallel, each with its own axis; the other two are in this directory.

**Verdict:** needs-attention

**Summary:** No-ship: the published history already violates the email constraint, both enforcement guards have material blind spots, and G6 produces an unsigned artifact. Attribution and gate atomicity also remain incomplete.

## Findings

### 1. [high] G1 passes despite personal addresses in published history

File: `scripts/check-no-personal-email.sh`

The guard searches only the current text working tree. It does not inspect the staged index, commit metadata, trailers, historical blobs, tracked binaries, tags, or built artifacts. `scripts/check-no-personal-email.sh` exits 0, while `git log d34bf2f..HEAD --format='%h %ae %ce' | grep -v noreply` finds 18 prohibited author/committer fields across nine commits. Historical trees also retain addresses in ticket files and Main.kt before their later redaction. Merge 82cc632 pulled upstream main-only ancestry into the purported d34bf2f hard fork, causing part of this exposure.

Recommendation: Rebuild/rewrite the branch from d34bf2f to remove prohibited metadata and historical blobs. Extend G1 to inspect the staged index, the complete reviewed history and trailers, binaries, tags, and post-build APK contents; configure CI checkout with full required history.

### 2. [high] The Google-dependency guard does not enforce “any configuration”

File: `app/build.gradle`

Only Android variant runtime configurations are collected, and traversal recognizes only ModuleComponentIdentifier nodes. Buildscript/plugin classpaths, lint, annotation processors, unit/instrumentation tests, and other resolvable configurations are unguarded. File/project dependencies are also invisible: adding `implementation files('libs/play-services-base.jar')` can package prohibited code while checkNoGoogleDependencies remains green. The current APK appears clean, but the claimed permanent constraint is bypassable.

Recommendation: Enumerate every resolvable configuration across all projects, including buildscript/plugin and test/tooling configurations, fail if any cannot be resolved, and inspect resolved artifacts/files as well as Maven component coordinates. Add negative tests for testImplementation, plugin/buildscript, variant-only, project, and local-file dependencies.

### 3. [high] G6 produces an unsigned, non-shippable release APK

File: `app/build.gradle`

The release build type has no signing configuration, and G6 checks only that assembleRelease succeeds. The resulting file is `*-release-unsigned.apk`; `$ANDROID_HOME/build-tools/36.0.0/apksigner verify app/build/outputs/apk/release/*-unsigned.apk` exits nonzero, while the debug APK verifies. Uploading the artifact produced by the advertised release gate would give users an APK Android cannot install or update from.

Recommendation: Define a secure external signing/release step, verify the final signed APK with apksigner, validate its certificate identity, and make the shipping gate operate on that artifact rather than the unsigned assembleRelease output.

### 4. [medium] The committed licence inventory omits shipped libraries

File: `LICENSE.md`

The release graph and R8 mapping contain Kotlin stdlib and kotlinx.coroutines classes, but the Included libraries table lists neither; it only names ckChangeLog, Material, AndroidX, and kotlinx.serialization. Reproduce with `./gradlew :app:dependencies --configuration releaseRuntimeClasspath | rg 'kotlin-stdlib|kotlinx-coroutines'` followed by `rg 'kotlin-stdlib|kotlinx-coroutines' LICENSE.md`. The generator faithfully renders this incomplete source, so regeneration cannot correct the attribution.

Recommendation: Inventory the resolved release graph, add Kotlin stdlib and kotlinx.coroutines with their applicable notices, and add a gate that compares declared licence coverage with the resolved shipping dependencies.

### 5. [medium] Licence publication is not atomic on final-copy failure

File: `scripts/generate-open-source-licenses.sh`

When LICENSES changes, the script moves the old asset directory into the temporary stage and installs the new directory before staging/publishing the HTML page. If the page copy or final move fails—for example from exhausted storage or a permissions race—the EXIT trap deletes the saved old directory, leaving new licence texts paired with the old page. This contradicts ticket 08's failure-atomic guarantee.

Recommendation: Keep rollback state until every publication succeeds and restore the previous LICENSES directory on any later failure, or stage and transactionally replace the complete assets set as one unit. Add an injected failure test after the LICENSES swap.

### 6. [medium] Local and CI gates do not run the same seven stages

File: `.github/workflows/ci.yml`

The local gate runs G0 before G1 and explicitly verifies `platforms/android-36`. CI starts with G1, has no G0 or explicit installation of that SDK package, and relies on mutable `ubuntu-latest` contents. Conversely, CI selects Temurin 21 while the local gate never verifies Java 21. The Gradle task order matches, but the documented stage/toolchain equivalence does not; version skew can make one environment reject a tree the other accepted.

Recommendation: Provision/pin JDK 21 and `platforms;android-36` in CI, then invoke one shared gate implementation in both environments; add a Java-version preflight locally so the same stages and toolchain constraints are enforced.

## Briefing given to the reviewer

```
Global adversarial review of everything Ding changed since it forked from felixwiemuth/SimpleReminder at d34bf2f (2025-10-20). Axis 2 of 3: build, gate, identity, hard constraints and attribution. Tickets 01 to 08 in .scratch/appropriate-and-fix/issues/ did this work one at a time; this review checks the result as a whole.

Spec: CLAUDE.md (hard constraints, the gate, working conventions), .scratch/appropriate-and-fix/map.md (settled decisions), CONTRIBUTING.md.

Scope: app/build.gradle, build.gradle, settings.gradle, gradle.properties, gradle/, app/proguard-rules.pro and any other R8 rules, app/src/main/AndroidManifest.xml, app/src/main/res/xml/*, app/src/main/res/values/*, app/src/main/res/raw/*, app/src/main/assets/*, scripts/*, .github/*, .gitignore, LICENSE.md, LICENSES/, CONTRIBUTORS.md, README.md, CONTRIBUTING.md, metadata/*, and the git history itself (57 commits since d34bf2f).

Hard constraints to verify, not trust: (1) GrapheneOS and AOSP compatible, no com.google.android.gms, com.google.firebase or play-services module in any configuration; com.google.android.material is allowed. (2) minSdk 31, compileSdk 36, targetSdk still 34 by decision until ticket 17. (3) No personal email address in any tracked file, in any commit author, committer or trailer, or in any built artifact; the GitHub no-reply address and GPL attribution are the only allowed addresses. (4) The gate scripts/check.sh and .github/workflows/ci.yml run the same tasks in the same order.

Decisions fixed (do not re-litigate): hard fork, never merging upstream again; single trunk main; applicationId and namespace app.ding with one build identity for every build type and version code 91500; ACRA removed with no replacement; GitHub issues as the only contact channel; GPL headers, CONTRIBUTORS.md and historical changelogs keep the SimpleReminder name deliberately; the licence page is generated from LICENSE.md by scripts/generate-open-source-licenses.sh with pandoc 3.11.

Look hard for:
- Ways past checkNoGoogleDependencies: configurations it does not walk (buildscript classpath, plugin dependencies, lint, annotation processors, test configurations), a dependency that only resolves for one variant, or a guard that silently passes when a configuration cannot be resolved.
- Ways past scripts/check-no-personal-email.sh: tracked binary files, encoded or obfuscated addresses, addresses in .idea or gradle caches that are tracked, addresses in metadata, and every commit since d34bf2f (author, committer, Co-authored-by trailers, message body).
- Drift between check.sh and ci.yml: task names, order, JDK, SDK package, fail-fast behaviour, and whether CI can be green while check.sh is red or the reverse.
- Rename leftovers that matter at runtime: felixwiemuth or simplereminder in the manifest, provider authorities, preference file names, intent actions, notification channel ids, proguard rules, resource names, deep links, backup rules, widget or tile metadata.
- R8 on the release build: kotlinx.serialization and any reflection-based access surviving minification, keep rules that are missing or too broad, and whether the release APK actually differs from debug in a way the gate would not catch.
- minSdk 31 cleanup that deleted a check the platform still needs on 31 to 35, and targetSdk 34 behaviours that are already wrong on Android 14 and 15 devices.
- shrinkResources set on debug but not release, and any other build-type asymmetry that makes the shipped APK the less tested one.
- GPL compliance: every file upstream never had carries the fork's header; every inherited header is untouched; LICENSE.md and the generated licences page list exactly the dependencies in the graph, no more and no fewer; the assets page is in sync with LICENSE.md right now.
- Store metadata under metadata/ that would publish something wrong or upstream's under the Ding name.
- CI workflow permissions, pinned actions, secrets exposure, and what happens on a fork's pull request.

Non-goals: reminder state and alarm behaviour (review axis 1); UI, settings and documentation prose (axis 3); feature work; F-Droid submission.

Report each finding with file and line, how to reproduce it (a command where possible), and its severity against the hard constraints above.
```
