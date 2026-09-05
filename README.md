# Ding

An Android reminder app. Something crosses your mind, you write it down in a
few seconds, and it comes back to you at the time you set.

1. Tap "Add reminder" on the home screen or use the Quick Settings tile
2. Enter a message and set the time on the clock
3. Tap "Add"

You can also type the time at the start of the message, once "Enable text-based
time input" is switched on in the settings. The dot is a clock time and the
colon is a delay from now: `12.30` is half past twelve, `12:30` is twelve hours
and thirty minutes from now, and `+90` and `:90` are both ninety minutes from
now. A space ends the time and starts the message.

At the due time a notification arrives, and if you asked for a nagging reminder
it repeats at a fixed interval until you deal with it. The whole value of the
app is firing reliably in the background, so that is what it is built and
tested around.

Screenshots: to be regenerated under the Ding name.

## Features

- A launcher icon and a Quick Settings tile that go straight to "Add reminder"
- Set a time within the next 24 hours on a clock widget, or type it at the
  start of the message once the setting for that is switched on
- Pick a date with +/- buttons or from a calendar
- Due, upcoming and past reminders in one list
- Reschedule or edit a reminder by tapping its notification or its list entry
- Mark a reminder done by swiping its notification away, or from the list
- Nagging reminders: the notification repeats at a set interval until dealt with

## A fork of SimpleReminder

Ding is a hard fork of
[SimpleReminder by Felix Wiemuth](https://github.com/felixwiemuth/SimpleReminder)
(the original project), taken at commit `d34bf2f`. Development upstream had
stopped, and the maintainer of this fork wanted to keep the app alive and
extend it. Upstream is not merged back in; useful changes are picked up by
hand.

## Requirements

Android 12 or later (`minSdk 31`).

Ding is Google-free: no Play Services, no Firebase, nothing from Google Mobile
Services in the dependency graph. It runs on GrapheneOS and on plain AOSP. This
is checked on every build rather than merely promised — the quality gate walks
the full runtime classpath of every variant and fails if such a dependency
appears.

## Building

You need the Android SDK with the `platforms;android-36` package and JDK 21.

```
./gradlew assembleDebug
```

To run everything the project requires to be green — lint, unit tests, the
Google-dependency check, and both the debug and release APKs:

```
scripts/check.sh
```

## Getting it

Releases are published on
[GitHub](https://github.com/jmnicolas90/Ding/releases). Ding is not on F-Droid
and not on the Play Store.

## Feedback

[GitHub issues on this repository](https://github.com/jmnicolas90/Ding/issues)
are the only contact channel. If you found a problem, please
[open a bug report](https://github.com/jmnicolas90/Ding/issues/new?template=bug_report.md);
if you have a concrete suggestion, please
[file a feature request](https://github.com/jmnicolas90/Ding/issues/new?template=feature_request.md).
Search the [existing issues](https://github.com/jmnicolas90/Ding/issues?q=is%3Aissue)
first. If you want to work on the code, see
[CONTRIBUTING.md](CONTRIBUTING.md).

## License

GPL-3.0-or-later, as the original was. There is no warranty.

Copyright (C) 2018-2025 Felix Wiemuth and [contributors](CONTRIBUTORS.md) for
the original SimpleReminder, and Copyright (C) 2026 Jean-Michel Nicolas for the
fork's changes. Ding also includes third-party software licensed under its own
terms. The details are in [LICENSE.md](LICENSE.md), the full licence texts in
[LICENSES/](LICENSES), and the credits in [CONTRIBUTORS.md](CONTRIBUTORS.md).
