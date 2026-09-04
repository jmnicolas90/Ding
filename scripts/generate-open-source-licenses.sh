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

# Regenerates the in-app third-party licences page from LICENSE.md.
#
# The page shown at file:///android_asset/open_source_licenses.html (linked
# from about.html) is one section of LICENSE.md: everything from the
# "Included work" heading to the end of the file, retitled "Open source
# licenses" so the page heading matches the link that opens it. Everything
# above that heading is about this project's own licence, not about
# third-party work, so it does not belong on the page.
#
# That extraction is the whole transformation, which is why there is no
# patch file any more: a hand-maintained diff against a file that changes is
# a recurring failure, and it went stale twice before it was deleted. One
# awk range that reads LICENSE.md at run time cannot go stale.
#
# Publication is atomic. Every intermediate file lives either in the
# temporary build directory or in a uniquely named staging directory inside
# the assets directory, and both are removed by the same trap, so a failure
# part-way leaves LICENSE.md and the generated assets exactly as they were
# and leaves no leftovers beside them. The staging directory sits next to
# the assets on purpose: it is the same filesystem, so the final publication
# of the page is a single rename that either happened or did not. Run it
# from anywhere; it locates the repo itself.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

LICENSE_FILE="$ROOT/LICENSE.md"
LICENSES_DIR="$ROOT/LICENSES"
ASSETS_DIR="$ROOT/app/src/main/assets"
PAGE="$ASSETS_DIR/open_source_licenses.html"

# The one pandoc release this script is pinned to. Different pandoc versions
# render the same markdown differently (whitespace, the structural CSS
# partial, the zebra row classes, the computed column widths), so an
# unpinned pandoc would make the committed page depend on whichever binary
# happened to be first on PATH. Bumping this line is a deliberate act, and
# the page is expected to change when it happens.
PANDOC_VERSION='3.11'

# The heading in LICENSE.md where the third-party section starts, and the
# heading it becomes on the generated page.
SECTION_HEADING='## Included work ##'
PAGE_HEADING='## Open source licenses ##'
PAGE_TITLE='Open source licenses'

fail() {
  echo "generate-open-source-licenses: $*" >&2
  exit 1
}

pandoc_install_guidance() {
  cat >&2 <<EOF
  pandoc renders the markdown to HTML, and this script is pinned to pandoc
  $PANDOC_VERSION. There is no sudo on this machine, so install that exact version
  user-locally from the official release tarball:

    ver=$PANDOC_VERSION
    curl -sL -o /tmp/pandoc.tar.gz \\
      "https://github.com/jgm/pandoc/releases/download/\$ver/pandoc-\$ver-linux-amd64.tar.gz"
    tar xzf /tmp/pandoc.tar.gz -C /tmp
    mkdir -p ~/.local/bin
    install -m 0755 "/tmp/pandoc-\$ver/bin/pandoc" ~/.local/bin/pandoc

  ~/.local/bin is already on PATH; check with "pandoc --version".
EOF
}

# --- Check the tools before touching anything -------------------------------

missing=()
command -v pandoc >/dev/null 2>&1 || missing+=(pandoc)
command -v awk >/dev/null 2>&1 || missing+=(awk)

if [ ${#missing[@]} -ne 0 ]; then
  echo "generate-open-source-licenses: missing required tool(s): ${missing[*]}" >&2
  echo >&2
  for tool in "${missing[@]}"; do
    case "$tool" in
      pandoc)
        pandoc_install_guidance
        ;;
      awk)
        echo "  awk extracts the third-party section of LICENSE.md. Install gawk" >&2
        echo "  (Fedora: it is part of the base system; ask the maintainer to run" >&2
        echo "  \"dnf install gawk\" if it is genuinely absent)." >&2
        ;;
    esac
    echo >&2
  done
  exit 1
fi

pandoc_found="$(pandoc --version | head -1)"
if [ "$pandoc_found" != "pandoc $PANDOC_VERSION" ]; then
  echo "generate-open-source-licenses: wrong pandoc version." >&2
  echo "  expected: pandoc $PANDOC_VERSION" >&2
  echo "  found:    $pandoc_found  ($(command -v pandoc))" >&2
  echo >&2
  pandoc_install_guidance
  echo >&2
  exit 1
fi

# --- Check the inputs -------------------------------------------------------

[ -f "$LICENSE_FILE" ] || fail "LICENSE.md not found at $LICENSE_FILE"
[ -d "$LICENSES_DIR" ] || fail "LICENSES/ not found at $LICENSES_DIR"
[ -d "$ASSETS_DIR" ] || fail "assets directory not found at $ASSETS_DIR"

grep -qxF "$SECTION_HEADING" "$LICENSE_FILE" || fail \
  "LICENSE.md has no line reading exactly \"$SECTION_HEADING\", so the
  third-party section cannot be located. Either restore that heading or
  update SECTION_HEADING in this script to match the new one."

# --- Build everything out of the way ----------------------------------------

# The build directory holds the intermediate markdown and html. The staging
# directory holds anything that is about to become an asset; it has to be a
# sibling of the assets so that publishing is a rename within one
# filesystem. Both are unique, and the one trap removes both.
work="$(mktemp -d)"
stage="$(mktemp -d "$ASSETS_DIR/.generate-open-source-licenses.XXXXXX")"
trap 'rm -rf "$work" "$stage"' EXIT

markdown="$work/open-source-licenses.md"
html="$work/open_source_licenses.html"

# The page heading, then LICENSE.md from the section heading to the end of the
# file. The heading line itself is dropped because PAGE_HEADING replaces it.
#
# The sed pass turns any markdown link whose target is exactly the LICENSES
# directory into the plain text it was linking. In the app the page is loaded
# from file:///android_asset/, where LICENSES is a directory with no document
# in it, so such a link goes nowhere; the sentence reads correctly without it.
# Links to a file inside the directory (LICENSES/APACHE-2.0, LICENSES/GPL3,
# LICENSES/MIT) are real documents and are left alone.
{
  printf '%s\n' "$PAGE_HEADING"
  awk -v heading="$SECTION_HEADING" '
    $0 == heading { started = 1; next }
    started
  ' "$LICENSE_FILE"
} | sed -E 's/\[([^]]*)\]\(LICENSES\/?\)/\1/g' >"$markdown"

# A table with a header and a separator but no body row lists no third-party
# work at all, and would publish a licences page that credits nobody. Require
# a real row: a "|" line that comes after the separator line and is not
# itself a separator.
awk '
  /^\|/ {
    if ($0 ~ /^\|[[:space:]:|-]*$/) { separator = 1; next }
    if (separator) { found = 1; exit }
  }
  END { exit(found ? 0 : 1) }
' "$markdown" || fail \
  "the extracted section contains no table body row, so it lists no
  third-party work. Refusing to publish it. Check the \"$SECTION_HEADING\"
  section of LICENSE.md."

# document-css is deliberately blanked. The page is shown in a small WebView
# inside a dialog (HtmlDialogFragment), and pandoc's default stylesheet sets
# 50px of body padding and a hardcoded light background and text colour, which
# would both crowd the dialog and override the app's theme. Blanking the
# variable keeps only pandoc's small structural rules. pagetitle is set
# explicitly so the <title> is the page's real name rather than the name of
# the temporary file, and so pandoc does not warn about an empty title.
pandoc "$markdown" \
  --from markdown \
  --to html \
  --standalone \
  --variable "document-css=" \
  --metadata "pagetitle=$PAGE_TITLE" \
  --output "$html"

[ -s "$html" ] || fail "pandoc produced an empty $html"

# The same guard on the other side of pandoc: the rows have to have survived
# into the page, not just into the markdown.
grep -q '<td' "$html" || fail \
  "the generated page contains no table cell, so pandoc did not render the
  attribution tables. Refusing to publish it."

# --- Publish, now that every step above has succeeded -----------------------

# The licence texts are copied from LICENSES/, so most runs find the assets
# already identical and there is nothing to do. Only replace them when they
# genuinely differ, and then by building the replacement in the staging
# directory first and swapping it in, so the assets are never a half-copied
# directory.
if diff -r "$LICENSES_DIR" "$ASSETS_DIR/LICENSES" >/dev/null 2>&1; then
  licenses_note="LICENSES already up to date"
else
  cp -r "$LICENSES_DIR" "$stage/LICENSES"
  if [ -e "$ASSETS_DIR/LICENSES" ]; then
    mv "$ASSETS_DIR/LICENSES" "$stage/LICENSES.replaced"
  fi
  mv "$stage/LICENSES" "$ASSETS_DIR/LICENSES"
  licenses_note="replaced $ASSETS_DIR/LICENSES"
fi

# The page goes last, and goes in one rename from the same filesystem, so a
# reader either sees the old page or the new one and never a half-written
# file. Nothing follows this line that could fail after it.
cp "$html" "$stage/open_source_licenses.html"
mv "$stage/open_source_licenses.html" "$PAGE"

echo "generate-open-source-licenses: wrote $PAGE ($licenses_note)"
