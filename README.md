# plMail for Android

A native Android client for [plMail](https://github.com/karatektus/pl_mail), talking to it over
JMAP (RFC 8620 / RFC 8621).

plMail is a **self-hosted** mail client, so there is no canonical server: the app asks for an
address at onboarding and expects LAN hostnames, Tailscale nodes, private-CA certificates and a
NAS that is sometimes rebooting. Everything the app does is shaped by that — cache aggressively,
poll rarely, degrade per-account, and never busy-loop against someone's home server.

The server's [docs/CLIENT_DEVELOPMENT.md](https://github.com/karatektus/pl_mail/blob/main/docs/CLIENT_DEVELOPMENT.md)
is the specification this app implements. Read it before changing anything here. Where it and
`src/Jmap/` disagree, the code wins — the server moves faster than its documentation.

## Layout

| Path | What |
|---|---|
| `app/` | The application module: `Application`, the activity, navigation, DI graph. Deliberately thin. |
| `core/jmap/` | Transport and codec. A plain JVM module — no Android, on purpose. |
| `build-logic/` | Convention plugins. No module repeats an `android {}` block. |
| `gradle/libs.versions.toml` | Every dependency version, declared once. |

`core/jmap` has no Android dependency by design: it is the code most worth testing — every wire
shape the server can hand back — and keeping the frameworks out means its suite runs on the host in
seconds rather than booting an emulator. The module boundary is what enforces it; an accidental
`import android.*` fails to compile.

## Building

```bash
./gradlew build
```

```bash
./gradlew :core:jmap:test
```

The second is the tight loop: pure JVM, no emulator, seconds.

Formatting is [ktfmt](https://github.com/facebook/ktfmt) in `kotlinlang` style (4-space indent, per
the Android Kotlin style guide), driven by Spotless from the root build:

```bash
./gradlew spotlessApply
```

Warnings are compile errors. `-Pplmail.warningsAsErrors=false` turns that off for the one case that
justifies it — bisecting against a compiler that has started warning about something unrelated to
the change under test.

### Requirements

- JDK 21
- Android SDK with platform 37 and build-tools 36 (`ANDROID_HOME` set)
- minSdk 31 · targetSdk 36 · compileSdk 37

`targetSdk` is 36 rather than 37 deliberately: SDK 37 is released, but no emulator system image
exists for it yet, and declaring a target whose behaviour changes have never been run is a guess.

## Running against a server

Onboarding pairs by scanning a QR code from the server's **Settings → App passwords**, or by
tapping the `plmail://pair?host=…&code=…` link when the code is already on the device. The QR
carries a short-lived single-use pairing code, never the app password itself. Pasting an app
password by hand still works as a fallback.

For anything automated, use the server's isolated test stack so tests never touch real mail:

```bash
cd ../pl_mail && TEST_HTTP_PORT=8002 docker compose -p pl_mail_android -f compose.test.yaml up -d --build --wait app
```

That serves at `http://127.0.0.1:8002`, which is **`http://10.0.2.2:8002`** from inside an emulator.
Seed it with `app:test:seed-user`, `seed-mail`, `seed-label` and `seed-attachment`, and mint a
credential with `app:test:seed-api-token -q`.

The session object's URLs are generated from the request's `Host` header, so the app must read
`apiUrl`, `uploadUrl`, `downloadUrl` and `eventSourceUrl` from it rather than deriving them. That is
what makes one credential work from both the emulator and a phone on the LAN.
