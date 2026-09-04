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
# Nothing outside the temporary directory is touched until every step has
# succeeded, so a failure part-way leaves LICENSE.md and the generated assets
# exactly as they were. Run it from anywhere; it locates the repo itself.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

LICENSE_FILE="$ROOT/LICENSE.md"
LICENSES_DIR="$ROOT/LICENSES"
ASSETS_DIR="$ROOT/app/src/main/assets"
PAGE="$ASSETS_DIR/open_source_licenses.html"

# The heading in LICENSE.md where the third-party section starts, and the
# heading it becomes on the generated page.
SECTION_HEADING='## Included work ##'
PAGE_HEADING='## Open source licenses ##'
PAGE_TITLE='Open source licenses'

fail() {
  echo "generate-open-source-licenses: $*" >&2
  exit 1
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
        cat >&2 <<'EOF'
  pandoc renders the markdown to HTML. There is no sudo on this machine, so
  install it user-locally from the official release tarball:

    ver=3.11   # latest at https://github.com/jgm/pandoc/releases
    curl -sL -o /tmp/pandoc.tar.gz \
      "https://github.com/jgm/pandoc/releases/download/$ver/pandoc-$ver-linux-amd64.tar.gz"
    tar xzf /tmp/pandoc.tar.gz -C /tmp
    mkdir -p ~/.local/bin
    install -m 0755 "/tmp/pandoc-$ver/bin/pandoc" ~/.local/bin/pandoc

  ~/.local/bin is already on PATH; check with "pandoc --version".
EOF
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

# --- Check the inputs -------------------------------------------------------

[ -f "$LICENSE_FILE" ] || fail "LICENSE.md not found at $LICENSE_FILE"
[ -d "$LICENSES_DIR" ] || fail "LICENSES/ not found at $LICENSES_DIR"
[ -d "$ASSETS_DIR" ] || fail "assets directory not found at $ASSETS_DIR"

grep -qxF "$SECTION_HEADING" "$LICENSE_FILE" || fail \
  "LICENSE.md has no line reading exactly \"$SECTION_HEADING\", so the
  third-party section cannot be located. Either restore that heading or
  update SECTION_HEADING in this script to match the new one."

# --- Build everything in a temporary directory ------------------------------

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

markdown="$work/open-source-licenses.md"
html="$work/open_source_licenses.html"

# The page heading, then LICENSE.md from the section heading to the end of the
# file. The heading line itself is dropped because PAGE_HEADING replaces it.
{
  printf '%s\n' "$PAGE_HEADING"
  awk -v heading="$SECTION_HEADING" '
    $0 == heading { started = 1; next }
    started
  ' "$LICENSE_FILE"
} >"$markdown"

# A page with no table row would mean the extraction silently produced nothing
# useful; better to stop than to publish an empty licences page.
grep -q '^|' "$markdown" || fail \
  "the extracted section contains no table rows, so it lists no third-party
  work. Refusing to publish it. Check the \"$SECTION_HEADING\" section of
  LICENSE.md."

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

cp -r "$LICENSES_DIR" "$work/LICENSES"

# --- Publish, now that every step above has succeeded -----------------------

# Replaced by rename so a reader never sees a half-written page.
cp "$html" "$PAGE.tmp"
mv "$PAGE.tmp" "$PAGE"

rm -rf "$ASSETS_DIR/LICENSES"
cp -r "$work/LICENSES" "$ASSETS_DIR/LICENSES"

echo "generate-open-source-licenses: wrote $PAGE and $ASSETS_DIR/LICENSES"
