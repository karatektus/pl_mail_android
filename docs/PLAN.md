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

### The accelerated emulator lives on Windows

Settled on 2026-07-31, because software rendering was never going to survive M3–M4. There is now a
**second, Windows-side SDK** whose only job is to run the emulator:

| | |
|---|---|
| Windows SDK | `C:\Users\mail\AppData\Local\Android\Sdk` — emulator 37.1.11, platform-tools 37.0.1, `system-images;android-36;google_apis;x86_64` |
| Windows JDK | `C:\Users\mail\.jdks\temurin-21`, needed only to run `sdkmanager.bat` |
| AVD | `plmail_win_api36`, `hw.gpu.mode=host`, 4 GB RAM, `hw.camera.back=virtualscene` |
| Helpers | `C:\Users\mail\plmail-emulator.bat`, `plmail-adb-server.bat`, `plmail-sdkmanager.bat` |

WHPX was already installed and usable, so nothing had to be enabled for acceleration. The emulator
renders through the machine's **RTX 3080** — confirmed from `dumpsys SurfaceFlinger`, which reports
`Android Emulator OpenGL ES Translator (NVIDIA GeForce RTX 3080)` rather than SwiftShader.

The back camera is `virtualscene`, not the `emulated` default `avdmanager` writes: the latter binds
and streams perfectly while rendering nothing, so a QR scanner tested against it looks broken for a
reason that has nothing to do with the app.

Building still happens in WSL2. The only awkward part is adb, because WSL2 is in `nat` mode and does
not share localhost with Windows:

1. The Windows adb server runs with `-a`, so it listens on `0.0.0.0:5037` instead of `127.0.0.1`.
2. `~/Android/use-windows-emulator.sh` puts a socat relay on the WSL side's own 5037. Anything in
   WSL that talks to `127.0.0.1:5037` then reaches it — including AGP, which uses ddmlib over TCP
   and does **not** honour `ADB_SERVER_SOCKET`, so a relay is the only approach that needs no
   Gradle flags.
3. WSL's Hyper-V firewall defaults to `DefaultInboundAction = Block`, which is what stops the relay
   connecting. One elevated rule fixes it permanently:

```powershell
New-NetFirewallHyperVRule -Name "adb-from-wsl" -DisplayName "adb server (WSL -> Windows)" `
  -Direction Inbound -VMCreatorId '{40E0AC32-46A5-438A-A0B2-2B479E8F2E90}' `
  -Protocol TCP -LocalPorts 5037 -Action Allow
```

Mirrored networking (`networkingMode=mirrored`) would remove the need for both the relay and the
rule, and is deliberately **not** used: it is known to disturb Docker bridge networking inside WSL,
and the pl_mail server stack runs there.

The WSL-side emulator and `~/Android/run-emulator.sh` stay as they are. They need no firewall rule
and no Windows session, which makes them the better choice for a quick headless install-and-
screenshot check; the Windows AVD is for anything interactive.

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

**Up and verified on 2026-07-31**, seeded with `seed-user`, `seed-mail` (4 inbox threads),
`seed-label` and `seed-attachment`. The app onboarded against it end to end from the emulator.

- Serves at `http://127.0.0.1:8002` → **`http://10.0.2.2:8002`** from the emulator, and that still
  holds now the emulator runs on Windows, though for a longer reason: `10.0.2.2` is the *emulator
  host's* loopback, which is Windows, and the container is published on WSL. WSL2's default
  `localhostForwarding` bridges the two, so Windows `localhost:8002` reaches the WSL container and
  the emulator reaches it through that. If localhost forwarding is ever turned off, this breaks and
  the address becomes WSL's own IP.
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
:core:designsystem      ★ semantic tokens, PlMailTheme, primitive composables — landed early, see M10
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

### M-Design · The look, brought forward from M10 — **tokens landed, conversion in progress**

**Reordered on 2026-08-01, deliberately.** The plan had the appearance model arriving at M10, after
every screen had been built against raw Material defaults. That ordering was wrong: each screen
built before the tokens exist is a screen that has to be retrofitted, and retrofits are where
inconsistency becomes permanent. So `:core:designsystem` landed first and the existing screens are
being moved onto it.

Look and feel is a **first-class requirement**, not polish. Parity with Gmail is about *capability*;
the visual language is its own and should read as better than a Material default.

What that means concretely, and what the module implements:

- **Warm neutrals.** Every neutral has more red than blue in it — a warm off-white that reads as
  paper, and a dark scheme that is a warm near-black rather than an inverted blue-grey. A test
  asserts it, because a neutral picked from a tool comes back cold and nobody can name what changed.
- **One accent**, a deep green, used scarcely: the active navigation item, a link, an unread dot,
  the compose button's tint. Never a large filled area — the FAB is tonal for exactly that reason.
- **Hierarchy from weight and colour, not size.** The four-step ink scale (`ink`, `inkSoft`,
  `inkMuted`, `inkFaint`) is a hierarchy of *meaning*: what the message is, what it is about, its
  metadata, its furniture.
- **Hairlines and surface shifts, never elevation.** No drop shadows anywhere, including on the FAB.
- **Motion** at 120/200/320ms with a restrained ease-out, collapsing to zero when
  `ANIMATOR_DURATION_SCALE` says the user has asked for stillness.
- **≥48dp targets and ≥16sp body**, and every pair the app draws clears WCAG AA in both schemes —
  `PaletteContrastTest` computes the real relative-luminance ratios rather than trusting an eye.
- **Radius applies to panes, not controls.** Enforced by the token type and asserted in a test.

Converted so far: the thread row (`:core:ui`, drawn by two features and therefore the right first
test of whether the tokens suffice), the mail list, the reader, the composer, search and onboarding.
Roborazzi now records **light and dark** for every row case, which is what stops a colour that was
only ever looked at in one scheme from shipping.

Still to do: the six-theme × two-layout × three-density *chooser* is M10's, along with the settings
screen that drives it. The resolver is built for it — `PlMailThemeChoice` names the three schemes
that exist rather than promising six nobody can select yet.

### M8 · Compose — **done**
Rich text (`richeditor-compose`, the open decision below, settled), reply / reply-all / forward with
quoting and `In-Reply-To`/`References`, an always-visible From picker, autosave to Drafts through
`Email/set`, attachments staged locally and uploaded at **send**, contact autocomplete from cached
mail plus the OS address book, and a client-side undo-send window.

**Four server behaviours were established by probing the running instance, and three of them succeed
while doing nothing.** All four are written up in `docs/SERVER_REQUESTS.md`; the short version:

1. `Email/set` **update** accepts `attachments`, answers `updated`, and drops them. Only a `create`
   attaches. So a change to the attachment set recreates the draft and bins the old one.
2. Neither the draft's `from` nor the submission's `identityId` reaches the sent message — it always
   goes out as the account's address. The picker therefore offers one entry per *account*.
3. `destroy` on a draft adds Trash and removes **Inbox**, which a draft never had, so it stays in
   Drafts as well. Discarding is an explicit `mailboxIds` patch instead.
4. A back-reference to a single created id resolves to a bare string, which `Email/get` rejects with
   an undescribed `invalidArguments`. Submitting a draft created in the same request uses the
   creation-id form (`"#c1"`), which is a different mechanism and does work.

The undo window writes the draft to Drafts **first**, then waits, then submits — so a process death
inside those seconds leaves the mail in Drafts rather than losing it.

The tablet presentation landed on 2026-08-01: `ComposeHost` decides from the window size class —
both axes, so a phone in landscape stays full screen — and presents the composer as a dialog over
the mailbox where there is room. Scheduled send remains blocked on the server (`maxDelayedSend` is
0).

### M9 · Organising — **in progress**

Landed on 2026-08-01:

- The **label list as the app's navigation**. `Labels.kt` collapses per-account Mailboxes into one
  `Label` on `labelId`, never on `name`, with twelve tests pinning the ways name-matching fails.
  Fixed order for system roles, alphabetical for the user's own, nested labels flat-with-paths. The
  bottom bar is gone: a label list is as long as the user made it, so it is a modal drawer on a
  phone and a permanent one where there is room.
- **Browsing any label.** `FeedRepository.labelled()` generalises the unified inbox; accounts with
  no binding for a label are dropped from the merge rather than queried unfiltered.
- **Apply and remove**, tri-state over a multi-selection — "on some" ticks up, never down.
- **Create, rename and delete**, deepest-first so the server's `mailboxHasChild` never surfaces.
- **Snooze** via `Thread/set`, four presets computed in the device's own zone plus an exact time,
  and unsnooze from the Snoozed list.

Still open: **colour is blocked on the server** — absent from `Mailbox/get`, refused on `update`,
silently dropped on `create`. Filed in `docs/SERVER_REQUESTS.md`. Also outstanding: the label sheet
and the snooze menu are reachable from the selection bar but not yet from inside the reader, and
mail rules / block sender have no client surface.

Undo of a snooze was shipped without ever being watched work, and it did not. Closed on 2026-08-01,
and the three defects behind it are worth remembering because none of them was in the undo code:
`Email/get` cannot answer what a conversation is snoozed until, so every page rebuilt the row with
the value missing; the feed row was never put back, so the way back moved nothing on screen; and
the snackbar asked for Material's `Short`, which is four seconds, under a comment claiming six.
Every page and every delta sync now carries a `Thread/get` back-referenced off the message get, and
the undo plan is worked out before the local write rather than derived from it afterwards.

### Design system — **pulled forward from M10, 2026-08-01**
Look and feel is a first-class requirement, not polish: the app has to feel modern, functional and
nice, with the Claude mobile app as the standard of finish to aim at. Parity with Gmail is about
*capability*; the visual language is its own thing.

So `:core:designsystem` and the token layer land **before** further feature screens, not at M10.
Screens built against raw Material defaults are screens that have to be retrofitted, and retrofits
are where visual inconsistency becomes permanent. The thread row in `:core:ui` converts first — two
features draw it, which makes it the best test of whether the tokens are sufficient.

Direction, so it is not re-derived each time: warm neutrals rather than blue-grey, in both schemes;
one restrained accent used sparingly and never as a large filled area; generous whitespace with
hierarchy carried by weight and colour rather than size; hairline borders and flat surfaces instead
of elevation shadows; quick purposeful motion. Targets stay ≥48dp and body ≥16sp, contrast checked
in both schemes — a theme that fails contrast is not finished. Roborazzi coverage across light and
dark grows with it, because that is what stops the look regressing while M8 and M9 are built on top.

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
From identity. **Re-checked against the 8002 stack on 2026-07-31: still present.** `Identity/get`
returns `{"id":"1","name":"E2E Mailbox","email":"E2E Mailbox"}`. It only affects seeded data, so it
is not a client bug to work around — but M8's From picker will show a non-address until it is fixed,
and `Identity.email` must not be assumed to parse.

---

## Open decisions

1. ~~**Where the emulator runs.**~~ Settled: on Windows, GPU-accelerated. See Environment.
2. ~~**Rich-text compose** (M8).~~ Settled: `richeditor-compose` 1.0.0. The deciding argument was
   not in the table below — it is that the composer **never feeds it foreign HTML**. A quoted
   original is held beside the draft and appended at send, so the editor only ever has to serialise
   what this user typed, and its parser is not on the hook for anyone else's marketing mail.

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
adb install -r app/build/outputs/apk/foss/debug/app-foss-debug.apk
adb shell am start -n de.plmail.debug/de.plmail.MainActivity
adb exec-out screencap -p > s.png
```

**The APK path has a flavour in it, and getting it wrong is silent.** `:app` grew `foss` and
`google` product flavours in M6, so the output moved from `apk/debug/app-debug.apk` to
`apk/<flavour>/debug/app-<flavour>-debug.apk`. The old path was left behind in `build/` by a build
that predates the flavours, so `adb install` on it *succeeds* and installs a months-old app —
which looks exactly like a change that did not take effect. Install the `foss` build: it is the
default flavour and the one this product's audience gets.

A deep link is the fastest way to pair a freshly cleared app, and the URI has to be quoted for the
**device's** shell — an unquoted `&` truncates it at `?host=…` and the app opens onboarding with an
empty form rather than reporting a bad code:

```bash
adb shell "am start -a android.intent.action.VIEW -d 'plmail://pair?host=…&code=…'"
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
