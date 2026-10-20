<!--
Thanks for the pull request. CONTRIBUTING.md, "How work gets in", explains
the flow; the short version is below.
-->

Closes #

<!--
A feature needs an issue labelled `accepted` before it is built. A bug fix may
come without one if this description says what the bug was.
-->

## What changes

## How it was checked

<!-- The device or emulator, the build (foss / google), and the server version it ran against. -->

## Checklist

- [ ] Linked to an issue above, or describes the bug it fixes
- [ ] Tests for the change (`./gradlew test`; `:core:jmap:test` for anything on the wire)
- [ ] Formatted and green (`./gradlew spotlessApply`, then `./gradlew build`)
- [ ] New or changed copy is in both `values/strings.xml` and `values-de/strings.xml`
- [ ] Docs updated if behaviour changed (README, `docs/`)
- [ ] Needs a server change? Linked to its pl_mail issue or pull request, and says which server version it needs
- [ ] A setting that changes how the app behaves for everybody is off by default, or says why not
