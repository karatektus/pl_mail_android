# JMAP wire fixtures

Captured verbatim from a real plMail server (the isolated test stack on `:8002`), not hand-written.
That is the point: a hand-written fixture encodes what someone *believed* the server sends, and the
bugs worth catching are exactly where that belief is wrong.

**Do not tidy these files.** If a value looks wrong, it is either a real server behaviour worth
pinning down or a real bug worth reporting — editing it turns a test that would have caught
something into a test that asserts a fiction. Re-capture rather than edit.

## What each file is for

| File | Pins down |
|---|---|
| `session.json` | Discovery. One account per connected mailbox; the `urn:plmail:params:jmap:push` vendor capability carrying `vapidPublicKey`. |
| `mailbox-get.json` | Mailbox as a *label binding*, including plMail's `labelId` extension. |
| `email-query.json` | `canCalculateChanges: false`, `total`, and that the requested `limit` is echoed rather than the server's cap. |
| `email-query-get-backref.json` | **The ordering trap.** Query returns `["5","1","2","3","4"]`; the back-referenced `Email/get` in the *same* request returns `list` as `1,2,3,4,5`. A client that renders `list` in order shows its newest mail last. |
| `email-get-bodies.json` | Body parts are synthetic, `partId: "text"`. |
| `email-get-attachment.json` | `blobId` namespacing: `m-<id>` for a message, `p-<id>` for a part. Attachment `partId` is a numeric string — a different namespace from body partIds. |
| `thread-get.json` | **`Thread/get` reorders too**, and not into ascending id order: asked `[5,1,2,3,4]`, returned `[4,3,2,1,5]`. The client doc only warns about `Email/get`. Also carries plMail's `snoozedUntil` extension. |
| `email-changes.json` | From `sinceState: "0"` the server reports **nothing** about existing mail — empty created/updated/destroyed, `hasMoreChanges: false`. A fresh client is not populated by `/changes`; it must page `Email/query`. "state 0 means give me everything" is the natural wrong assumption. |
| `identity-get.json` | The From picker's source. See the caveat below. |
| `error-401.json` | `application/problem+json`. The real `WWW-Authenticate` is `Basic realm="plMail JMAP", charset="UTF-8"` — the doc omits the charset, so match loosely. |
| `error-account-id-integer.json` | `accountId: 1` as a JSON integer is rejected with `invalidArguments`, not coerced. HTTP 200 with a method-level error, and no `description` field. |
| `error-unsupported-filter.json` | An unknown keyword raises `unsupportedFilter` rather than being silently dropped. |
| `error-unsupported-anchor.json` | `anchor` paging is refused — **same `unsupportedFilter` type** as the filter case, so the two are indistinguishable by type alone. |
| `error-not-request.json` | Well-formed JSON that is not a JMAP request: HTTP **400**, `urn:ietf:params:jmap:error:notRequest`. Note the different status from the method-level errors above. |

## Settings (`urn:plmail:params:jmap:contacts`, `:appearance`, `:sync`)

Captured on 2026-08-06 against the same stack, after the seven-ask batch merged into plMail `main`.

The appearance fixtures were taken with a **deliberately non-default** appearance in place — Nord,
boxed, comfortable — because a fixture that happens to equal the defaults passes whether the client
read it or not. The server was set back to its defaults afterwards.

| File | Pins down |
|---|---|
| `session-settings.json` | All three new capabilities in one discovery. The appearance capability carries a compact `{theme, layout, accent, density}` **plus** the full vocabularies (**seven** themes — `paper` is the one the app does not have), the per-layout knob presets and the published `ranges`. `urn:plmail:params:jmap:sync` is **per account** in `accountCapabilities`, on both accounts: `syncLimit: 0` means *uncapped*, `backfillTarget: null` means no backfill has completed, and `backfillPending: true`. The contacts capability advertises `defaultSuggestions` and `maxSuggestions`, and `primaryAccounts` has its own key for the contacts URN. |
| `appearance-get.json` | The singleton: id `"singleton"`, **no `accountId`**, and seventeen properties including `paneBlur: 24` and background/ink fields Android does not render. Note `paneAlpha: 0.7` and `paneBlur: 24` are the boxed layout's *seeded* preset, not values anybody sent. |
| `appearance-set-clamped.json` | `paneAlpha: 1.4` sent, `{"paneAlpha": 1}` reported in `updated` — RFC 8620 §5.3, what the server changed *beyond* the request. `layout` and `density` were taken as asked and say nothing, which is why `updated` cannot be read as "what was applied". |
| `appearance-set-refused.json` | A bad theme beside a valid layout and paneAlpha: the **whole patch** is refused with a per-object `invalidProperties` naming the seven accepted themes, `newState` equals `oldState`, and none of the valid properties landed. |
| `appearance-set-state-mismatch.json` | A stale `ifInState` is a **request-level** `error` with type `stateMismatch` — not a `notUpdated` entry. A caller waiting for a per-object failure never sees one. |
| `contact-autocomplete.json` | Two calls in one batch: a blank query refused with `invalidArguments`, and a real one answered beside it — so a refusal does not take the batch down. Both normalised arguments are echoed: the trimmed `query` and the capped `limit` (asked 500, given 50). **`list` is empty and cannot be captured otherwise**: contacts are harvested through a Messenger message and the test stack runs no consumer, so its address book is permanently empty. The entry shape is asserted from `ContactAutocompleteMethod::toSuggestion` in the test instead. |

## Submissions (`EmailSubmission/get` and `/changes`)

Captured on 2026-08-06, in **one sequence against one submission**, which is why they can be read
together: submit a draft with `HOLDUNTIL` eight hours out, get it, read the change log, cancel it,
get it again, read the change log again.

They exist because **a server behaviour changed and the old client was built around the absence**.
plMail reconstructs a submission from the Message and used to skip any with no `sentAt`, so a
submission still being *held* answered `notFound` — the same answer as a draft nobody had ever
submitted — and anything that did resolve always reported `undoStatus: "final"`. The release time
lived in the create response and nowhere else, which is why the schedule had to be kept in
DataStore and why a message scheduled on one phone was invisible on every other device.

| File | Pins down |
|---|---|
| `submission-set-held.json` | The create response: `undoStatus: "pending"` and the real `sendAt`. The submission id **is** the Email id, and `createdIds` carries the same value. |
| `submission-get-pending.json` | Two ids in one get — one held, one a draft that was never submitted — landing on **opposite sides**: `list` with `undoStatus: "pending"` and the same `sendAt` the create response gave, and `notFound`. Under the old behaviour both would have been in `notFound`, and that difference is the whole of the client's feature detection. |
| `submission-get-canceled.json` | `undoStatus: "canceled"`, **keeping the `sendAt` it would have left at**. The row survives rather than disappearing, which is what lets a cancel made on another device be seen here — absence would be indistinguishable from an old server holding it. |
| `submission-changes.json` | The submit, reported under `created`. |
| `submission-changes-cancel.json` | The cancel, reported under `updated`, for the same id. |

**The `final` arm is deliberately absent and cannot be captured.** The `:8002` stack runs
`MESSENGER_TRANSPORT_DSN=in-memory://` with no consumer and `MAILER_DSN=null://null`, so no
submission on it ever completes. `EmailSubmissionReadTest` asserts that arm against a hand-built
record and says so at the point of use, rather than a hand-written file sitting in here looking
like everything around it.

Two more behaviours probed the same day and worth knowing before writing anything against this
surface, neither of which produces a fixture:

- **There is no way to enumerate submissions.** `EmailSubmission/get` with `ids: null`, and with the
  key missing entirely, are both `requestTooLarge`; `EmailSubmission/query` is `unknownMethod`. So
  `/changes` is the only route to an id nobody remembered.
- **Cancelling an already-cancelled submission is accepted again**, not refused — so a cancel is
  safe to retry and safe to race with another device doing the same thing.

## Calendars (`urn:plmail:params:jmap:calendars`)

Captured on 2026-08-05 against the same stack, after seeding four events onto the calendars the
mail seed had already created.

| File | Pins down |
|---|---|
| `session-calendars.json` | The vendor calendar capability, at session level *and* per account — and that a **two-account** login carries it on one account only. `primaryAccounts` has its own key for the calendars URN; it happens to equal the mail primary, which is what would let a client reusing `primaryMailAccount` go unnoticed. Note the VAPID key differs from `session.json`: this is a later seed, and that is why it is a second file rather than a replacement. |
| `calendar-get.json` | Calendar as the whole surface — there is no query, changes or set. `state: "fixed"`, a **hex** `color` (not the label token vocabulary), plMail's `role` (`default`, `account`) and `isSynced` extensions, and `myRights` with its own shape (`mayUpdateAll`, no keyword rights). No calendar may be deleted. |
| `event-query-get.json` | A query paired with a back-referenced get. Ids are **series** ids ordered by first occurrence in the window, not by id. Also the all-day event: `showWithoutTime: true` and **no `timeZone` key at all**. |
| `event-overrides.json` | `recurrenceOverrides` keyed by the occurrence's original start, with a `start` inside that moved it. `isRecurring` is true beside a rule the client did not derive it from. |
| `event-query-get-expanded.json` | Captured 2026-08-06. `expandRecurrences: true`: one id per **occurrence**, ordered by occurrence start, the one-off keeping its plain series id among the synthetic ones. The back-referenced get resolves both kinds — an occurrence answers `seriesId`, `recurrenceId`, its own `start`, and `recurrenceRules`/`recurrenceOverrides` as explicit **null**. Plus the two refusals that bound the feature: a window past `materialisedHorizon` is `cannotCalculateOccurrences`, and `CalendarEvent/set` on an occurrence id is `invalidArguments` naming `seriesId`. |
| `event-set-create.json` | The create response: `oldState`/`newState` both `"fixed"`, and `created` echoing only what the server decided (`id`, `uid`, `calendarId`, `isRecurring`, `sequence`). Top-level `createdIds` too. |
| `event-errors-update-destroy.json` | Five failures and two successes in one batch. A missing query window is a bare `invalidArguments` with **no description saying which end**; `sort` is `unsupportedSort`; a second account is `accountNotSupportedByMethod`; a refused property is a *per-object* `invalidProperties` carrying a `properties` array. Plus a successful update (value `null`) and destroy, and a get with a `notFound`. |

### Calendar behaviours that are not in a fixture

Established live and encoded as code rather than as JSON, because each is about what the client
*sends*:

- **`ifInState` is refused** with `invalidArguments`. The state is the constant `"fixed"`, so a
  guard on it can never fail. `CalendarEventSet` has no parameter for one.
- **Patch paths are refused**: `{"title/x": …}` answers `invalidPatch`, "Patch paths are not
  supported; send the whole "title" property." `Email/set` accepts the same shape, so this is the
  one place the two write surfaces genuinely disagree.
- **`privacy` is published and not settable** — `invalidProperties`, same as `participants` and
  `alerts`.
- **`CalendarEvent/get` preserves the requested order**, unlike `Email/get` and `Thread/get`.
  `ordered()` exists anyway; do not build on it.
- **A `properties` filter really does drop `@type`, `uid` and `calendarId`**, which is why every
  property but `id` is nullable.
- **`{"excluded": true}` in an override round-trips**, and is the only way to cancel one occurrence
  of a series — there is no id for a single occurrence.

### What this seed CANNOT cover for calendars

- **No read-only calendar.** Every calendar reports `mayAddItems: true`, so the `forbidden` a
  destroy on a read-only one raises is unexercised.
- **No `isSynced: true` calendar** and no `source` other than `"manual"`; `kind` is null everywhere.
- **Nothing outside the materialised horizon.** The partial-index answer a query beyond `-1 year` /
  `+2 years` gets is not observable here.
- **One rule shape only** — a weekly `byDay`. No `until`, `count`, `interval` or `byMonthDay`, and
  no unconvertible imported rule, which is the case where `isRecurring` and `recurrenceRules`
  disagree.

## What this seed CANNOT cover

Known gaps, so nobody mistakes their absence for evidence:

- **No keywords at all.** Every email returns `"keywords": {}`. Nothing exercises `$seen`, `$flagged`,
  `$draft` or `$answered` round-tripping.
- **No HTML bodies.** Every message is `text/plain`, so `htmlBody` is `[]` everywhere and the
  `"html"` partId never appears. Consequently the `fetchHTMLBodyValues` capitalisation trap — where
  the wrong spelling silently returns empty body values with no error — is **not observable here**.
  Both spellings look identical against this seed.
- **No empty `mailboxIds`.** Every email is in the Inbox, so `{}`-vs-`[]` for the empty case is
  unverified (the mapper returns `stdClass`, so it should be `{}`).
- **No multi-mailbox email.** The custom label has zero bound messages.
- **`labelId == id` for every mailbox.** The binding and label id spaces coincide exactly on a
  single-account seed, which is precisely the case the client doc says made the historical
  wrong-ids bug invisible. Asserting they match here proves nothing about the translation.
- **`Email.size` is `0` on every message** (null size column). Do not assert `size > 0`.
- **`bodyStructure` is silently absent** even when requested — no error, just missing.
- **Thin `role` coverage.** Only `"inbox"` and `null` appear; no Sent/Drafts/Trash/Junk/Archive
  bindings exist. The custom label also has `sortOrder: 0`, same as Inbox, so the documented sidebar
  order is not reproducible by sorting on `sortOrder` alone.

The way to close most of these is to drive the *public API* against the disposable stack —
`Email/set` to apply keywords and labels, create a draft, star something — and re-capture. That needs
no server change and exercises the same write path the app will use.

## A seed-data bug worth knowing

`identity-get.json` shows `"email": "E2E Mailbox"` — a display name where an address belongs. This is
**not** a JMAP bug: `IdentityGetMethod::fallbackIdentity` correctly returns `account.email`, and the
seed sets it wrong (`SeedTestEmailCommand.php:110` does `->setEmail('E2E Mailbox')`). It is captured
verbatim anyway. Do not write a From-picker test that treats this value as a valid address.

**The seed also has no alias rows at all, on either account**, so this fixture is the *synthetic*
identity — the one the server yields for the account address itself when there is nothing else to
offer — and there is no captured multi-alias shape anywhere in here. Re-probed on 2026-08-06 and
still true: account 1 answers one identity, account 2 answers one identity. Creating aliases is a
server-side write this repo may not make, so the multi-alias case lives in
`:core:data`'s `ComposeRepositoryTest` as a **constructed** fixture built to the contract in
`docs/CLIENT_DEVELOPMENT.md` — one identity per sendable alias, primary first — and is labelled
there as constructed rather than captured. If the seed ever grows alias rows, capture it and move
it here.

## Re-capturing

```bash
cd ../pl_mail && TEST_HTTP_PORT=8002 docker compose -p pl_mail_android -f compose.test.yaml up -d --wait app
```

Then seed (`app:test:seed-user`, `seed-mail`, `seed-label`, `seed-attachment`), mint a credential
with `app:test:seed-api-token -q`, and re-issue the requests above against `http://127.0.0.1:8002`.
