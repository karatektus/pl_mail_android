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
| M9 Organising (labels, snooze) | **done** | colour adopted 2026-08-01, see below |
| Inbox categories (Gmail tabs) | **done, server branch unmerged** | see below |
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
- **The reader renders hostile HTML.** Three product-owner bugs, all reproduced against a seeded
  800px Stripe-style receipt and all watched fixed on the emulator: a fixed-width table's right-hand
  column was laid out past the pane and clipped, so a receipt showed its labels and none of its
  amounts; the conversation could not be scrolled at all, putting reply and forward out of reach;
  and adaptation for the dark used one hardcoded near-black rather than the chosen theme. See
  `MessageDocument` for the fitting rules and why `max-width` alone is not one, and `ReaderWebView`
  for the height. Reply and forward are now also a pinned bar, so they no longer depend on a
  WebView's measurement being right.

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
| **The reader's own dark strategies, on a message that actually uses them** | A message declaring `prefers-color-scheme` (`DARK_NATIVE`), and one with a `cid:` inline image, under a dark theme. | **Not blocked.** `DARK_RESTYLED`, `DARK_INVERTED` and `ORIGINAL` were all watched under Nord this session against the seeded receipt and a plain-text message; `DARK_NATIVE` was not, because no seeded message declares a scheme, and it is the one path that hands rendering to `WebSettingsCompat` rather than to our stylesheet. A three-line body with a `@media (prefers-color-scheme: dark)` block would settle it. |
| **The reader on a message with attachments, and on a thread of several** | Any seeded thread with more than one message. | **Not blocked, and worth doing.** The message card and the pinned bar were both only seen on single-message threads. The pinned bar deliberately answers the *newest* message while the row inside each card answers that card's, and nothing has confirmed the two look distinguishable when four cards are stacked. |
| **The tablet AVD** | `~/Android/run-emulator.sh plmail_tablet_api36`, or the Windows AVD reconfigured. | **Untouched for three sessions and now four.** The two-pane list/detail, the permanent drawer, the composer-as-dialog and the new `rowLabelSlots` decision all only exist on a tablet. `rowLabelSlots` in particular takes the *pane* width precisely because a tablet's list pane can be narrower than a phone — and that reasoning has never been looked at on a tablet. |

---

## Things found and not chased

Written down because previous sessions' honesty about exactly this has led directly to real bugs.

- **A search result does not open the conversation.** `MainActivity` wires the search screen's
  `onOpenThread` to `{ _, _ -> isSearching = false }` — it closes search and returns to whatever
  list was underneath, and the thread that was tapped is never shown. Found while trying to reach a
  seeded message; M7 is marked done and this is the one thing search is for. It also matters more
  than it looks, because search is the *only* way to reach a conversation the feed has not paged.
- **A thread's row and its reader disagree about the avatar letter.** The reader now takes the
  initial from the display name and falls back to the address, so "Anthropic, PBC" is an A; the list
  row still hashes and letters from the address alone and shows an I for `invoice+statements@`.
  `ThreadRow` is in `core/ui` and was another agent's file this session, so only the reader was
  changed. The *colour* must keep coming from the address — see `avatarInitial`'s note.
- **The inbox feed does not notice a message the server already has.** A thread inserted
  server-side is returned by `Email/query` immediately, and the app's list still does not show it
  after a relaunch or after Diagnostics' "Check now" — `FeedMediator.initialize` skips the refresh
  whenever the feed has any cached rows, and `Email/changes` did not report the insert. Worked
  around while testing by labelling the thread with a label whose local feed was empty. Whether this
  is a server changelog gap or a client one has not been established, and it is the reason a
  freshly-seeded message appears to vanish.
- **A message whose body inverts is drawn on pure black.** `invert(1)` of a newsletter's white paper
  is `#000`, which under Nord sits inside a `#3B4252` card and reads as a hard rectangle. It is
  correct — that is what inversion means, and Gmail does the same — but a small `brightness()` in
  the filter would land it nearer the theme. Not attempted, because every colour in the message
  moves with it and the "show original" hatch is one tap away.
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
- **A Roborazzi baseline was left stale by `cc290db` and nobody noticed.** That commit added the
  snippet floor to `ThreadRow` and did not re-record, so `thread-row-labels-long-*` had been
  disagreeing with the code since. It surfaced this session only because a re-record was needed
  anyway. `verifyRoborazziDebug` is not part of `./gradlew build` — that is why a stale baseline can
  sit there — and it is worth deciding whether it should be.
- **The app bar said "Inbox" for one frame after saving a label edit**, with the label's own rows
  still on screen. Seen once, on the device, and **not reproduced**: the emulator was being shared
  and the second attempt was interrupted by another session installing an APK. The mechanism would
  have to be `MailView.restore` failing to find the key and falling back to `Inbox`, but
  `replaceMailboxes` is transactional and never leaves the table empty, so that does not obviously
  explain it. Written down rather than guessed at. The pre-existing code had the same fallback
  shape (`?: labels.firstOrNull()`), so if it is real it is not new.
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
| `Appearance` over JMAP | The phone honours the theme set on the web; `PlMailAppearance.of` is already the one function the sync will call |
| Scheduled send (`maxDelayedSend` > 0) | "Send tomorrow at 8am"; the current undo window is seconds and does not survive the process |
| Sync window in the session object | The accounts screen could say what the *server* retains rather than inferring it from the oldest message |

Two have closed. **`Mailbox.color` is merged** into plMail `main` and adopted here. **The inbox
categories are implemented on `feat/jmap-categories`** and await a human merge — that branch is the
only thing between this client's category navigation and a real account.

### Label colour — **adopted 2026-08-01**

`Mailbox.color` is merged into plMail `main` (`b06b909`) and the client now uses it. All six steps
this file previously listed are done: the schema column, the wire field, `Label.color` from the
primary binding, the chip's colour parameter, the per-theme resolution, and the picker.

Two things about how it landed are worth keeping, because both were decisions rather than
transcription:

- **The chip's fill never changes.** Colour goes into the hairline and the text; `sunken` stays the
  background. A tinted pill is a filled area of colour on every labelled row, which is a second
  population of coloured marks competing with the unread dot — the one accent the row is allowed.
  It also would have broken contrast: `inkMuted` on `sunken` is barely over the AA floor already,
  and washing the fill would have taken it under. The geometry is untouched, so
  `ThreadRowLayoutTest` still measures labelled rows equal to unlabelled ones.
- **The vocabulary lives once, in `:core:designsystem`.** `:core:jmap` carries the raw token
  uninterpreted because it is Android-free, and the cache stores the raw token so a tenth colour
  added server-side survives to an app update rather than being erased by an enum written today.
  `PaletteContrastTest` now sweeps nine tokens × six schemes against both `surface` and `sunken` at
  4.5:1 — the sweep this file asked for.

### The inbox categories — **client done, server branch unmerged**

plMail has classified inbox mail into Gmail's five categories for a long time and the web has had a
tab bar over it. There was no JMAP surface at all. The server side is on **`feat/jmap-categories`**
in a worktree at `~/pl_mail_categories`, one commit, and **needs a human to merge**; the client is
built against it and degrades correctly without it (every `Thread.category` is null, so the drawer's
category group never appears).

- Navigation is Gmail's drawer, not a tab strip: the five categories are rows indented under Inbox,
  above the other system labels. A tab strip over the list would be a second navigation control
  disagreeing with the drawer about where the user is.
- **Inbox stays the whole inbox** rather than becoming Primary, which is where this departs from
  Gmail on purpose. The server puts an unclassified conversation in no category at all, so an Inbox
  that meant Primary would hide mail on any plMail whose category backfill has not run.
- The tab is a **server-side filter** (`EmailFilter.ThreadCategory`), not a sieve over a page. See
  the commit message for the paging argument.
- **No unread badge on the category rows**, deliberately. The only number this device could show is
  how many unread of that category it has *paged*, and JMAP publishes no per-category total — a
  badge disagreeing with the web's is worse than none.

**Watched work on the device**, against a patched server on its own stack (8003, own volumes, the
8002 one untouched) seeded across all five categories: the drawer group and its indentation, the
Promotions tab returning exactly its three conversations and ending there, the same for Updates, two
chips of different colours on one row, recolouring a label from the phone reaching the server
(`Reisen` → `violet`) and the chip redrawing, and the whole thing again in **dark and in German** —
"Allgemein / Soziale Netzwerke / Werbung / Benachrichtigungen / Foren", the longest of which fits the
280dp drawer without truncating.

**Not watched:** Nord, Dusk and Solar, which are covered numerically by `PaletteContrastTest`'s
54-pair sweep rather than by an eye; and a tablet, where the permanent drawer draws the same rows in
a pane of a different width.

---

## Traps worth inheriting

Already in `PLAN.md`: the flavoured APK path, quoting a `plmail://` URI for the device's shell, one
Gradle invocation at a time, `sg kvm`, the Windows emulator relay and its firewall rule.

New from this session:

- **Running a patched server without touching `~/pl_mail`.** `compose.test.yaml` bind-mounts `./`
  over `/app`, so a git worktree of the server has no `vendor/` and the image's copy is hidden. The
  answer is a compose *overlay written outside the repo* and passed as a second `-f`: relative paths
  resolve against the first file's directory, so `context: .` still means the worktree, and the
  overlay adds `- /home/karatektus/pl_mail/vendor:/app/vendor:ro`. With `-p pl_mail_cat` and
  `TEST_HTTP_PORT=8003` it is a third stack with its own volumes, and the 8002 one is untouched.
- **The emulator is genuinely shared and it will move under you.** Two sessions drove it at once
  this time; taps landed in the launcher, YouTube opened, and an APK install from the other session
  covered the screen mid-capture. Put a whole navigation into **one** `adb shell "...; sleep n; ..."`
  rather than a sequence of `adb shell input tap` calls, and screenshot immediately after — the
  window between two `adb` invocations is where the other session gets in.
- **The other session's install will silently downgrade your schema, and it looks like your bug.**
  Mid-verification the app stopped drawing colours and stopped showing the category rows, with a
  healthy server that was demonstrably still returning both. The cause was the *other* session
  installing its own APK, built before the version-3 schema; Room saw a downgrade and recreated the
  database at version 2, without the two new columns. Nothing in the app says so. The tell is
  `adb shell run-as de.plmail.debug cat databases/plmail.db` piped to a file and
  `PRAGMA user_version` — thirty seconds, and it turns "my feature is broken" into "somebody else
  installed over me". Re-install immediately before every observation, not once at the start.
- **`adb` is not on a non-interactive shell's PATH.** `/etc/profile.d/jdk20.sh` and the `.bashrc`
  early return mean a `bash -c` gets neither `ANDROID_HOME` nor platform-tools. Export both
  explicitly in any script.
- **Seeding server-side data is allowed; writing to the server checkout is not.** The distinction
  matters because `compose.test.yaml` bind-mounts `./:/app`, so a script written to `/app` *is* a
  write to `~/pl_mail`. Write it to the container's own `/tmp` instead — `docker cp local.php
  <project>-app-1:/tmp/x.php && docker exec <project>-app-1 php /tmp/x.php`. The kernel boots from
  `/app/vendor/autoload.php` and `test.service_container` gives access to private services, but
  **booting it by hand skips the Dotenv the console does for you**: the first failure is
  `Environment variable not found: "TRUSTED_PROXIES"` during `preBoot`, which looks like a database
  problem and is not. `(new Symfony\Component\Dotenv\Dotenv())->bootEnv('/app/.env', 'test');`
  before `new Kernel(...)` fixes it.
- **A WebView calls `requestLayout()` from inside its inherited constructor.** `ViewGroup` does it
  through `setFlags`, long before the Chromium backend exists, so an override that asks the view
  anything about its content dies with `IllegalStateException: AwContents must be created if we are
  not posting!` — and it dies on opening a message, which is the whole app. A Kotlin subclass's own
  fields are still at their JVM defaults at that moment, which is what makes a plain
  `private var isConstructed = false` with `init { isConstructed = true }` the guard that works.
  See `ReaderWebView`.
- **Iterating on a message's CSS does not need a build.** The document `MessageDocument.wrap`
  produces is a static file: write it to disk, `python3 -m http.server` in WSL, open
  `http://10.0.2.2:<port>/probe.html` in the emulator's Chrome. That turned a twelve-minute
  build-install-navigate loop into ten seconds and is what found the two rules a `max-width` cannot
  express. `file:///sdcard/...` does **not** work — Chrome refuses it with `ERR_ACCESS_DENIED`.
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
