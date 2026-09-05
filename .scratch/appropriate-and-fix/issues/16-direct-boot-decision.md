# 16 — Direct Boot: implement it, or stop claiming it

Type: grilling
Status: resolved
Blocked by: —

## Question

Review finding (medium), `AndroidManifest.xml:99-108`. The receiver declares `LOCKED_BOOT_COMPLETED`, but neither it nor the application is marked `android:directBootAware="true"`, so it **cannot run before unlock**. Startup then immediately reads ordinary credential-protected `SharedPreferences`, which are unavailable during Direct Boot anyway. The changelog nonetheless claims reminders are scheduled before login.

So the feature does not work and the app says it does. Two honest ways out:

**Remove it.** Delete `LOCKED_BOOT_COMPLETED` and correct the claim. One commit, no risk.

**Implement it end to end.** Mark the required components direct-boot aware, keep the reminder state needed for scheduling in device-protected storage, migrate and unlock safely, and test a reboot-before-unlock scenario. That is a real feature with a real storage split — closer in size to the Room migration that was ruled out of scope than to a bug fix.

The decision is yours because it is a product question, not a technical one: **does a reminder need to fire on a phone that has rebooted and not yet been unlocked?** On a GrapheneOS Pixel with a strong passphrase, a reboot at 3am means nothing fires until morning. Whether that matters depends on how you actually use the app.

Recommendation going in: **remove the claim**. It is the honest fix, it is cheap, and if pre-unlock reminders turn out to matter it belongs on the feature map with a proper storage design rather than bolted onto a manifest.

**Done when** the manifest, the code and the changelog all say the same true thing.

## Resolution (2026-09-05)

Decided in conversation. The claim goes, the feature is written down for the
store redesign, and the change itself is ticket 26.

- **Pre-unlock delivery matters to the maintainer, and is not built now.** The
  going-in recommendation was to remove the claim and forget the feature; that
  rested on a reboot while locked being rare. It is not: GrapheneOS can reboot
  the device on its own after a configured number of hours locked, and the
  maintainer's phone is set up that way. So a reminder due between such a reboot
  and the next unlock fires late, routinely, and that is worth fixing — but
  fixing it means reminder content readable before the passphrase, in
  device-protected storage, with direct-boot-aware components. That is a
  storage split, so it belongs to the same effort as the Room migration and is
  recorded in `docs/planned-features.md` and on the map's Room line under *Out
  of scope*, not built here.
- **`LOCKED_BOOT_COMPLETED` and `QUICKBOOT_POWERON` are both removed** from the
  boot receiver's intent-filter. The first is never delivered to a receiver that
  is not direct-boot aware, so removing it changes no runtime path. The second
  is an old HTC fast-resume action with no meaning at minSdk 31. `BOOT_COMPLETED`
  and the "Activate on device startup" setting stay as they are.
- **Upstream's changelog sentence is corrected in place, not deleted** — in the
  in-app changelog and in the store metadata file for 0.9.12 alike. The
  historical sentence stays as the record that upstream made the claim, followed
  by a short correction saying it never worked, the app can only run once the
  device has been unlocked, and Ding no longer claims otherwise. No fork release
  block is opened, so no version-number decision is dragged in.
- **The "Activate on device startup" summaries say "once it has been
  unlocked"**, both the on and the off summary, because that is where a user
  looks for why a reminder came late after a reboot.
- **"First unlock" becomes a glossary term** in `CONTEXT.md`: the first time the
  user unlocks the device after a reboot; before it the store is unreadable and
  the app cannot run, so nothing fires, and Reconcile runs at first unlock at
  the earliest. Avoid "boot" and "logging in".
- **Ticket 26 is a task on the gate alone**: nothing in it is JVM-testable, and
  the emulator image has no lock credential and boots straight to unlocked, so
  it cannot show the difference.
