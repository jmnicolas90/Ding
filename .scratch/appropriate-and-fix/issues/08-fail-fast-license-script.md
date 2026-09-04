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
script does not: it keeps the section, with one deterministic rewrite. Those
paragraphs say where the full licence texts live and that the attributions are
complete for the fork but not necessarily for the original project, which
belongs on a licences page rather than being editorially removed from one. That
is the only content change to the page.

The one rewrite is a link. The first restored paragraph ends "can be found
under [LICENSES](LICENSES)", and in the app the page is loaded from
`file:///android_asset/`, where `LICENSES` is a directory with no document in
it, so that link goes nowhere. The script turns any markdown link whose target
is exactly the `LICENSES` directory into the plain text it was linking, so the
sentence still reads correctly and is not a dead link. Per-file links
(`LICENSES/APACHE-2.0`, `LICENSES/GPL3`, `LICENSES/MIT`) are real documents and
are left alone. This is done in the script, on the extracted markdown;
`LICENSE.md` is not edited, because on GitHub that link works and is worth
keeping.

### The script

Moved with `git mv` from the repo root to `scripts/generate-open-source-licenses.sh`,
where the other scripts live, and rewritten: `#!/usr/bin/env bash`,
`set -euo pipefail`, the fork's GPL header (the file had no upstream header to
keep), a tool check up front, a pandoc version check, input checks on
`LICENSE.md` and the section heading, a refusal to publish a section
containing no table body row, all work in a `mktemp -d` directory removed by
`trap`, and the assets replaced only after every step has succeeded. It
resolves the repo root from `BASH_SOURCE`, so it runs from anywhere.

Publication is atomic. There are two throwaway directories, both removed by
one `trap`: a `mktemp -d` build directory for the intermediate markdown and
html, and a uniquely named staging directory created inside
`app/src/main/assets/`. The staging directory has to be a sibling of the
assets because it is the same filesystem, which is what makes the final
publication a single rename that either happened or did not. `LICENSES/` is
compared with `diff -r` first and left alone when it already matches, which is
every run in practice; when it does differ the replacement is built in the
staging directory and swapped in, never deleted and recopied in place. The
page goes last, as the final step, and nothing that can fail follows it.

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

That version is pinned in the script, in one variable at the top, and checked
against `pandoc --version | head -1` before any work. Pandoc versions render
the same markdown differently — whitespace, the structural CSS partial, the
zebra row classes, the computed column widths — so an unpinned pandoc would
make the committed page depend on whichever binary happened to be first on
`PATH`, and two people regenerating the same `LICENSE.md` would get two
different pages. On a mismatch the script prints the expected version, the
version and path it found, and the same install guidance, naming 3.11.
Bumping the pin is a deliberate act, and the page is expected to change with
it.

### The page was regenerated for real

The diff against the hand-edited page from ticket 06 is 148 lines and falls
into three groups:

- **Content, deliberate:** the five restored paragraphs described above. The
  review pass then took one line off that group: the dead `LICENSES` directory
  link in the first of them is now plain text.
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
files already matched `LICENSES/` blob for blob, mode included. Since the
review pass the script notices that itself, with `diff -r`, and does not touch
the directory at all when it matches.

### Evidence for the two done-when conditions

**1. Running it twice leaves the tree unchanged.** Two consecutive runs, with
`git status --porcelain` captured after each:

```
$ scripts/generate-open-source-licenses.sh
generate-open-source-licenses: wrote .../app/src/main/assets/open_source_licenses.html (LICENSES already up to date)
$ git status --porcelain > /tmp/status1
$ scripts/generate-open-source-licenses.sh
generate-open-source-licenses: wrote .../app/src/main/assets/open_source_licenses.html (LICENSES already up to date)
$ git status --porcelain > /tmp/status2
$ diff /tmp/status1 /tmp/status2 && echo IDENTICAL
IDENTICAL
```

Both statuses read exactly:

```
 M .scratch/appropriate-and-fix/issues/08-fail-fast-license-script.md
 M CLAUDE.md
 M app/src/main/assets/open_source_licenses.html
 M scripts/generate-open-source-licenses.sh
```

— this pass's own changes, unchanged by the second run. The second run added
nothing, and left no staging directory behind in `app/src/main/assets/`.

**2. No pandoc on `PATH` exits non-zero with a useful message.**

```
$ env PATH=/usr/bin:/bin scripts/generate-open-source-licenses.sh
generate-open-source-licenses: missing required tool(s): pandoc

  pandoc renders the markdown to HTML, and this script is pinned to pandoc
  3.11. There is no sudo on this machine, so install that exact version
  user-locally from the official release tarball:

    ver=3.11
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
with a stub `pandoc` on `PATH` that answers `--version` with `pandoc 3.11` so
it gets past the version pin, and then exits 3 on the render, so the script
gets as far as writing the extracted markdown into its temporary directory and
creating its staging directory and then dies:

```
$ env PATH=/tmp/pandoc-stub:/usr/bin:/bin scripts/generate-open-source-licenses.sh
pandoc: simulated failure
$ echo $?
3
```

`sha256sum` of the page, `LICENSE.md` and all three asset licence texts, before
and after the failed run:

```
5a1270262c5a5eee14ea112b4dedcb3640781e52fd531de7cefad6516fad934e  app/src/main/assets/open_source_licenses.html
d15448bc9cc01e49ac6db9611570aa4abc3cace4677c7850070630bb94b34d6d  LICENSE.md
cfc7749b96f63bd31c3c42b5c471bf756814053e847c10f3eb003417bc523d30  app/src/main/assets/LICENSES/APACHE-2.0
ca372a7d92560b1fa9f6d832b440e8bcd62d9adfa8870c98287deab66d98310e  app/src/main/assets/LICENSES/GPL3
fd80a26fbb3f644af1fa994134446702932968519797227e07a1368dea80f0bc  app/src/main/assets/LICENSES/MIT
```

Identical both times. Nothing was left beside the page — no `.tmp` file, no
staging directory, 0 dotfiles in `app/src/main/assets/` — and the count of
`/tmp/tmp.*` directories was 0 before and 0 after, so the one `trap` cleaned
up both of its directories.

## Review findings (2026-09-05)

- **(high) Publication was not atomic** — the page was moved into place before
  `LICENSES/` was deleted and recopied, and the fixed `$PAGE.tmp` staging path
  lived outside the trapped directory. Fixed: two throwaway directories, both
  removed by the one `trap`, a `diff -r` check that leaves an already-identical
  `LICENSES/` alone and otherwise swaps in a staged copy, and the page
  published last in a single same-filesystem rename. Re-checked with the stub
  pandoc that exits mid-run: assets byte-identical, no residue.
- **(medium) The empty-attribution guard passed on a table with no rows** —
  `grep -q '^|'` matched the header and separator lines. Fixed: the markdown
  must contain a `|` line that comes after the separator and is not itself a
  separator, and the generated html must contain at least one `<td>`. Tested
  with a header-and-separator-only fixture through the real extraction step: it
  fails with exit 1 and writes nothing.
- **(medium) Output depended on whichever pandoc was first on `PATH`** — no
  version check. Fixed: pinned to `3.11` in one variable at the top, compared
  against `pandoc --version | head -1`, and a mismatch fails with the expected
  version, the version and path found, and the install guidance naming 3.11.
- **(medium) The restored paragraph linked to the `LICENSES` directory** —
  which in the app's WebView resolves to `file:///android_asset/LICENSES`, a
  directory with no document. Fixed in the script, not in `LICENSE.md`: after
  extraction, any markdown link whose target is exactly `LICENSES` (with or
  without a trailing slash) becomes plain text. Per-file links stay links; the
  regenerated page has no `href` pointing at the bare directory.

Disposition: all four fixed.
