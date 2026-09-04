# 08 — Make the licence generator fail fast

Type: task
Status: resolved
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

## Resolution (2026-09-05)

### The patch is gone

`OPEN_SOURCE_LICENSES.md.patch` is deleted, and with it the `git apply` step.
Reading the patch, everything it encoded was one contiguous extraction: take
`LICENSE.md` from the `## Included work ##` heading to the end of the file and
retitle that heading `## Open source licenses ##` so the page heading matches
the "Open source licenses" link in `about.html` that opens it. Everything above
that heading is about this project's own licence, not about third-party work,
which is why it was cut.

That is now one `awk` range in the script, read out of `LICENSE.md` at run
time, so it cannot go stale. The patch could and did: it went stale twice
(ticket 06 found two typo fixes and a moved blank line that had never been
carried across), and it was destructive besides — its header renamed
`LICENSE.md` to `OPEN_SOURCE_LICENSES.md`, so a successful `git apply` *deleted
`LICENSE.md`*. A hand-maintained diff against a file that changes was exactly
the recurring failure the ticket suspected it was.

The patch also stripped five explanatory paragraphs out of the section. The
script does not: it keeps the section verbatim. Those paragraphs say where the
full licence texts live and that the attributions are complete for the fork but
not necessarily for the original project, which belongs on a licences page
rather than being editorially removed from one. That is the only content change
to the page.

### The script

Moved with `git mv` from the repo root to `scripts/generate-open-source-licenses.sh`,
where the other scripts live, and rewritten: `#!/usr/bin/env bash`,
`set -euo pipefail`, the fork's GPL header (the file had no upstream header to
keep), a tool check up front, input checks on `LICENSE.md` and the section
heading, a refusal to publish a section containing no table rows, all work in a
`mktemp -d` directory removed by `trap`, and the assets replaced only after
every step has succeeded. It resolves the repo root from `BASH_SOURCE`, so it
runs from anywhere.

`pandoc` is called with `--variable document-css=`. Pandoc 3.x ships a default
stylesheet with 50px of body padding and a hardcoded light background and text
colour; the page is shown in a small dialog `WebView`
(`HtmlDialogFragment`), where that would both crowd the dialog and override the
app's theme. Blanking the variable keeps only pandoc's small structural rules,
which is what the previous page had.

The only reference to the old name outside the historical records was a comment
in `scripts/check-no-personal-email.sh`; it is updated. `CLAUDE.md` gains a
short paragraph saying the script exists, what it regenerates and that nothing
runs it for you, because moving it out of the repo root made it easy to miss.
The 02-09-2026 code review and ticket 06 still name the old path on purpose:
they are dated records of what was true when they were written.

### pandoc

pandoc **3.11**, the latest release, installed user-locally with no sudo:
`pandoc-3.11-linux-amd64.tar.gz` from
`https://github.com/jgm/pandoc/releases/download/3.11/`, unpacked and
`install -m 0755 pandoc-3.11/bin/pandoc ~/.local/bin/pandoc`. `~/.local/bin` is
already on `PATH`. This is the route the script's error message tells you to
take when pandoc is missing.

### The page was regenerated for real

The diff against the hand-edited page from ticket 06 is 148 lines and falls
into three groups:

- **Content, deliberate:** the five restored paragraphs described above.
- **Title:** `<title>` was `OPEN_SOURCE_LICENSES`, the name of the temporary
  file the old script happened to use. It is now `Open source licenses`, set
  explicitly with `--metadata pagetitle`.
- **Pandoc 3.11 versus the 2.x that produced the old file, all cosmetic:**
  `<meta name="generator">` now carries the version, `lang="" xml:lang=""` is
  gone from `<html>`, the structural CSS partial is 3.11's, the
  `<tr class="odd|even|header">` zebra classes are gone (3.x stopped emitting
  them), output is soft-wrapped at column 72, and the `<colgroup>` widths are
  recomputed — the first table gains one because 3.x measures the header row
  too. No row, link, copyright or licence text changed.

`app/src/main/assets/LICENSES/` is byte-identical after regeneration; its three
files already matched `LICENSES/` blob for blob, mode included.

### Evidence for the two done-when conditions

**1. Running it twice leaves the tree unchanged.** Two consecutive runs, with
`git status --porcelain` captured after each:

```
$ scripts/generate-open-source-licenses.sh
generate-open-source-licenses: wrote .../app/src/main/assets/open_source_licenses.html and .../app/src/main/assets/LICENSES
$ git status --porcelain > /tmp/status1
$ scripts/generate-open-source-licenses.sh
generate-open-source-licenses: wrote .../app/src/main/assets/open_source_licenses.html and .../app/src/main/assets/LICENSES
$ git status --porcelain > /tmp/status2
$ diff /tmp/status1 /tmp/status2 && echo IDENTICAL
IDENTICAL
```

Both statuses read exactly:

```
D  OPEN_SOURCE_LICENSES.md.patch
 M app/src/main/assets/open_source_licenses.html
RM generateOpenSourceLicensesFile.sh -> scripts/generate-open-source-licenses.sh
```

— the ticket's own staged changes, unchanged by the second run. The second run
added nothing.

**2. No pandoc on `PATH` exits non-zero with a useful message.**

```
$ env PATH=/usr/bin:/bin scripts/generate-open-source-licenses.sh
generate-open-source-licenses: missing required tool(s): pandoc

  pandoc renders the markdown to HTML. There is no sudo on this machine, so
  install it user-locally from the official release tarball:

    ver=3.11   # latest at https://github.com/jgm/pandoc/releases
    curl -sL -o /tmp/pandoc.tar.gz \
      "https://github.com/jgm/pandoc/releases/download/$ver/pandoc-$ver-linux-amd64.tar.gz"
    tar xzf /tmp/pandoc.tar.gz -C /tmp
    mkdir -p ~/.local/bin
    install -m 0755 "/tmp/pandoc-$ver/bin/pandoc" ~/.local/bin/pandoc

  ~/.local/bin is already on PATH; check with "pandoc --version".

$ echo $?
1
```

**3. A failure part-way leaves the tree untouched.** The check above fails
before any work; the interesting case is a failure *after* validation. Tested
with a stub `pandoc` on `PATH` that exits 3, so the script gets as far as
writing the extracted markdown into its temporary directory and then dies:

```
$ env PATH=/tmp/pandoc-stub:/usr/bin:/bin scripts/generate-open-source-licenses.sh
pandoc: simulated failure
$ echo $?
3
```

`sha256sum` of `app/src/main/assets/open_source_licenses.html` and `LICENSE.md`
before and after the failed run:

```
c99c9df27bd61714073faf19464a922c6a2b28a3bc4e9aaa758ea3d23c614857  app/src/main/assets/open_source_licenses.html
d15448bc9cc01e49ac6db9611570aa4abc3cace4677c7850070630bb94b34d6d  LICENSE.md
```

Identical both times. No stray `open_source_licenses.html.tmp` was left beside
the page, and the count of `/tmp/tmp.*` directories was 0 before and 0 after,
so the `trap` cleaned up.
