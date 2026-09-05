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

# The preflight both local gates run first: is there an Android SDK, and does it
# hold the platform package this project compiles against?
#
# Its own script because scripts/check.sh runs it as G0 and scripts/check-device.sh
# runs it before booting an emulator, and a preflight copied into two files is a
# preflight that will one day check two different things.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# compileSdk = 36 needs platforms/android-36 specifically. Android Studio installs
# android-36.1, a distinct package that does NOT substitute — so the raw Gradle
# error reads as "platform missing" while an android-36.1 directory sits right
# there. Name the real fix instead.
sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [ -z "$sdk" ] && [ -f "$ROOT/local.properties" ]; then
  # tr strips the trailing CR a CRLF local.properties would leave behind.
  sdk="$(sed -n 's/^sdk\.dir=//p' "$ROOT/local.properties" | tail -1 | tr -d '\r')"
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
