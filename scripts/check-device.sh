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

# The device stage: the androidTest suite on an emulator Gradle creates, boots and
# tears down by itself. It answers the one question scripts/check.sh cannot — does
# a real AlarmManager fire the alarm the app set, and does the notification reach
# the system?
#
# WHEN TO RUN IT: before every `--no-ff` merge to `main`, whatever the ticket
# touched. Not on every commit — booting an emulator that often would erode the
# habit of always running scripts/check.sh, which is the gate that has to stay
# cheap. `main` is where merges land, so that is where this one runs.
#
# .github/workflows/device.yml runs the same Gradle task on every push to `main`
# and on manual dispatch. If the two ever drift, one of them is lying about
# whether the tree is good — fix the drift, don't pick a winner. The one
# difference is deliberate and is the renderer, not the task: see below.
#
# The emulator is Gradle's own, under ~/.android/avd/gradle-managed. It never
# touches the hand-made bench-pixel6-aosp AVD and never uninstalls anything from
# an attached device, unlike connectedDebugAndroidTest.
# `./gradlew :app:cleanManagedDevices` deletes it.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

echo "── D0 preflight ──"
if ! "$ROOT/scripts/check-sdk-platform.sh"; then
  echo "✗ DEVICE STAGE FAILED" >&2
  exit 1
fi
echo "✓ D0 preflight"

# The GPU flag is on this command and NOT in gradle.properties, which CI shares:
# this host segfaults under the SwiftShader renderer AGP defaults to, and the
# GitHub runner has no host GPU to hand out. Same task, renderer chosen per host.
echo "── D1 device test ──"
if ! (cd "$ROOT" && ./gradlew :app:pixel6aospDebugAndroidTest \
        -Pandroid.testoptions.manageddevices.emulator.gpu=host); then
  echo "✗ DEVICE STAGE FAILED" >&2
  # The scrollback is not the verdict; the per-test logcat capture is where a
  # device-side failure actually explains itself.
  echo "  report:  app/build/reports/androidTests/managedDevice/debug/allDevices/index.html" >&2
  echo "  logcat:  app/build/outputs/androidTest-results/managedDevice/debug/pixel6aosp/" >&2
  exit 1
fi
echo "✓ D1 device test"

echo "─────────────────────────────"
echo "Device stage green."
