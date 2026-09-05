# 26 — Stop claiming reminders are scheduled before the device is unlocked

Type: task
Status: resolved
Blocked by: —

## Question

Implements the decision of ticket 16 (`16-direct-boot-decision.md`, read its
*Resolution* first). The app declares `LOCKED_BOOT_COMPLETED` on
`BootReceiver` but nothing is direct-boot aware, so Android never delivers it,
and the startup sweep reads credential-protected preferences that do not exist
before the first unlock. Upstream's 0.9.12 changelog still says reminders are
"loaded and scheduled already before logging in". The feature is wanted and is
deferred to the store redesign; this ticket makes the manifest, the strings,
the changelog, the feature list and the glossary say the same true thing.

Every change, nothing more:

1. **Manifest.** In `app/src/main/AndroidManifest.xml`, remove the
   `LOCKED_BOOT_COMPLETED` and `QUICKBOOT_POWERON` actions from the
   `BootReceiver` intent-filter. `BOOT_COMPLETED`, `enabled="false"`, the
   `RECEIVE_BOOT_COMPLETED` permission and `BootReceiver.kt` itself are
   untouched.
2. **Changelog, corrected in place.** In
   `app/src/main/res/xml/changelog_master.xml` (release 0.9.12) and
   `metadata/en-US/changelogs/912700.txt`, keep upstream's sentence and append
   to that same entry a correction in the sense of: "This never worked: the app
   can only run once the device has been unlocked, and Ding no longer claims
   otherwise." Do not delete the line and do not open a fork release block.
3. **Setting summaries.** In `app/src/main/res/values/strings.xml`, add the
   unlock condition to `preference_run_on_boot_summary_on` and
   `preference_run_on_boot_summary_off`, e.g. "Continue showing reminders after
   a restart of the device, once it has been unlocked." and "After a restart of
   the device, reminders will not be shown until the device is unlocked and the
   app is opened." Check the other locales under `res/values-*/` and change a
   translated copy only if one exists.
4. **Feature list.** Add an entry to `docs/planned-features.md`, in the style of
   the one already there: deliver reminders before the first unlock after a
   reboot. Why it matters: GrapheneOS can reboot the device on its own after a
   configured number of hours locked, so a reminder due between that reboot and
   the next unlock fires late, routinely, on the device this app is written for.
   What it needs: reminder content in device-protected storage, readable
   without the passphrase and shown on a locked screen, direct-boot-aware
   receiver and application, a safe migration at unlock, and a test of a
   reboot-before-unlock scenario. It belongs to the store redesign (the Room
   migration) and is not to be bolted onto the current preferences store. Say
   that the claim was removed by this ticket, as the existing entry does for
   ticket 25.
5. **Glossary.** Add to `CONTEXT.md`, next to *Reconcile*: **First unlock** —
   the first time the user unlocks the device after a reboot. Before it the
   store is unreadable and the app cannot run, so nothing fires; Reconcile runs
   at first unlock at the earliest. _Avoid_: boot, logging in.
6. **Bookkeeping**, same commit: `Status: resolved` here and one line in the
   map's *Decisions so far*.

No new tests: nothing here is JVM-testable, and the emulator cannot show the
difference. The gate must be green.

**Done when** `grep -r "LOCKED_BOOT_COMPLETED\|QUICKBOOT_POWERON" app/ metadata/`
finds nothing, the two changelog files carry the correction, the two summaries
mention unlocking, the feature list and the glossary have their entries, and
the gate is green.
