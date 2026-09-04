# 16 — Direct Boot: implement it, or stop claiming it

Type: grilling
Status: open
Blocked by: —

## Question

Review finding (medium), `AndroidManifest.xml:99-108`. The receiver declares `LOCKED_BOOT_COMPLETED`, but neither it nor the application is marked `android:directBootAware="true"`, so it **cannot run before unlock**. Startup then immediately reads ordinary credential-protected `SharedPreferences`, which are unavailable during Direct Boot anyway. The changelog nonetheless claims reminders are scheduled before login.

So the feature does not work and the app says it does. Two honest ways out:

**Remove it.** Delete `LOCKED_BOOT_COMPLETED` and correct the claim. One commit, no risk.

**Implement it end to end.** Mark the required components direct-boot aware, keep the reminder state needed for scheduling in device-protected storage, migrate and unlock safely, and test a reboot-before-unlock scenario. That is a real feature with a real storage split — closer in size to the Room migration that was ruled out of scope than to a bug fix.

The decision is yours because it is a product question, not a technical one: **does a reminder need to fire on a phone that has rebooted and not yet been unlocked?** On a GrapheneOS Pixel with a strong passphrase, a reboot at 3am means nothing fires until morning. Whether that matters depends on how you actually use the app.

Recommendation going in: **remove the claim**. It is the honest fix, it is cheap, and if pre-unlock reminders turn out to matter it belongs on the feature map with a proper storage design rather than bolted onto a manifest.

**Done when** the manifest, the code and the changelog all say the same true thing.
