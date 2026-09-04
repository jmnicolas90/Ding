# 08 — Make the licence generator fail fast

Type: task
Status: open
Blocked by: —

## Question

`generateOpenSourceLicensesFile.sh` has no shebang and no `set -eu`. Review finding, confirmed: `OPEN_SOURCE_LICENSES.md.patch` no longer applies to `LICENSE.md`; the script prints the failure and continues. With `pandoc` also absent it still reaches the final `cp` and **exits 0**, so automation can publish a stale `open_source_licenses.html` while reporting success.

Fix:

- Add `#!/usr/bin/env bash` and `set -euo pipefail`.
- Validate required tools (`pandoc`, `patch`) up front with a clear message naming what to install.
- Work in a temporary directory cleaned up via `trap`, and replace `app/src/main/assets/open_source_licenses.html` only after every step has succeeded.
- Repair or regenerate the patch so it applies to the current `LICENSE.md`.

Consider whether the patch step should exist at all — a patch that has to be hand-maintained against a file that changes is a recurring failure. Generating the HTML directly from `LICENSE.md` may be simpler than keeping the patch alive.

Independent of everything else on this map, and small. Good first ticket for a session with little context.

**Done when** running the script twice in a row leaves the tree unchanged, and removing `pandoc` from `PATH` makes it exit non-zero with a useful message.
