# 18 — Decide how alarm delivery gets tested automatically

Type: grilling
Status: open
Blocked by: 09

## Question

Review finding: **"Core reminder state and persistence paths have no automated tests"** (medium). The 65 existing tests cover date arithmetic and `TimeMatcher` — nothing touches storage, ID allocation, failed commits, malformed JSON, state transitions, cold-start delivery, notification permissions, edit flows, or boot reconciliation. Those are the highest-risk parts of the app.

Tickets 10 through 13 each add tests for their own change. This ticket decides the **approach** for the class of test none of them can write alone: does the alarm actually fire, and does the notification actually appear?

The hard part is that this is a test about *time* and *process lifecycle*. Waiting is not testing. Options to weigh:

- **Robolectric** — fast, runs in the JVM gate, can drive the scheduler and fake the clock. Cannot tell you what a real `AlarmManager` under Doze does.
- **Instrumented tests on `bench-pixel6-aosp`** — real platform, real `AlarmManager`, and the emulator is already on this machine. Slow, and needs a decision about whether CI runs it (GitHub's Linux runners do support KVM).
- **A seam in the app** — put the clock and the scheduler behind interfaces so most of the behaviour is testable as pure logic, and only a thin layer needs a device.

Blocked by ticket 09 because the answer depends on where the transition boundary landed: a good boundary makes most of this pure-logic testing, and a bad one forces everything onto a device.

Worth keeping in proportion: this app has run reliably on GrapheneOS for years. The risk being managed is **regression from our own changes**, not a pre-existing defect. That argues for tests that pin current behaviour before it is touched, rather than an exhaustive device suite.

**Done when** there is a decided approach, a named first test to write, and a note on whether it runs in CI.
