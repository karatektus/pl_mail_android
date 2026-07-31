# plMail for Android

## Context

`pl_mail` is a self-hosted mail client (Symfony 8 / PostgreSQL) that exposes **JMAP** at `/jmap` as
the one stable, documented surface for third-party and native clients. Its
[docs/CLIENT_DEVELOPMENT.md](../../pl_mail/docs/CLIENT_DEVELOPMENT.md) is a full client
specification: what the product is, how it must look and behave, and every wire detail.

An iOS client (`pl_mail_ios`, ~16k LOC) already implements that spec and is under active development
by someone else. This plan builds a native Android client to the same specification and the same
standard, following Google's own architecture guidance (Now in Android module layout, Compose +
Material 3, Hilt, Room, Paging 3, WorkManager) and the Android Kotlin style guide.

**The iOS app is reference, not a port target.** Where its solution is domain logic — the k-way feed
merge, the per-message dark-render strategy, the TOFU key pin, the "everything is a cache" schema
rule — it is reused, because those are conclusions about *plMail*, not about Swift. Where it is
platform shape, Android idiom wins.

**The client doc is slightly stale.** `Thread/set` (snooze) and `SearchSnippet/get` exist in
`pl_mail/src/Jmap/Method/Mail/` but are listed as absent in §3. `src/Jmap/` is the authority; verify
capabilities against the live session object, never against the doc alone.

> **This document has been adapted from the original macOS plan to the Windows/WSL2 machine, which
> is where Android development happens from now on.** Everything in the Environment, Test
> environment and Verification sections below was re-verified on this host on 2026-07-31. The
> milestones, architecture and non-negotiables are platform-independent and are unchanged in
> substance. Where the macOS plan said something that is simply not true here — arm64 images,
> Homebrew, `~/.zshrc`, `~/Library/Android/sdk`, `-gpu host` — it has been replaced rather than
> annotated, so this file can be read on its own.

---

## Environment

The build runs **inside WSL2**, not on Windows. The repository, the JDK, the Android SDK, Gradle and
Docker all live on the Linux side; Windows currently has no Android tooling installed at all. The
machine has 16 cores and 30 GB of RAM, so the build itself is comfortable.

| | |
|---|---|
| Host | Windows + WSL2 (kernel 6.18.33.2-microsoft-standard-WSL2), Linux user `karatektus` |
| Repositories | `~/pl_mail_android` (this), `~/pl_mail` (server). **Not** under `~/Documents/` |
| JDK | **Temurin 21.0.12 LTS** at `~/.local/jdks/current-21` (a symlink to `jdk-21.0.12+8`) |
| Android SDK | `~/Android/Sdk` — `ANDROID_HOME` **is** exported, unlike the macOS host |
| Platforms | `android-37.0` and `android-36`. Note there is no bare `platforms;android-37` package any more — Android uses minor-versioned platforms, so `compileSdk = 37` resolves against **`android-37.0`** |
| Build-Tools | 36.0.0 and 36.1.0 · Platform-Tools 37.0.1 |
| System image | `system-images;android-36;google_apis;**x86_64**` — x86_64, not arm64. This is an AMD machine |
| AVDs | `plmail_api36` (phone, 1080×2400) and `plmail_tablet_api36` (10.1" WXGA, 1280×800) |
| Docker | Installed and **running**; the user is in the `docker` group |

### Shell environment

`~/.bashrc` (not `~/.zshrc`) carries:

```bash
export JAVA_HOME="$HOME/.local/jdks/current-21"
export ANDROID_HOME="$HOME/Android/Sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
```

**Gradle's JVM is pinned in `~/.gradle/gradle.properties`, not by `JAVA_HOME`, and that is
load-bearing.** A root-owned `/etc/profile.d/jdk20.sh` exports `JAVA_HOME=/opt/jdk-20.0.1` into every
shell on this box and cannot be edited without a sudo password. The `.bashrc` export above overrides
it — but only for *interactive* shells, because `.bashrc` returns early when non-interactive. A
script, a `bash -c`, or an IDE-launched build would otherwise silently compile on JDK 20. So:

```properties
org.gradle.java.home=/home/karatektus/.local/jdks/current-21
org.gradle.java.installations.paths=/home/karatektus/.local/jdks/current-21
```

`local.properties` in the repo carries `sdk.dir=/home/karatektus/Android/Sdk` and is gitignored.

### The emulator, and the one real Windows decision

Two things differ from the macOS host and are **forced, not preferences**:

- **No GPU.** There is no `/dev/dri` under this WSL2 kernel, so `-gpu host` has no device to bind to.
  Software rendering (`-gpu swiftshader_indirect`) is the only option inside WSL2. It boots and it
  works; it is not fast.
- **`sg kvm`.** `/dev/kvm` is `root:kvm` 0660 and the `kvm` group was added to the user after this
  session's shells had already started. `sg kvm -c '…'` enters the group without a relogin. Once you
  have logged out and back in this is unnecessary.

`~/Android/run-emulator.sh` wraps both, plus `-no-window` (there is no usable display target for the
emulator UI here — drive it with `adb` and `screencap`):

```bash
~/Android/run-emulator.sh                      # phone
~/Android/run-emulator.sh plmail_tablet_api36  # tablet
```

> **The open decision for this host: where the emulator should run long-term.** A software-rendered
> emulator inside WSL2 is usable for install-and-screenshot verification but is poor for anything
> interactive, and it will be painful at M3–M4 when real UI work starts. The better arrangement on
> Windows is to install **Android Studio and the emulator on the Windows side**, where the emulator
> gets WHPX hardware acceleration and the real GPU, and keep building in WSL2 — connecting to the
> Windows-side adb server over TCP from WSL2. That splits the toolchain across the boundary and needs
> its own small setup (a second SDK on Windows, `adb -H` or `adb connect`, and a shared or duplicated
> APK path). It is worth doing before M3. **Not done yet; flagged rather than assumed.**

### Verified working, on this host

`./gradlew build` compiles every module, R8-minifies the release APK and passes lint. The
`:core:jmap` suite is **108 tests, 0 failures, ~5 seconds**, no emulator. The debug APK installs and
runs on `plmail_api36`.

One pre-existing failure, which is project code rather than environment: **`./gradlew build` fails on
`:core:database:testDebugUnitTest`**. `core/database/src` contains only `main/kotlin` — the module
has no `src/test` directory at all, and Gradle 9 fails a `Test` task that discovers nothing.
`./gradlew build -x :core:database:testDebugUnitTest` is green meanwhile. **M2 closes this properly**
by giving the module its first real unit tests, which is the right fix; relaxing the check globally
would work against a deliberate value in this repo, where a JUnit BOM/launcher mismatch showing up
as "no tests found" is meant to be loud.

---

## Dedicated test environment

The server checkout is at `~/pl_mail`. Its **full stack is currently running** as compose project
`pl_mail` — FrankenPHP/Caddy on 80/443, Postgres on 5432, Mercure, mailer on 1025/8025, adminer on
8080. That is the user's own instance, reachable at `https://localhost`, and it is **not** what
automated work should point at.

`compose.test.yaml` is its own compose project with its own Postgres, and its port is
`${TEST_HTTP_PORT:-8001}`. A second, fully isolated stack needs no new compose file — a different
project name gives it its own volumes:

```bash
cd ~/pl_mail && TEST_HTTP_PORT=8002 docker compose -p pl_mail_android -f compose.test.yaml up -d --build --wait app
```

- Serves at `http://127.0.0.1:8002` → **`http://10.0.2.2:8002`** from the emulator. The emulator's
  `10.0.2.2` alias resolves to the WSL2 host, which is where the container's port is published, so
  this works unchanged from the macOS arrangement.
- Own `database_data`, `app_var`, `app_public_assets` volumes — nothing shared, so `down -v` resets
  it without touching the user's data or the iOS app's.
- Seed with `app:test:seed-user`, `seed-mail`, `seed-label`, `seed-attachment`, `clear-drafts` via
  `docker compose -p pl_mail_android -f compose.test.yaml exec -T app php bin/console …`.
- Credential: `app:test:seed-api-token -q` prints a bare `plmail_…` secret. Or the real pairing flow,
  which is what M2 implements and therefore the better one to test against — `app:device:pair <email>
  --base-url=…` prints a `plmail://pair?host=…&code=…` URI and `POST /device/pair` exchanges it.
- **Session URLs follow the request `Host` header**, so a request from the emulator to
  `10.0.2.2:8002` gets `apiUrl: http://10.0.2.2:8002/jmap/api` back. Reading URLs from the session
  rather than hardcoding them is what makes one credential work from the emulator and from a phone on
  the LAN.

### Why not just point at `https://localhost`

It works for a `curl` check — a valid app password returns a full session object from
`https://localhost/jmap/session` — but it is a trap for the app, for two reasons that are both
consequences of `ServerTrust` doing its job:

1. The certificate is issued by "Caddy Local Authority" and is **short-lived (~12 h)**. `ServerTrust`
   pins the leaf's **SPKI**, so a Caddy renewal that rotates the key turns a stored pin into
   `PinMismatch` — which the code makes deliberately terminal and un-askable. Correct behaviour,
   miserable as a daily driver.
2. Its only SAN is `DNS:localhost`. `ServerTrust` deliberately keeps OkHttp's hostname verifier, so
   reaching it from the emulator as `https://10.0.2.2` fails name verification. The workaround is
   `adb reverse tcp:8443 tcp:443` and addressing it as `https://localhost:8443` (the port is not part
   of SAN matching). Note `adb reverse tcp:443` fails with `EACCES` — binding a privileged port on
   the device needs `adb root`.

The 8002 stack is plain HTTP and has neither problem.

> **`pl_mail` is being committed to concurrently by other sessions.** A parallel session once ran a
> broad `git add` and swept an untracked file into an unrelated commit. So: no writes to `pl_mail`
> from this session without saying so first, and any server-side ask gets checked against `git log`
> immediately beforehand — the repo can move between reading it and acting on it.

---

## Push architecture

RFC 8030 requires a *push service*: something that owns the endpoint URL, holds the persistent
connection to the device, and receives the server's encrypted POST. Browsers ship one. A native
Android app does not, and Android's system-level service is FCM, which speaks its own protocol and
needs a Firebase project plus a server-side sender — `WebPushSender.php` cannot POST to it.

UnifiedPush fills exactly that gap, and is the only option requiring **no server change**: connector
v3 generates the P-256 keypair, the distributor supplies an RFC 8030 endpoint, and the library
decrypts RFC 8291 `aes128gcm` — precisely what `WebPushSender` already emits.

| Approach | User installs | Cost | Plan |
|---|---|---|---|
| **External distributor** (ntfy, self-hostable) | One app | A second app install | **M6** |
| **Embedded distributor** | Nothing | Foreground service + permanent notification, battery | Structured for, added if the install step proves a real barrier |
| **FCM** | Nothing; what Android users expect | Firebase project + an FCM sender in plMail | On the asks list |

A distributor app (**ntfy**, self-hosted or ntfy.sh) must be installed on the test device before M6.
The push path is behind one interface either way, so which of the three is in use changes the
registration source and nothing downstream: a push carries only
`{"@type":"StateChange","changed":{…}}` and always triggers the same `Email/changes`.

---

## Decisions taken

| | |
|---|---|
| Language / UI | Kotlin 2.x (K2), Jetpack Compose, Material 3 (+ adaptive) |
| minSdk / targetSdk / compileSdk | **31** / **36** / **37**. JDK 21 toolchain, Java 17 bytecode target. SDK Platform 37 (Android 17) is a final release and installed, but no API 37 emulator image is published — so we compile against 37 for the newest APIs and lint checks, and target 36, the highest level we can actually run. Bump `targetSdk` to 37 once an image ships |
| Form factors | Phone + tablet/foldable, `NavigableListDetailPaneScaffold` |
| applicationId | `de.plmail` (matches iOS `PRODUCT_BUNDLE_IDENTIFIER`), namespaces `de.plmail.<module>` |
| Version | `versionName 0.1.0`, `versionCode 1` |
| DI | Hilt |
| Persistence | Room (KSP) + DataStore(Preferences); credential in an Android Keystore-wrapped blob |
| Networking | OkHttp + kotlinx.serialization. **No Retrofit** — JMAP is one POST endpoint with a batch body, not a REST surface |
| Lists | Paging 3 with `RemoteMediator` over the Room feed table |
| Background | WorkManager (periodic) + SSE (foreground only) + **UnifiedPush** (background delivery) |
| Style | Spotless with **ktfmt `kotlinlangStyle`** — 4-space, per the Android Kotlin style guide. *Not* ktfmt's `googleStyle`, which is 2-space: correct for Google-internal Kotlin, wrong for Android, and it disagrees with the `kotlin.code.style=official` that Studio reads from `gradle.properties`. Android Lint, `-Werror` on Kotlin warnings |
| Tests | JUnit5 + kotlin.test + Turbine (JVM), Robolectric (Android unit), Compose UI tests + Roborazzi screenshots, `androidTest` for Room migrations |

---

## Module layout

Mirrors Now in Android, and mirrors the boundary the iOS app cares most about: the protocol layer
depends on nothing, so its tests run on the JVM in seconds instead of booting an emulator.

```
:app                    Application, MainActivity, NavHost, Hilt graph, manifest, network config
:core:jmap              ★ pure kotlin("jvm") — protocol, codec, client, transport, SSE, TOFU trust
                          + testFixtures: FakeMailServer, RecordingTransport
:core:model             pure kotlin("jvm") — domain types shared by data and ui
:core:database          Room entities, DAOs, migrations
:core:datastore         settings + Keystore-backed credential store
:core:data              repositories, DeltaSync, UnifiedFeed, AccountPager, search compiler, blob cache
:core:designsystem      theme tokens, Material 3 theming, primitive composables
:core:ui                shared stateful composables (thread row, avatar, undo host, empty states)
:core:notifications     UnifiedPush receiver, channels, notification actions
:core:testing           Hilt test runner, fakes, Robolectric config, Roborazzi rules
:feature:onboarding  :feature:mail  :feature:search  :feature:compose  :feature:labels  :feature:settings
:benchmark              Macrobenchmark + baseline profile (M11)
```

Modules land when the milestone needs them, not all at once. Build logic lives in `build-logic/`
convention plugins (`plmail.android.application`, `.library`, `.compose`, `.hilt`, `.room`,
`plmail.jvm.library`) so no module repeats an `android {}` block; dependencies in
`gradle/libs.versions.toml`.

**`:core:jmap` is the highest-test-value code and must stay Android-free.** The module boundary is
what enforces it: an accidental `import android.*` fails to compile.

---

## Milestones

Each milestone ends green: `./gradlew build test lint spotlessCheck` clean, and the app runnable on
both AVDs. Each is one commit (or a small series), with tests written alongside — not after.

### M0 · Toolchain, skeleton, test environment — **done**
`git init`, `.gitignore`, `README.md`, Gradle wrapper 9.6.1, version catalog, `build-logic/`
convention plugins, Spotless/ktfmt, `:app` with a placeholder screen.

### M1 · `:core:jmap` — the protocol layer — **done**
The whole wire surface, pure JVM, heavily tested against fixtures captured from the real server.

- `Session` discovery (`/.well-known/jmap`), cached and **single-flighted** so ten callers at launch
  cause one request; invalidated on foreground and after 401.
- `Credential`: `Bearer plmail_…` and `Basic base64(email:token)`.
- `RequestBuilder` with **back-references** (`#ids` / `resultOf`), `MethodResults` decoding, per-call
  error extraction, `application/problem+json` mapping to typed errors.
- `RequestGate`: a semaphore honouring the session's `maxConcurrentRequests` (4), with a reservation
  the SSE connection holds for its lifetime.
- Types: `Email`, `EmailAddress`, `Mailbox` (incl. plMail's `labelId`), `Thread`, `Identity`,
  `Keyword`, `EmailFilter`, `Comparator`, `UTCDate`, `JMAPIDSet`, `JSONValue`.
- Methods: `Email/{get,query,changes,set}`, `Mailbox/{get,changes,set}`, `Thread/{get,changes,set}`,
  `EmailSubmission/set`, `Identity/get`, `PushSubscription/{get,set}`.
- Blob upload/download URL templating; `EventSourceClient` (SSE, reconnect with backoff, hard 300s
  server close treated as normal).
- **`ServerTrust`**: trust-on-first-use pinned to the **SPKI SHA-256**, not the certificate — a
  `certbot renew` keeps the key, and re-prompting every 90 days teaches users to tap "trust" blind.
  A custom `X509TrustManager` delegating to the platform trust manager first, then the pin, installed
  on OkHttp. A *changed* pin is a permanent hard failure, never a prompt.
- `testFixtures`: `RecordingTransport`, `GatedTransport`.

**Wire behaviour discovered against the live server** — fixtures in
`core/jmap/src/test/resources/jmap/` (14 files). These contradict the obvious assumption and are
encoded as tests; do not re-derive them:

1. **`Email/get` does not preserve requested order.** A query returns `[5,1,2,3,4]` newest-first; the
   back-referenced get returns `[1,2,3,4,5]`. Use `EmailGetResult.ordered(ids)`.
2. **`Thread/get` reorders too**, and not into id order (`[5,1,2,3,4]` → `[4,3,2,1,5]`). The spec doc
   only warns about `Email/get`.
3. **`Email/changes` from state `"0"` reports nothing** about existing mail. A fresh client is
   populated by `Email/query`; `/changes` only keeps an already-populated one current.
4. **`accountId` must be a JSON string.** An integer is rejected with `invalidArguments` and **no
   description**.
5. **`fetchHTMLBodyValues`** — that capitalisation. The wrong spelling is silently ignored and
   returns empty body values with no error.
6. **Unknown keyword and unsupported `anchor` paging both return bare `unsupportedFilter`** with no
   description — indistinguishable.
7. `WWW-Authenticate` includes `charset="UTF-8"`, which the doc omits.
8. `Email.size` is `0` on seeded data. `mailboxIds`/`keywords` are objects (`{}` not `[]`).
9. **`in:archive` means "carries no Inbox label"** — archiving removes Inbox and adds nothing. The
   Archive label is IMAP folder bookkeeping.

### M2 · Persistence, credentials, onboarding — **in progress**
`:core:database` currently has the 9-table Room schema, DAOs and the exported schema JSON, and
nothing else. Remaining:

- Repository layer over the DAOs; mappers JMAP ↔ entities. Composite keys
  `"<server>/<accountId>#<objectId>"` — JMAP ids are unique only within an account and one login
  reaches several.
  **The schema rule is load-bearing: everything here is a cache.** Every row must be reconstructible
  from the server, which is what licenses "on migration failure, drop and re-sync" and means no
  hand-written migration ever has to preserve data. Wanting a column the server doesn't know about is
  the signal to ask for a server change.
- Credential store: token encrypted with an Android Keystore AES-GCM key
  (`setUserAuthenticationRequired(false)`, `StrongBox` when available), ciphertext in DataStore.
  *Not* `androidx.security:security-crypto` — it is deprecated.
- `network_security_config.xml`: trust `system` **and** `user` CAs (private-CA NAS installs are the
  target audience), cleartext permitted but onboarding shows an explicit unencrypted-connection
  warning for `http://` addresses. Note the app has **no** `networkSecurityConfig` today, so
  `http://10.0.2.2:8002` is currently blocked outright — this is what unblocks testing against the
  8002 stack.
- **Onboarding is QR pairing first, paste second.** Settings issues a short-lived single-use code,
  the QR carries `plmail://pair?host=…&code=…`, and the app exchanges it at public `POST /device/pair`
  for a freshly minted app password. The QR never contains the password. Register the `plmail://`
  scheme as a deep link (tapping the code on the same device skips the camera), scan with CameraX +
  ML Kit, keep manual paste as fallback. Typing 71 characters of base16 onto a phone keyboard is the
  worst moment in onboarding and this removes it.
- Address entry with normalisation (bare host → `https://`, `/.well-known/jmap` appended), **verify
  before saving anything** — show the username and connected accounts back, because "was that
  `nas.local` or the other `nas.local`" is a real question for this audience — and the cert-pin
  prompt showing the fingerprint in `openssl`-comparable hex.
- Room migration test (`androidTest`), which needs a booted AVD.

*Verify:* onboarding against `http://10.0.2.2:8002`; `connectedDebugAndroidTest` for the migration.

### M3 · The unified inbox
- `AccountPager` (one `Email/query` + `Email/get` per account, back-referenced into one round trip)
  and `UnifiedFeed`: a k-way merge whose invariant is *no row may be emitted until every source has a
  visible head or is known-exhausted*, with cursors that advance **on emit, not on fetch**, so a kill
  mid-scroll re-fetches rather than leaving a hole. Boundary ids handle messages sharing one second
  across a page boundary (`before` is a strict `<`).
- Paging 3 `RemoteMediator` over the feed table; instant cold-launch list from cache.
- Thread row: participants **oldest first** (not the newest sender — that made every answered thread
  look like it came from you), subject with "(no subject)" fallback, snippet, message count, date,
  unread/starred/attachment affordances, letter avatar coloured by a hash of the *address* (not the
  display name, which changes when someone reconfigures their client).
- Adaptive shell: `NavigableListDetailPaneScaffold`, modal nav drawer on phone, permanent rail on
  tablet. Back is a real navigation step, always.
- **One failing account must never blank the list.** Per-account failure surfaces as a banner; the
  other accounts keep rendering.

*Verify:* feed-merge unit tests (duplicate at page boundary, one account failing, one account empty,
resume after process death); Roborazzi screenshots of the row states.

### M4 · The reader
- Thread reader: newest expanded, older collapsed; read-on-display (**after it is actually shown**,
  never on prefetch).
- Message body in a per-message `WebView`: JS off, `WebViewAssetLoader`, remote images blocked behind
  a per-sender opt-in, inline `cid:` images from the blob cache.
- **The dark-render strategy, per message**: profile the HTML, then `original` / `darkRestyled`
  (message brought no colours — restyle, nothing inverted, best result) / `darkInverted` (`invert` +
  `hue-rotate(180deg)`, **and invert `img/picture/video/svg`/background-image elements back** — the
  rule everyone forgets) / `darkNative` (message declares `prefers-color-scheme`; just tell it the
  scheme, via `WebSettingsCompat.setAlgorithmicDarkeningAllowed`). Always offer "show original"
  wherever the rendering was transformed. Port `MessageColorProfile.swift`.
- Attachments list; original RFC822 source via the `m-<id>` blob. Only `image/*` is served inline by
  the server — never assume inline rendering of an arbitrary type in a WebView.

### M5 · Actions, undo, bulk
Archive (= remove the Inbox mailbox id; *not* add Archive), trash (= `destroy`, which is a move to
Trash — there is no hard delete anywhere in the product and the UI must say Trash), star, read/unread,
mark spam. **Local first, then send**; every one undoable via a snackbar for ~6 s; a server rejection
is surfaced, not logged, because the row already moved. Swipe-to-archive / swipe-to-trash with
configurable actions, each also reachable as an explicit control. Multi-select + bulk actions with
`ifInState` conflict detection.

### M6 · Staying current
- `DeltaSync`: `Email/changes` loops (256 rows/call, `hasMoreChanges`), hydration in chunks of 100
  (well under the permitted 500 — the server hydrates full Doctrine entities and this is someone's
  Raspberry Pi), `cannotCalculateChanges` → drop cursors and re-page, and a loop ceiling after which
  re-paging is cheaper than catching up.
- SSE **foreground only**, disconnected on background. Each connection holds a PHP worker for its
  life; the server hard-closes at 300 s and expects a reconnect. `?closeafter=state` for a cheap
  resync without holding a connection.
- `WorkManager` periodic sync at 15 min (matching the server's own schedule — being more eager buys
  nothing, the mail isn't there yet), network-constrained, backoff on failure.
- **UnifiedPush**: `org.unifiedpush.android:connector` v3. Register → get `endpoint` + `p256dh` +
  `auth` → `PushSubscription/set` → **complete the verification handshake** (the server immediately
  POSTs a `PushVerification` to the URL; echo the code back via an update, or the subscription
  receives nothing forever — this is what stops the endpoint being an open relay). Graceful, explicit
  degradation when no distributor is installed → WorkManager only, and the UI says "last checked",
  never "up to date".
- Notifications: per-account channels, grouped summary, actions (archive, mark read, reply).

### M7 · Search
Gmail operator syntax parsed client-side into JMAP filters — `SearchQuery` / `SearchQueryCompiler`
are **already written** in `:core:jmap`; this milestone is the UI over them. `SearchSnippet/get` for
highlighted results. Filter chips, recent searches, per-account scoping. Empty results mention the
sync window when the query had a date component — mail older than an account's window is not in the
database and therefore not searchable, and "no results" is a dishonest answer to that.

### M8 · Compose
Rich text, reply / reply-all / forward with proper quoting and `In-Reply-To`/`References` (a reply
that omits them starts a new conversation), **always-visible From picker** over `Identity/get`
(accounts have multiple sendable aliases), draft autosave through `Email/set`, attachment upload at
**send** time (staged blobs are swept by `app:prune:blobs`, so a draft left open overnight would send
with its attachments already collected), contact autocomplete from locally-harvested addresses plus
the OS address book, client-side undo-send window (the server deliberately does *not* apply the web
UI's grace period to JMAP submissions), fullscreen on phone / dialog on tablet.

### M9 · Organising
Labels: apply, remove, create, delete, colour. **Collapse one label across accounts using
`Mailbox.labelId`**, never by matching on `name` — that breaks the moment the label is renamed in one
account. Sidebar order is fixed for system labels (Inbox 0, Sent 10, Drafts 20, Spam 30, Trash 40,
Archive 50 — created hidden), custom labels alphabetical after. Snooze via `Thread/set` with presets
+ exact time. Nested labels flat-with-paths.

### M10 · Appearance and settings
The two-axis **Theme × Layout** model with density and knobs on top, in a `LocalPlMailTheme`
`CompositionLocal` over semantic tokens (`surface`, `line`, `raised`/`hover`,
`ink`/`ink-soft`/`ink-muted`/`ink-faint`, `accent*`, `sunken`, `field*`,
`danger`/`warning`/`success`/`info`, `inverse*`) — never raw palette values, so one theme change
re-resolves everything. Six themes (system, light, dark, nord, dusk, solar) + Material You dynamic
colour as an additional user choice; two layouts (flat, boxed); three densities. **Radius applies to
panes, not controls** — buttons, chips and rows keep a fixed small radius. Honour
reduced-transparency (forces alpha 1, blur 0) and reduced-motion. Driven by local settings today,
from the server's `Appearance` the moment it is exposed — that swap must touch the resolver and
nothing else. Plus: account list and order, sync window display, notification prefs, and a
diagnostics screen (per-account last sync, last error, push state) — users self-host, so when
something breaks they are the one who has to fix it.

### M11 · Polish and ship-readiness
German + English strings from M0 onward, checked (design for German ~30% longer); TalkBack and
contrast sweep across all six themes; ≥48 dp targets, ≥16 sp body; predictive back; app shortcuts and
a home-screen unread widget; offline as a first-class state (cached mail, queued mutations, plain
"can't reach your server" naming the hostname); R8 rules; baseline profile + Macrobenchmark;
Roborazzi screenshot suite across themes × densities × phone/tablet.

---

## Non-negotiables

Straight from §4 of the client doc — these are where a client goes wrong quietly:

- Never build against the HTML/Turbo routes (`/mail/*`, `/compose/*`, `/settings/*`). They are
  internal and unversioned.
- Never hardcode `apiUrl`, `uploadUrl`, `downloadUrl`, `eventSourceUrl` — read them from the session
  every time; the user's reverse proxy is theirs to reconfigure.
- Never parse `blobId`. Never assume one account. Never invent keywords. Never implement hard delete.
- Never hold SSE open in the background, and never poll aggressively — this is someone's Raspberry Pi
  with a single PHP worker pool.
- Never pass the user's theme into the message renderer.
- **Never work around a missing server feature without asking first.**

---

## Server-side asks

Per §0, these are normal outcomes, not favours — and they are approved in principle. Each lands as
its own small commit on its own branch in `pl_mail` when its milestone reaches it, never mixed into
Android work:

| Ask | Milestone | Why |
|---|---|---|
| `Appearance` over JMAP (the export format already exists) | M10 | Otherwise the app cannot honour the theme the user set on the web |
| `Email/queryChanges` | M6 | Without it, refreshing a list means re-running the whole query |
| Contact autocomplete endpoint (server already harvests them) | M8 | No JMAP Contacts; the alternative is a worse local-only guess |
| JWT issuance endpoint | M2 | Android is first-party; the plumbing exists, only the endpoint is missing |
| Scheduled send (`maxDelayedSend` is 0) | M8 | Gmail parity |
| Vacation responder (`VacationResponse/*`) | M10 | Gmail parity |
| Mail rules / block sender over JMAP | M9 | Exists server-side, no client surface |
| An FCM sender alongside Web Push | M6 | UnifiedPush needs a distributor app; FCM is what most Android users expect |
| Update `CLIENT_DEVELOPMENT.md` — `Thread/set` and `SearchSnippet/get` now exist | M1 | The doc lists them as absent |

Also outstanding on the server: `SeedTestEmailCommand.php:110` does `->setEmail('E2E Mailbox')` — a
display name in the account's email column — so `Identity/get` hands clients a non-address as the
From identity. The user said they would fix this; check whether they have before M8.

---

## Open decisions

1. **Where the emulator runs** (see Environment). Software rendering in WSL2 works now but will hurt
   from M3. Moving the emulator to the Windows side is the likely answer. Due before M3.
2. **Rich-text compose** (M8). Compose has no rich-text editor, and the server round-trips HTML
   bodies.

   | Option | Trade |
   |---|---|
   | `richeditor-compose` (Apache-2.0, maintained, HTML in/out) — **recommendation** | One third-party dependency in the compose path; saves weeks and it is the only option that already speaks HTML |
   | `contenteditable` in a WebView | Highest HTML fidelity and closest to the web composer, but a WebView inside a Compose form fights focus, IME and accessibility |
   | Hand-rolled on `AnnotatedString` | No dependency, full control; we write HTML serialisation ourselves for bold/italic/lists/links/quoting — a lot of work for a solved problem |

3. **`in:archive` and trashed mail.** "No Inbox label" is literally true of trash and spam too, so
   `in:archive` returns those. Decide: leave literal / exclude trash+spam / match the sidebar's
   Archive view. A related uncommitted change may exist in `pl_mail` at
   `src/Repository/Mail/MessageThreadRepository.php` — verify against `git log` before touching.
4. **`LabelRole::Archive` rename** to something like `ImapArchiveFolder` — the name collision (IMAP
   folder vs. "archived") is what produced the `in:archive` bug. Needs a migration. Not started.

---

## Verification

```bash
./gradlew spotlessApply build test lint
```

- **Fast loop** — `./gradlew :core:jmap:test` runs the entire protocol suite on the JVM in seconds,
  no emulator. This is deliberate and must stay true.
- **Instrumented** — `./gradlew connectedDebugAndroidTest` for Room migrations and Compose UI tests
  (needs a booted AVD).
- **Screenshots** — `./gradlew verifyRoborazziDebug`; `recordRoborazziDebug` to re-baseline.
- **Against a real server** — the isolated 8002 stack, so nothing touches real mail.

Emulator loop on this host:

```bash
~/Android/run-emulator.sh &
adb wait-for-device
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n de.plmail.debug/de.plmail.MainActivity
adb shell screencap -p /sdcard/s.png && adb pull /sdcard/s.png
```

**Do not run two Gradle invocations against this checkout concurrently** — parallel agent sessions
cause build-cache collisions. Serialise them.

**One gap to be honest about:** the only installed emulator image is Android 16, so the API 31 floor
is verified at *compile* time only — Android Lint's `NewApi` check plus `@RequiresApi` discipline,
which catches unguarded API use but not runtime behaviour differences. Every version-gated code path
gets an explicit comment saying what it guards and why. If a minSdk-floor bug is ever suspected,
`sdkmanager "system-images;android-31;google_apis;x86_64"` is a ~1.5 GB download away.

---

## House style

Commit messages are imperative sentences with substantial bodies explaining *why*, matching `pl_mail`
and `pl_mail_ios`. End with `Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>`.

**Comments explain why, never what** — specifically what went wrong or would go wrong otherwise. Both
existing repos are dense with rationale and never restate the code. Match that; it is the strongest
convention in this project.
