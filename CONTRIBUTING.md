# Contributing to plMail for Android

The app and the [server](https://github.com/karatektus/pl_mail) are one project, and they work the
same way: the server's [CONTRIBUTING.md](https://github.com/karatektus/pl_mail/blob/main/CONTRIBUTING.md)
is the longer version of this page. Building, formatting and running against a test server are in
the [README](README.md); releasing is in [docs/RELEASING.md](docs/RELEASING.md).

## How work gets in

**Issue first, then code.** Every change starts as an issue somebody agreed to, and every pull
request says which one it finishes. An issue costs a paragraph; a rejected pull request costs the
work in it.

1. **Open an issue** with the Bug or Feature form. Questions about running the app go to the
   server's [Discussions](https://github.com/karatektus/pl_mail/discussions). A new issue is
   labelled `needs-triage`.
2. **A maintainer triages it**: `accepted` means it may be worked on, `needs-info` means a question
   is waiting for you, `wontfix` means no, with the reason. Accepted work gets a milestone, or stays
   in the backlog, which is having none.
3. **Say you are taking it**, in a comment, so two people do not build the same thing.
4. **Open a pull request** against `main` that says `Closes #N`. The template lists what review
   checks.

**A feature needs an `accepted` issue before it is built.** That is where it is agreed whether the
app wants it, where it lives, and whether it is a setting. A setting that changes how the app
behaves for everybody starts off. **A bug fix may come straight as a pull request** if its
description says what the bug was; an issue is still welcome.

**The server comes first.** The app speaks JMAP to the server and implements its
[docs/CLIENT_DEVELOPMENT.md](https://github.com/karatektus/pl_mail/blob/main/docs/CLIENT_DEVELOPMENT.md).
A feature the server cannot do yet gets an issue there as well, and the two issues link each other
(`Counterpart to pl_mail#28`). A pull request that needs a newer server says which version, and the
app degrades on an older one rather than failing.

**Milestones follow the server's releases.** `v0.4.0` here is the app's half of the server's
`v0.4.0` milestone; an issue with no milestone is in the backlog. A pull request belongs to the
milestone of the issue it closes. Work for a milestone that should not reach users before the
server ships it is collected on a milestone branch (`milestone/v0.4.0`) — pull requests for it go
there instead of `main`, and the branch goes to `main` in one pull request labelled `deployment`
when the server's release is out. The app's own version numbers (`v0.0.31`) are not milestones and
keep counting as they do.

**Labels**: one type — `bug`, `enhancement`, `documentation`, `chore` — a status from triage, and
one or more `area: …` (mail, compose, calendar, search, settings, onboarding, push, jmap, build).
Pull requests get their area labels from the files they touch (`.github/labeler.yml`), and a check
refuses a pull request that closes no issue — unless it is labelled `bug`, `chore`,
`dependencies` or `deployment`. Contributors cannot label their own pull requests, so a bug fix
without an issue waits for a maintainer to label it `bug`.

**Into `main` through a pull request**, the maintainers' own changes included, with green checks,
squash-merged. Releases are cut from `main` and installed by people who never see the pull request,
so this is what keeps an unreviewed commit out of the APK.
