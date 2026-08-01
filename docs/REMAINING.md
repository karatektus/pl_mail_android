# What is left

Written 2026-08-01, at the end of the session that landed the accounts screen, the narrow-screen
row fixes and the offline queue. `docs/PLAN.md` carries the architecture, the decisions and the
milestone history; **this file is the honest status and the to-do list**, and it exists because a
milestone marked "done" that is not is worse than one marked partial.

Read this first, then `PLAN.md` for why anything is the way it is.

---

## True status, milestone by milestone

| | Status | |
|---|---|---|
| M0 Toolchain, skeleton, test env | **done** | |
| M1 `:core:jmap` protocol layer | **done** | 108 JVM tests, no emulator |
| M2 Persistence, credentials, onboarding | **done** | |
| M3 Unified inbox | **done** | |
| M4 Reader | **done** | |
| M5 Actions, undo, bulk | **done** | offline queue added M11, see below |
| M6 Staying current (sync, SSE, push, notifications) | **done** | |
| M7 Search | **done** | |
| M8 Compose | **done** | scheduled send blocked on server |
| M9 Organising (labels, snooze) | **done except colour** | colour blocked on server |
| Design system | **done** | six themes, two layouts, three densities |
| **M10 Appearance and settings** | **done** | see below |
| **M11 Polish and ship-readiness** | **partial — roughly a third** | see below |

### M10 — done as of this session

Everything M10 asked for is in:

- Theme × Layout × Density chooser, Material You, reduced motion/transparency, pane alpha.
- Diagnostics screen.
- **Account list and order** — `AccountsScreen`, arrows rather than drag (see below), order stored
  in DataStore and applied to the composer's From picker and to which account a new label is
  created in.
- **Sync-window display** — per account, how much is cached on the device and back to when, plus a
  button that asks the server for its own oldest message.
- **Notification preferences** — per account, stored as *muted* so a new mailbox speaks by default,
  enforced in `DeltaSync` where the announcement is raised.

### M11 — what is done

- **German at real length on a narrow screen.** Found and fixed: the 160dp chip budget had a cap
  and no floor, so at 320dp the preview was cut to about three characters. There is now a preview
  floor enforced at measure time (`Modifier.layout`, not `BoxWithConstraints` — this is fifty rows
  scrolling) and a slot count decided once per list from the pane's own width. `ThreadRowNarrowTest`
  pins both. **This closes the carried "German at real length" item.**
- **System label names are localised.** The sidebar said "Inbox" on a German device while plMail's
  web client said "Posteingang" for the same account. Resolved by `role`, never by name.
- **The thread row's TalkBack sentence is resources**, plurals included. It was eight English string
  literals in Kotlin, so a German device read a German inbox out in English.
- **Offline as a first-class state.** Cached mail stays on screen; a banner distinguishes "you are
  offline" from "plMail could not reach `<host>`"; mutations that hit a transport failure are
  queued in DataStore and drained by a network-constrained WorkManager job. Verified end to end on
  the emulator with the radios off — the queued Trash reached the server.

### M11 — what is NOT done

Everything below is untouched. Rough order of value:

1. **TalkBack sweep.** Only `ThreadRow` and the new accounts screen have had their semantics
   thought about. The reader, the composer, search, onboarding, the label sheet and the appearance
   screen have not been walked with TalkBack on at all. Expect the same class of defect the thread
   row had: English literals, unlabelled icon buttons, and controls whose visible label repeats
   across rows with nothing to tell them apart.
2. **Contrast sweep across all six themes.** `PaletteContrastTest` already computes real relative
   luminance ratios through the same resolver the app uses, and it passes — but it checks the
   *token pairs*, not every pair the app actually draws. Nothing has verified, for instance, the
   warning ink on `warningSoft` in Nord, or the new accounts screen's switch colours in Solar.
3. **≥48dp targets and ≥16sp body, audited.** The tokens promise it and `ThreadRow` enforces a
   minimum height; nothing sweeps the app for a control that ignores them. The new reorder arrows
   are `IconButton`, which is 48dp, but that was luck rather than a check.
4. **Predictive back.** Not started. `android:enableOnBackInvokedCallback` is not set in the
   manifest, and the state-swapped screens in `MainActivity` use plain `BackHandler`, which does not
   participate in the predictive animation. The screens are already funnelled through one derived
   `Screen` value, so the conversion is small and localised.
5. **App shortcuts and an unread widget.** Not started. Shortcuts want `shortcuts.xml` plus a static
   "compose" and dynamic per-label entries; the widget wants Glance and a count query that does not
   wake the network.
6. **R8 rules.** The release build minifies and passes lint today, but nothing has been checked at
   runtime — `Email`, `Mailbox`, `Thread` and friends are kotlinx-serialization types and the
   plugin's own consumer rules are what is keeping them; the *new* `PendingMutation` in
   `:core:data` is the first serialized type outside `:core:jmap` and has never been through R8.
   **Install a release build and pair it before believing anything here.**
7. **Baseline profile + Macrobenchmark.** The `:benchmark` module named in the plan does not exist.
8. **Roborazzi across themes × densities × phone/tablet.** Today's baselines are light and dark, at
   411dp, phone only. The matrix is six themes × three densities × two form factors and wants a
   parameterised test rather than a hand-written one per case.
9. **Queued mutations in the composer.** The outbox covers mail *actions*. A send that fails offline
   still goes through `SendQueue`, which is in-memory and does not survive the process — worth
   deciding whether a draft that could not be submitted should join the outbox.

---

## The carried unverified list

Several sessions have now inherited this. Where an item is blocked on something that does not exist
on this machine, that is said rather than implied.

| Item | What it needs | Notes |
|---|---|---|
| **Attachment save against a provider other than the local Downloads picker** | A second `ACTION_CREATE_DOCUMENT` provider installed on the AVD — Google Drive, Nextcloud, or any DocumentsProvider. The stock `google_apis` image has only Downloads. | Blocked on an install, not on effort. The `FileProvider` path is exercised by the Downloads picker; what is untested is a provider that writes asynchronously or refuses. Sideloading Nextcloud's APK onto the AVD would settle it in ten minutes. |
| **Multi-distributor branch of diagnostics** | Two UnifiedPush distributors installed at once. Only ntfy is on the AVD. | Blocked on an install. A second distributor (`Sunup`, `NextPush`) would do. The branch is one `if` in `DiagnosticsScreen`; the risk is that `PushSetup` picks one silently instead of refusing, which is the behaviour the note claims it does not have. |
| **`retryPush` with no distributor installed** | ntfy uninstalled from the AVD, then "Enable push" tapped. | **Not blocked on anything.** Cheap, and it is the path that returns the untranslated `NO_DISTRIBUTOR` string — which is itself a known M11 gap (`DiagnosticsViewModel` says so in a comment). |
| **Material You against an actual wallpaper change** | A wallpaper set on the AVD and the dynamic-colour switch on. | **Not blocked.** `adb shell` cannot set a wallpaper directly on API 36 without a helper, but the emulator's own Settings app can. What is being checked is that the *whole* token set moves, not just the accent. |
| **The pane-alpha slider's visual effect below 100%** | The boxed layout selected, then the slider moved. | **Not blocked, and slightly suspicious.** `PlMailPane` only applies alpha when `layout == BOXED`, which is correct and documented — but it means the slider does nothing at all in the flat layout, which is the default, and the appearance screen does not say so. Worth checking whether the control is drawn disabled or simply appears broken. |
| **German at real length on a narrow screen** | — | **Closed this session.** Found two real defects (chip budget floor, English system-label names) and fixed both. See `ThreadRowNarrowTest`. |
| **The tablet AVD** | `~/Android/run-emulator.sh plmail_tablet_api36`, or the Windows AVD reconfigured. | **Untouched for three sessions and now four.** The two-pane list/detail, the permanent drawer, the composer-as-dialog and the new `rowLabelSlots` decision all only exist on a tablet. `rowLabelSlots` in particular takes the *pane* width precisely because a tablet's list pane can be narrower than a phone — and that reasoning has never been looked at on a tablet. |

---

## Things found and not chased

Written down because previous sessions' honesty about exactly this has led directly to real bugs.

- **The unreachable banner is sticky.** `FeedRepository._failures` is only rewritten when a page
  load runs. After the offline test, the network came back, the outbox drained through WorkManager
  and the banner "plMail could not reach 10.0.2.2" was **still on screen** — because nothing had
  re-paged the list. It is pre-existing (this session only changed the wording), and it means any
  transport failure leaves a banner up until the user pulls to refresh. The fix is probably to
  clear `_failures` on a successful sync as well as on a successful page.
- **`./gradlew build` intermittently fails a `lintAnalyze*` task.** Seen three times this session,
  on `:feature:search:lintAnalyzeDebugUnitTest`, `:feature:onboarding:lintAnalyze*` and
  `:core:notifications:lintAnalyzeDebugUnitTest`. Each succeeded when run alone immediately
  afterwards, and the next full `build` was green with no code change. No error text is printed —
  just `FAILED`. Smells like parallel lint workers and memory rather than anything in the code, but
  it has not been diagnosed, and it will look like a real failure to whoever hits it next.
  **Re-run before believing it.**
- **`OutboxStore` and `AppearanceStore` and `PushStateStore` share one preferences file.** They all
  `distinctUntilChanged`, which is what stops a sync re-theming the app — but the file is now doing
  four jobs and a fifth writer will not be obvious. Worth splitting before it is five.
- **`AccountEntity.sortIndex` is now dead-ish.** The user's order lives in DataStore, and the only
  remaining reader of `sortIndex` is the DAO's `ORDER BY sortIndex, name`, which is the fallback
  when nobody has arranged anything. That is deliberate and correct, but a future reader will
  reasonably assume `sortIndex` is the ordering. The comment on `AccountPrefsStore` explains it;
  a comment on the column would help more.
- **`Identity.email` still may not parse.** `SeedTestEmailCommand.php:110` sets the account email to
  the display name "E2E Mailbox", so the first seeded account's address is not an address. The
  *second* account seeded this session (`second@e2e.test`) deliberately has a real one, so the stack
  now has one of each — which is useful and is why it was done that way.
- **The accounts screen asks the server one `Email/query` per account.** With many accounts that is
  many round trips on one button. Fine at two; worth batching into one request with several method
  calls if anybody has ten.
- **`Outbox.drain` swallows the reason a change was refused.** It drops the mutation, which is
  right, but nothing is recorded anywhere the user can see. A change made offline that the server
  later refuses disappears silently. The diagnostics screen would be the honest home for it.

---

## Server asks

Six are open in `docs/SERVER_REQUESTS.md`, which has the probe evidence for each. In short, and in
terms of what each unblocks on the client:

| Ask | Unblocks |
|---|---|
| `Email/set` update honours `attachments` | Removes the "recreate the draft and bin the old one" workaround, and the stray Trash entry per attachment change |
| `EmailSubmission/set` honours `identityId` | The From picker can offer aliases instead of one entry per account |
| A JMAP surface for contact autocomplete | Suggestions for people whose mail is not on this device |
| `Mailbox.color` over JMAP | Label colour — see below |
| `Appearance` over JMAP | The phone honours the theme set on the web; `PlMailAppearance.of` is already the one function the sync will call |
| Scheduled send (`maxDelayedSend` > 0) | "Send tomorrow at 8am"; the current undo window is seconds and does not survive the process |
| Sync window in the session object (**new this session**) | The accounts screen could say what the *server* retains rather than inferring it from the oldest message |

### Label colour, specifically

Implemented on plMail's branch `fix/jmap-label-colour` and **not merged**. It adds `Mailbox.color`
with a closed vocabulary of Tailwind tokens (gray, red, orange, amber, green, teal, blue, violet,
pink) or null, refused with `invalidProperties` if unknown, settable on create and on update.

Chips are neutral and must stay neutral until it lands. When it does, adopting it is:

1. `MailboxEntity` gains a `color` column, and the schema goes to version 3 through the destructive
   upgrade — no hand-written migration, because it is cache like everything else there.
2. `Mailbox` in `:core:jmap` gains the field; `MailboxMapper` already sends what it is given.
3. `Label` carries it through `Labels.kt` (the *primary* binding decides, same as the name).
4. `PlMailLabelChip` takes a colour parameter. Its doc comment already says this and says nothing
   about the shape, size or placement changing.
5. **The colours have to be resolved through the theme, not used raw.** A Tailwind token on Nord's
   Polar Night is the same contrast problem the plan already recorded for Nord's own `#BF616A`, and
   `PaletteContrastTest` must be extended to sweep the nine tokens through all six themes before
   any of them is drawn.
6. The label editor gains a picker. Nine swatches plus "none".

Do not pre-empt any of this while the branch is unmerged.

---

## Traps worth inheriting

Already in `PLAN.md`: the flavoured APK path, quoting a `plmail://` URI for the device's shell, one
Gradle invocation at a time, `sg kvm`, the Windows emulator relay and its firewall rule.

New from this session:

- **`adb` is not on a non-interactive shell's PATH.** `/etc/profile.d/jdk20.sh` and the `.bashrc`
  early return mean a `bash -c` gets neither `ANDROID_HOME` nor platform-tools. Export both
  explicitly in any script.
- **Seeding server-side data is allowed; writing to the server checkout is not.** The distinction
  matters because `compose.test.yaml` bind-mounts `./:/app`, so a script written to `/app` *is* a
  write to `~/pl_mail`. Write it to the container's own `/tmp` instead:
  `docker compose -p pl_mail_android -f compose.test.yaml exec -T app sh -c 'cat > /tmp/x.php' < local.php`.
  The kernel boots from `/app/vendor/autoload.php` and `test.service_container` gives access to
  private services.
- **A second JMAP account is one `Account` row on the e2e user.** `SessionBuilder` exposes one JMAP
  account per row. System labels are created lazily, so a fresh second account arrives with an
  Inbox and nothing else — which is a *better* test than a fully-furnished one.
- **`app:test:seed-mail` targets a hardcoded username** (`mailbox@e2e.test`), so it cannot seed a
  second account. There is no console command that can; a script is the only route.
- **`--` is illegal inside an XML comment in an Android resource file.** It fails
  `packageDebugResources` with a message that does not mention which comment. This repo's comment
  style uses `--` as an em-dash substitute in Kotlin, and carrying that habit into `strings.xml`
  breaks the build.
- **`:core:data` had the kotlinx-serialization *runtime* and not the compiler plugin.** It parsed
  JSON by hand, so nothing noticed. Adding an `@Serializable` class compiled fine and threw
  "Serializer for class X is not found" at run time, in a test. If you add a serialized type to a
  module, check its `plugins {}` block.
- **A `semantics {}` block is not a composable scope.** `stringResource` cannot be called inside it;
  read the string out of composition first. This bit twice in one session.
- **Per-app locale for testing German:**
  `adb shell cmd locale set-app-locales de.plmail.debug --locales de-DE`. No reboot, no device-wide
  change. Undo with `--locales en-GB`.
- **Simulating a 320dp phone:** `adb shell wm size 640x1280 && adb shell wm density 320`, and
  `wm size reset && wm density reset` afterwards. Forget the reset and every later screenshot is
  wrong in a way that looks like a layout bug.
- **`adb shell svc wifi disable && adb shell svc data disable`** is how the offline path was tested.
  Airplane mode via `settings put global airplane_mode_on` needs a broadcast that is restricted on
  recent API levels; `svc` works and is reversible.
