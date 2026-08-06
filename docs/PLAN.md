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
:core:notifications     channels, the grouped mail notification and its actions
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

> **Status, honestly, lives in [REMAINING.md](REMAINING.md).** This file is the architecture, the
> decisions and the history — why each thing is the way it is. That one is the current state of
> every milestone, the carried unverified list, the loose ends nobody has chased, and what adopting
> the unmerged label-colour branch will take. Read it first; come back here for the reasoning.
>
> Short version as of 2026-08-01: **M0–M10 and the design system are done. M11 is partial** —
> German at real length, the narrow-screen row and offline-as-a-state have landed; TalkBack, the
> contrast sweep, predictive back, shortcuts, the widget, R8 verification, the baseline profile and
> the Roborazzi matrix have not.


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
10. **`CalendarEvent/query`'s `after` and `before` are UTC**, despite being JSCalendar
    LocalDateTimes with no offset and no `Z`. `CalendarEventQueryRunner::run()` parses each and then
    `setTimezone('UTC')`, and matches against occurrence spans stored as UTC instants — so a window
    built from the device's wall clock is a window shifted by its offset, and an event in the first
    hours of a local day falls outside the day it is on. Found on a device, not in a test: the fake
    server compared the same naive strings the client sent. See `CalendarRepository.startOfDayUtc`
    and REMAINING.md's M12 section.

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

**Both landed 2026-08-01**, together with the blob plumbing they needed. `JmapClient.download`
streams into a caller's `OutputStream` through a new `DownloadingTransport` seam rather than going
through `send`, which returns a `ByteArray` — the session advertises a fifty-megabyte ceiling on a
message, and buffering that on a phone that is also holding a WebView works for every attachment
anybody tests with and fails on the one somebody needed. `BlobStore` in `:core:data` caches the
bytes under `cacheDir/blobs/<hash>/<name>`: hashed because a `blobId` is opaque and this app is
forbidden from parsing one, named because the filename is what the user sees when the file opens
somewhere else. Written to a `.part` and renamed, so an interrupted download cannot leave a half
file that the next tap treats as complete.

The list sits under the body and above the reply row; the whole row opens, the trailing button
saves through `ACTION_CREATE_DOCUMENT`. A `FileProvider` declared in `:core:data` mints the
`content://` URI, because a `file://` one across a process boundary is a `FileUriExposedException`
rather than a permission failure and there is no version of it that mostly works.

"View source" is **per message**, in the message's own overflow, for the same reason reply is: a
thread has several messages and "the source of a conversation" is not a thing. It does not wrap —
folding a `Received:` chain at the device width destroys the one thing anybody opens it for — so it
scrolls both ways instead.

Two things found while verifying this, both recorded where they belong. The `m-` blob is a
*reconstruction* for any message plMail has no raw bytes for, and for a JMAP-created message that
means a source with no headers at all (`docs/SERVER_REQUESTS.md`). And a blob download failed once
with `SocketException: Software caused connection abort` on the session GET after the app had been
idle — a pooled connection the server had closed. `retryOnConnectionFailure` was off globally, so
that surfaced as "could not reach your server" for a server that was running fine; it is now on for
GETs alone, since a replayed `POST /jmap/api` is a duplicated `Email/set` and a replayed GET is
nothing. The comment that had been justifying the old setting claimed OkHttp "only retries
connection establishment, never a request the server has already begun answering" — it does not;
`RetryAndFollowUpInterceptor` declines only for a *one-shot* body, and a byte array is replayable.

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
  **Landed 2026-08-01**, in `:core:notifications`. Push delivery had worked for a while and
  produced nothing anybody could see, which made it the largest hole in the product rather than the
  last piece of polish.

  What decides that mail is *new* lives in `DeltaSync` and is deliberately not "what the server
  called created": it is what this device has never held, which is the only definition that
  survives a discarded cursor. Only the delta sync announces — paging a list also writes messages
  the cache has never seen, and every one of them is older mail somebody scrolled to.

  The module depends on `:core:data` and nothing depends on it; the seam is a multibound
  `NewMailListener`, so a build without it syncs and simply says nothing. `MailDestinations` runs
  the other way, implemented in `:app`, because only `:app` can name `MainActivity` — an implicit
  `plmail://` intent would have needed `BROWSABLE`, which turns "open this conversation" into
  something a web page can link to.

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

**Four server behaviours were established by probing the running instance.** Two have since been
fixed on the server and adopted here on 2026-08-06; the other two still hold:

1. ~~`Email/set` **update** accepts `attachments`, answers `updated`, and drops them.~~ **Fixed.**
   Update stores the whole set: a part left out is removed, a part kept by its `p-` blobId costs no
   upload, and an unresolvable blobId refuses the whole patch and writes nothing. The
   recreate-the-draft-and-bin-the-old-one workaround is deleted.
2. ~~Neither the draft's `from` nor the submission's `identityId` reaches the sent message.~~
   **Fixed for `identityId`.** `EmailSubmission/set` resolves it through the same list
   `Identity/get` publishes and refuses an id it did not publish with `forbiddenFrom`, so the From
   picker offers one entry per *alias*. It sets the From **address** only — the display name still
   comes from the account, on the web path too.
3. `destroy` on a draft adds Trash and removes **Inbox**, which a draft never had, so it stays in
   Drafts as well. Discarding is an explicit `mailboxIds` patch instead.
4. A back-reference to a single created id resolves to a bare string, which `Email/get` rejects with
   an undescribed `invalidArguments`. Submitting a draft created in the same request uses the
   creation-id form (`"#c1"`), which is a different mechanism and does work.

The draft reaches Drafts **first** and only then is anything asked to send it, so a failure leaves
the mail in Drafts rather than losing it. **The undo window itself is the server's hold now**:
`HOLDFOR 6` on the submission, which is why killing the app inside those seconds no longer drops a
send the user watched the composer close over, and why the mail leaves on time rather than whenever
the app got round to it. Undo is a real `undoStatus: canceled` request that can be refused, and the
refusal is shown rather than swallowed. The old local delay survives as the fallback for a server
that advertises no hold.

The tablet presentation landed on 2026-08-01: `ComposeHost` decides from the window size class —
both axes, so a phone in landscape stays full screen — and presents the composer as a dialog over
the mailbox where there is room.

**Send later landed on 2026-08-06**, once the server advertised `maxDelayedSend: 2592000` and
`submissionExtensions: {"FUTURERELEASE": ["HOLDFOR", "HOLDUNTIL"]}`. Presets plus a bounded date and
time picker, the whole feature absent rather than disabled when the account's ceiling is zero, and
the ceiling read per account from the session rather than written down anywhere here. The limit
worth knowing is the server's, not the client's: a *held* submission has no server-side row —
`EmailSubmission/get` answers `notFound` for it, the same as for a draft nobody submitted — so the
release time exists only in the create response and the schedule is kept in DataStore beside the
outbox, for the same reason. A message scheduled on this phone is therefore invisible on another
device.

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

Labels reached the **thread rows** on 2026-08-01, which was the last place they existed everywhere
except: navigation, management and mutation all understood them and the row itself never said what
a conversation carried. `ThreadEntity` grew `labelKeys` — collapse *keys*, not names, comma-separated
and written when the row is summarised — and the schema went to version 2 by falling through to the
destructive upgrade rather than growing its first hand-written migration, which is exactly what the
"everything is a cache" rule was for. The names are resolved at draw time against the label list the
sidebar already holds, so a rename shows on every row at once instead of waiting for each
conversation to be re-synced.

Three things are removed before a chip is drawn, and each would otherwise appear on nearly every
row: the label being looked at, every system role (by `role`, never by name), and any key the
sidebar does not know. They share the snippet's line rather than taking one of their own — a line of
their own makes labelled conversations taller than unlabelled ones, and a list that scrolls at two
heights looks broken for a reason nobody can name.

**Corrected on 2026-08-01, on the device rather than in a baseline.** Three things were wrong and
each of them contradicted something this repo had already written down.

1. The chips sat *before* the snippet, so a labelled conversation read
   "E2E Label | Steuer | +1 | Hallo, anbei die b…" — the preview cut to three words by furniture, on
   the one line `ThreadRow`'s own doc calls "the line people actually read". They now trail it: the
   preview starts at the same left edge as the sender and the subject, and what truncates is the end
   of the sentence, where an ellipsis belongs.
2. Two chips **plus** a counter is three chips, and three do not fit beside a preview. The counter
   now takes one of the two slots (`ROW_LABEL_LIMIT` counts it), so a conversation with five labels
   draws one name and "+4" rather than two names and "+3". The cluster also has a width budget of
   its own, because two long German names are each inside the per-chip cap and together take two
   thirds of the line.
3. The chips were a dp taller than the line they sat on, so every labelled row was three pixels
   taller than its neighbours — under a comment saying the padding had been chosen so that would not
   happen. `ThreadRowLayoutTest` now measures the four cases against each other, which is a thing no
   screenshot baseline can do: each one is a single row, and the defect only exists between rows.

The list also gained bottom `contentPadding` clearing the compose button. `Scaffold` draws the FAB
over its content and reports nothing about it in the padding it hands back, so the last rows and the
"That's everything on this device" footer sat underneath it with no way to scroll them out.

Still open: **colour is blocked on the server** — absent from `Mailbox/get`, refused on `update`,
silently dropped on `create`. Filed in `docs/SERVER_REQUESTS.md`. Mail rules and block sender still
have no client surface.

The reader gained its own chrome on 2026-08-01 — back, archive, trash, and an overflow carrying
star, mark-unread, spam, "Label as" and snooze — which is the last of M9's leftovers. Two
decisions in it are worth keeping. Reply stays *under the message it answers* rather than moving
into the app bar, because a thread has several messages and an app-bar reply quotes whichever one
the code picked. And the announcement, its undo and the label sheet all moved up to `MailPane`:
archiving from the reader closes the reader, so a snackbar hosted by the reader left with it,
taking the way back off screen on exactly the actions where it matters most.

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

### M10 · Appearance and settings — **diagnostics landed 2026-08-01**

`:feature:settings` exists, and what is in it is the **diagnostics screen**, taken first within M10
because it is the one screen this audience needs at the moment they most need something: they run
the server, so when mail stops arriving they are also the person who has to find out why — and the
app could previously only tell them that nothing was wrong. Per-account last *successful* sync and
the error since it, the server address as stored, the distributor package, when the subscription was
registered, when a push last physically arrived, and a "check now" that syncs and asks the server
whether the subscription is verified.

Three things were wrong underneath it, and all three were invisible until something tried to draw
them.

1. **The push subscription id was thrown away.** `PushRepository.isLive` exists to detect an
   unverified subscription — registered, correct from every other angle, delivering nothing for
   ever — and it takes an id the app never kept. The diagnostic was written and unreachable. It is
   now in `PushStateStore`, in DataStore rather than Room precisely because the database's
   destructive-migration policy would drop it and a device that has lost its subscription id cannot
   check or revoke its own registration.
2. **Recording a sync failure erased the last successful sync.** One `UPDATE` wrote both columns, so
   a failure passed `at = null` and deleted the timestamp — turning "worked at 09:14, failing since"
   into "never worked", which sends the reader to look at their credential instead of at the last
   two hours. Split into two statements and pinned by an instrumented test, because nothing about
   the failure is loud.
3. **Nothing recorded that a push had arrived.** Every other line on that screen is the app
   describing its own intentions; "last push received" is the only one that is evidence.

`PushTransport` is an interface in `:core:data` implemented in `:app`, the same way round as
`MailDestinations`, because only `:app` knows UnifiedPush exists.

The screen deliberately makes **no requests when it is opened**. Adding traffic to a server somebody
is already worried about is the wrong first move, so everything is recorded state except the one
button that says it contacts the server.

**The Theme × Layout chooser landed on 2026-08-01**, and the shape it landed in is the server's
rather than its own. `Theme`, `Layout` and `Density` are plMail's own enums with plMail's own wire
values, resolved through one function — `PlMailAppearance.of(theme, layout, density, …)` — so
`Appearance` arriving over JMAP replaces the *source* of that call and touches nothing else. Three
consequences of taking that seriously, each of which changed the client:

- **Density was renamed before anything shipped on it.** It was compact/comfortable/spacious and is
  now comfortable/cosy/compact, because "spacious" is a step the server has no name for — a setting
  that would silently vanish the first time the two synced.
- **Nord, Dusk and Solar take their surfaces and ink from the web's own stylesheet**, so the same
  theme is the same theme on both. What could not be taken is the parts that fail WCAG AA on a
  phone: Nord's `#BF616A` red is 3.0:1 on Polar Night, and *every* Solarized accent fails on
  `base3` — yellow 3.0, orange 4.3, blue 3.5. Those palettes were designed for syntax highlighting
  and terminals, where an accent is a hint over text that is legible anyway. They are lightened or
  darkened only as far as AA requires, and `PaletteContrastTest` now sweeps every theme through the
  same resolver the app uses rather than a list somebody has to remember to extend.
- **The warmth rule is scoped rather than waived.** Nord is a published palette chosen by name;
  correcting its blues toward this app's warm neutrals would hand back a theme that is not the one
  asked for. The assertion applies to Light and Dark, which are what plMail looks like when nobody
  has chosen.

Material You sits beside the six as a switch rather than as a seventh entry: it is a different kind
of answer, and it still needs light or dark decided, which is what the theme list is for. The whole
token set is mapped from the dynamic scheme rather than only the accent — an app that takes the
primary and keeps its own greys gets the one part of Material You that reads as a mismatch. It needs
no version guard, which is worth saying rather than rediscovering: dynamic colour is API 31 and this
app's `minSdk` **is** 31.

Reduced motion was already honoured from the system. **Reduced transparency cannot be** — checked
against the API 37 stubs, `Settings.Secure` has no constant for it — so it is an app switch, and the
screen says so rather than leaving the asymmetry looking like an oversight. `paneBlur` is the one
knob deliberately absent: Compose blurs a composable's own content and has no backdrop filter, so a
"frosted" pane would blur the text on it rather than the list behind it, and a token that could only
be implemented wrongly is worse than a missing one.

The screen is its own preview — every control writes immediately and the app re-themes under the
finger — which is also how the next defect surfaced. **Back left the app.** Search and diagnostics
had the same bug and had had it since they were written: these screens are swapped in by boolean
state rather than pushed onto a back stack, so nothing consumed the gesture. The flags are not
mutually exclusive either, so the fix is one derived `Screen` value used by both the `when` that
draws and the `BackHandler` that dismisses, rather than one handler per flag that can disagree with
what is on screen.

**The accounts screen closed M10 on 2026-08-01**, and the first thing it needed was a second
account: the test stack had exactly one, so a reorder control could be written and could not be
watched work. `SessionBuilder` exposes one JMAP account per plMail `Account` row on the user, so
seeding a second row and three threads onto it — data, in the container's `/tmp`, never into the
server checkout — was the whole of it. `Mailbox` bindings appear lazily, so the second account
arrived with an Inbox and nothing else, which is exactly the state a real second mailbox starts in.

Three things live on that screen because they are three answers to the same question, and the order
is the one with consequences:

- **The order cannot be `AccountEntity.sortIndex`.** That column is set from the *session's* order,
  which is the server's answer and is reconstructible from it — as every column in that database has
  to be, or the "drop and re-sync on any migration failure" policy silently destroys something. A
  user's arrangement is not reconstructible, so it lives in DataStore, keyed by the account uid,
  which comes back identical after a cache wipe. Verified by killing the app: the order and the
  composer's From both survived.
- **The order has to mean something outside the screen**, or it is decoration. The first account is
  now what `LabelRepository.create` files a new label into and what the composer opens on — the
  latter watched on the device, which is what proves the setting is real.
- **The window is about the device, and says so.** "11 messages on this device, back to 31 Jul" is
  the boundary of what is searchable, because this app pages backwards as the user scrolls and
  nothing in the product had ever said that out loud. What the *server* holds needs a request, so it
  sits behind a button that says it makes one — one `Email/query` per account, ascending, limit 1.
  The server's own `sync.message_limit` and `sync.backfill_target` are not on JMAP at all; filed.

Notification preferences are per account and stored as **muted**, not as notifying. The direction is
the whole design: a mailbox added on the server has no entry, and storing the positive would leave it
silent forever — indistinguishable from push being broken, which is the one failure this product
cannot afford to fake. The check is made in `DeltaSync`, where the announcement is raised, so a
listener added later cannot forget it.

### M10 · Appearance and settings — **complete**

Nothing is outstanding in M10. The section below is the original scope statement, kept because it
is where the two-axis model and the token vocabulary are written down.
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

### M11 · Polish and ship-readiness — **partial**

German + English strings from M0 onward, checked (design for German ~30% longer); TalkBack and
contrast sweep across all six themes; ≥48 dp targets, ≥16 sp body; predictive back; app shortcuts and
a home-screen unread widget; offline as a first-class state (cached mail, queued mutations, plain
"can't reach your server" naming the hostname); R8 rules; baseline profile + Macrobenchmark;
Roborazzi screenshot suite across themes × densities × phone/tablet.

**Three of those landed on 2026-08-01. The rest have not started** — see
[REMAINING.md](REMAINING.md) for the split, which is the file to trust on status.

**German at real length, on a narrow screen**, which is the combination nothing here had been
looked at under: every baseline is 411dp and every string had been read in English. Shrinking the
emulator to 320dp and setting the app's locale to German found two defects immediately.

The chip cluster had a cap and no floor. 160dp is comfortable on a 411dp phone, where the text
column is 243dp; at 320dp the column is 188dp and the same 160dp left the preview about three
characters — which is exactly the defect that putting the chips *behind* the snippet had been meant
to fix, arriving again from a direction nobody had looked at. A cap cannot express it, because the
number being capped is not the number that matters. The cluster now takes the smaller of the cap and
whatever remains above a preview floor, measured with `Modifier.layout` rather than
`BoxWithConstraints` — fifty rows are scrolling, and a subcomposition per row to learn a width the
measure pass is already handing over is a cost on every frame. *How many* chips fit is a separate,
composition-time question, decided once per list from the **pane's** width rather than the window's:
a tablet's list pane is a fraction of an 840dp window and is easily narrower than a phone.

And the sidebar was in English on a German device, because those names come off the wire and the
server produces them from `LabelRole` with no catalogue near them. plMail's *web* client does not
draw them at all — it renders `sidebar.nav.inbox`, which `messages.de.yaml` turns into
"Posteingang". Resolving a system role to the app's own word is not a workaround for a missing
server feature; it is what makes the two surfaces agree. By `role`, never by name, so a label
somebody made and called "Trash" keeps its name.

**Offline as a first-class state**, which the local-first rule made necessary rather than optional.
Every action is applied to the cache first and never rolled back, so offline the conversation left
the inbox, a snackbar reported a rejection, and the change was then gone. There is now a durable
queue, and the rule it turns on is that **a transport failure queues and a server rejection does
not**: a refusal is an answer, and replaying it produces a loop that terminates never. It lives in
DataStore rather than Room, which is the clearest case in the app for that rule — a queued mutation
is the one piece of state the server does not have, so the destructive-migration policy would
silently discard something the user did.

It stores the label *key* rather than the `Label`, because bindings are cache and may have been
renumbered between the tap and the send. It drains oldest-first and stops at the first transport
failure, keeping everything from there including the one that failed: star-then-unstar and
unstar-then-star are different end states. The flush is a network-constrained WorkManager job rather
than a coroutine watching connectivity, because a queued archive has to survive the app being swiped
away — and it runs *before* the delta sync in the same worker, because the server's copy still
carries the Inbox label and syncing first would write the change back out.

Two banner defects fell out of turning the radios off. A **session** failure was being drawn with
the per-account wording, so it read "Could not reach http://10.0.2.2:8002 at 10.0.2.2. The other
accounts are still up to date" — the server named twice, then a claim about accounts nobody had
enumerated, because the call that lists them is the one that failed. And with no network at all the
per-account banners are copies of a fact the offline banner has already stated, in the one state
where "the other accounts are still up to date" is false. One known defect remains and is written
up in REMAINING.md: the failure banner is **sticky**, because `_failures` is only rewritten by a
page load, so it survives the network coming back until something re-pages.

### M12 · The calendar — **done, verified on device 2026-08-06**

plMail serves a vendor JMAP calendar surface (`urn:plmail:params:jmap:calendars`: `Calendar/get`,
`CalendarEvent/get`, `CalendarEvent/query`, `CalendarEvent/set`) and until this milestone the app
had no calendar at all — it was the last whole feature area the server had and the client did not.
Landed as four commits, each a layer:

- `b520532` — `:core:jmap`: the wire surface, pure JVM, pinned by fixtures captured from the live
  stack. The decisions that matter: the capability URN goes in `using` (unlike push); calendars are
  served from exactly one account, read from `primaryAccounts` under the calendars URN, not the
  mail one; `CalendarEvent/get` chunks by the account's `maxEventsInGet` (100), not core's 500; the
  state token is the literal `"fixed"` and is treated as opaque.
- `a3b17d9` — `:core:database`/`:core:data` (schema 3 → 4, destructive fall-through): cache and
  `CalendarRepository`. There is no `/changes` for calendars — the state cannot move — so staying
  current is re-running the windowed query on open, on pull-to-refresh and nothing else. Recurring
  placement never expands rules client-side (forbidden by CLIENT_DEVELOPMENT.md): day membership
  comes from batched one-day probe queries, ≤31 per request under `maxCallsInRequest`, and
  time-of-day from the base start plus the published `recurrenceOverrides`. The occurrence-
  expansion ask that would collapse that to one call is filed in SERVER_REQUESTS.md with the probe
  evidence.
- `616f30c` — `:feature:calendar`: agenda, detail, editor, drawer entry gated on the server
  publishing a calendar account. The deliberate cuts (agenda only, series-level edits, one calendar
  per event, no reminders field — `alerts` is not writable) are argued in REMAINING.md.
- `155da63` — the three defects on-device verification found, none visible to the suite: query
  windows are **UTC on the wire** despite being zoneless LocalDateTimes (the fake server had
  mirrored the client's wrong assumption, so the tests agreed with the bug); availability never
  re-probed after pairing; the New editor kept the previous draft. Wire-behaviour item 10 below and
  REMAINING.md's M12 section carry the detail.

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
| ~~Scheduled send (`maxDelayedSend` is 0)~~ — landed and adopted 2026-08-06 | M8 | Gmail parity |
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
