# Server-side asks

Things the Android client wants that `pl_mail` does not currently offer.

**The standing rule is that nothing here may be implemented from this repo.** The server is
committed to concurrently by other sessions; this file is a queue for a human to triage, not a to-do
list anyone is working from. Read `~/pl_mail` freely — that is how these entries get written
accurately — but never write to it.

**Two exceptions have now been made, both explicitly authorised and both worked in a git worktree of
their own** rather than in `~/pl_mail`: `Mailbox.color` and the inbox categories. **Both are now
merged into `main`** and both are described under "Landed" below. The rule is unchanged for
everything else, and the worktree is what makes an exception safe — the primary checkout's branch
never moves and no parallel session's `git add` can sweep the work up.

## The rule this file exists to serve

> Never work around a missing server feature without asking first.

A workaround is not free: it is a second implementation of a protocol decision, in a client, that
has to keep agreeing with the server's version forever. Two clients guessing differently about what
`in:archive` means is a bug that presents as "my phone shows different mail than my laptop", which is
close to unfixable once someone has built habits on top of it.

So when a feature turns out to need the server: build everything around it, stop at the boundary,
and write the entry. Say what was verified rather than assumed — a probe against the running stack
beats reading the PHP, because the README has been wrong before (`SearchSnippet/get` is documented
as unimplemented and works fine).

## How to write an entry

Each one should let someone decide in a minute, without opening the client:

- **What the client wants to do**, in product terms, not protocol terms.
- **What it can do today**, and what that costs the user.
- **What was checked** — the method probed, the response, the source file read.
- **The smallest server change that would unblock it.** Not a design; a direction.
- **Whether a client-side workaround exists**, and what it would cost to be wrong.

---

## Open

Nothing, for the first time. All seven asks below were built on 2026-08-06 — each in its own
worktree branch, all merged into plMail `main` in one push (`cbd27e0`) — and every wire behaviour
described under "Landed" was verified over HTTP against a live stack built from that merge, not
read out of the PHP. The entries moved to "Landed" below, with what a client author needs.

---

## Landed

Kept rather than deleted, because what was asked for and what arrived are not always the same shape,
and the difference is what a client author needs.

### The 2026-08-06 batch — all seven remaining asks, **merged into plMail `main`** (`cbd27e0`)

Built by five parallel sessions, each in its own worktree, integrated and probed live before the
merge. `docs/CLIENT_DEVELOPMENT.md` (EN and DE) was updated with all of it. What follows is the
shape that actually landed, per ask, where it differs from or sharpens what was requested.

**`Email/set` update stores `attachments`.** Whole-value semantics, exactly like the web composer's
strip: the array is the complete set, a part left out is removed. A part already on the draft is
kept by the `p-` blobId `Email/get` handed out — no re-upload, same part id — and a `p-` blob from
a *different* message is copied in, so forwarding an attachment costs no round trip. `name`/`type`
on a kept part are applied when present. An unresolvable blobId — malformed, expired, another
account's — refuses the **whole patch** with `invalidProperties` and writes nothing, including a
subject in the same patch; the refused-update-is-a-no-op behaviour is deliberately stricter than
the mailbox-patch precedent. One behaviour change beyond the ask: `create` now also refuses a
non-array `attachments` instead of ignoring it. **Client action: retire the recreate-and-trash
workaround.** — *done 2026-08-06.* `ComposeRepository.save` patches; the attachment array rides on
the patch only when the set has changed, because it is whole-value and re-stating it per keystroke
is pure cost. A refused patch needs no rollback, so the composer reports it and leaves the draft.

**`identityId` reaches the sent message.** `EmailSubmission/set` resolves it through the same list
`Identity/get` publishes (one identity per sendable alias; the synthetic account-id identity only
while an account has no alias rows), so an id the server offered is exactly an id it accepts. An id
that resolves to nothing is `forbiddenFrom` — never silently sent as the account's address.
`EmailSubmission/get` now reports the identity actually used. Limit worth knowing: it sets the From
*address*; the display name still comes from the account, on the web path too. **Client action:
the From picker can finally show one entry per alias.** — *done 2026-08-06.* One entry per alias,
the account's name shown beside it only when more than one account is connected, and a
`forbiddenFrom` turned into a sentence naming the address with `Identity/get` re-read in the same
breath. `loadDraft` matches the draft's existing From rather than taking the first identity.

**Scheduled send.** `maxDelayedSend` is now `2592000` (30 days), and the session advertises
`submissionExtensions: {"FUTURERELEASE": ["HOLDFOR", "HOLDUNTIL"]}`. The request shape is RFC 8621
§7 / RFC 4865: `envelope.mailFrom.parameters.HOLDFOR` (seconds) or `.HOLDUNTIL` (UTC date), names
case-insensitive, both together refused, beyond the ceiling refused naming `maxDelayedSend`. The
response's `sendAt` is the real release time. Cancellation exists: `update {id: {"undoStatus":
"canceled"}}` before release declines the send (the flag the web undo button sets); after `sentAt`
it is `cannotUnsend`, and a cancelled submission answers `notFound` from `/get` afterwards — there
is no submission row to hold the state. The envelope is validated now, not dropped: a `mailFrom`
that is not the submission's From is `forbiddenFrom`, an `rcptTo` differing from the Email's
recipients is `invalidRecipients`. **Client action: the "send tomorrow at 8am" feature is
buildable; the rejected local-alarm design stays rejected.** — *done 2026-08-06*, and one thing
found while building it that the entry above does not say plainly enough to design from: **a
submission that is still held is not gettable at all.** `EmailSubmissionGetMethod` skips any Message
with a null `sentAt`, so `/get` answers `notFound` for a pending hold exactly as it does for a
cancelled one and for a draft nobody ever submitted, and it always reports `undoStatus: "final"`.
The release time therefore exists in the create response and nowhere else. The client keeps it in
DataStore beside the offline queue — not Room, which is dropped on any schema bump precisely because
everything in it is reconstructible from the server, and this is not. The consequence is the
feature's honest limit: a message scheduled on one device is invisible on another, and only the
ability to call it back is lost.

Also worth writing down for whoever builds this next, all four verified on 8002 on 2026-08-06: the
**minimal** scheduling envelope is `{"mailFrom": {"parameters": {"HOLDUNTIL": "…"}}}` — `email` and
`rcptTo` are both optional, and sending them only risks `forbiddenFrom`/`invalidRecipients` for
information the server already has. `HOLDFOR` accepts a bare integer as well as the RFC's string.
Parameter names are case-insensitive (`holduntil` works). And the submission id that comes back is
the Email id, which is what a later cancel names.

**`Contact/autocomplete`, under `urn:plmail:params:jmap:contacts`.** Takes `accountId`, a
non-empty `query`, optional `limit` (default 8, capped at 50, both advertised in the session's
capability object). Answers `{accountId, query, limit, list}` — each entry a JMAP `EmailAddress`
(`name`, `email`) plus `frequency`, `lastSeenAt`, `isCorrespondent`; no id on purpose (the address
is the stable key). Ranked `frequency DESC, last_seen_at DESC` — the recency tie-break was a
product decision made during the build and applies to the web composer too, so both surfaces rank
identically. Served from **every** account, unlike calendars. A blank query is `invalidArguments`,
as are `filter`/`sort`/`position`. **Client action: the local-cache suggester should be replaced,
as its own docblock always said; the OS address book stays as a supplement.**

**`Appearance/get` / `Appearance/set`, under `urn:plmail:params:jmap:appearance`.** The JMAP
singleton pattern: one object, id `"singleton"`, no `accountId` (per-user, like PushSubscription —
sending one is refused). All embeddable fields are readable; enums outside the vocabulary are
refused with `invalidProperties` naming the accepted values, numeric knobs are clamped **and the
clamped value reported** in `updated`, so nothing is applied behind the client's back. `ifInState`
is honoured. The session also carries a compact read (`theme`, `layout`, `accent`, `density`) plus
the full vocabularies, ranges and per-layout defaults — enough to paint the first frame and bound
the sliders without a method call. Two things to design around: there is **no `Appearance/changes`
and no push** — a theme changed in the browser is seen on the next `Appearance/get` — and a patch
of `layout` alone also seeds that layout's knob preset (explicit knobs in the same patch win;
everything seeded is reported). The `paper` theme question is still the client's to answer.
**Client action: wire `PlMailAppearance.of(...)` to `Appearance/get`, flip DataStore from source to
override.**

**The sync window, under `urn:plmail:params:jmap:sync`.** Per-account in `accountCapabilities`:
`syncLimit` (the cap **in force** — reported as 0 on Microsoft accounts whatever is stored, because
Graph cannot honour it), `backfillTarget` (how far a completed backfill reached; null when none has
finished), and `backfillPending` (derived from the same accessors the sync engine uses — it means
"there is mail still coming", not "a worker is running this second"). **Client action: the
accounts-screen `Email/query` oldest-message probe can go; "your server holds mail back to" becomes
a session read.**

**`CalendarEvent/query` takes `expandRecurrences: true`.** One entry per *occurrence* in the
window; `position`/`limit`/`total` count occurrences; ordering is by occurrence start, moved
overrides sorting at their moved time and exclusions absent. The id shape is
**`<eventId>_<recurrenceId>`** — `42_20260304T090000Z`, the occurrence's *original* start as a UTC
instant in ISO basic format — treat it as opaque. The separator is `_` rather than the `;` other
servers use because RFC 8620 §1.2 confines a JMAP Id to `A-Za-z0-9`, `-`, `_`; this was decided
during the build, and the client builds against it fresh. One-off events keep their plain series id
even when expanding. `CalendarEvent/get` resolves instance ids — the series with its override
merged in, plus `seriesId` (plMail extension), `recurrenceId`, its own `start`/`duration`, and
`recurrenceRules`/`recurrenceOverrides` nulled per the draft. `CalendarEvent/set` refuses an
instance id by name, pointing at `seriesId` + `recurrenceOverrides`. A window past the advertised
`materialisedHorizon` is `cannotCalculateOccurrences`; `timeZone` alongside expansion is refused.
**Client action: the 31-one-day-queries-per-month machinery reduces to one query per window.**
**Adopted 2026-08-06** — the probe machinery is deleted. A month is one round trip whatever recurs
in it. Two notes for whoever reads this next: the refresh sends the expanded query *and* a collapsed
one in the same request, because an occurrence's object is the series with its override merged in
and the editor must open on the series' own start; and the window is clamped to a year either side
of today before it is sent, because the horizon refusal takes down the whole month rather than
trimming it.

### `Mailbox.color` — **merged into plMail `main`** (`b06b909`), adopted 2026-08-01

Asked for as "add `color` to `MailboxMapper::toJmap()` and to the patch arm". What landed is that
plus a closed vocabulary: nine Tailwind tokens (`gray`, `red`, `orange`, `amber`, `green`, `teal`,
`blue`, `violet`, `pink`) or null, moved out of `LabelType` into a `LabelColor` enum the web form
reads too, refused with `invalidProperties` naming the accepted values rather than dropped, and
accepted on `create` as well as `update`. Colour is the one property a **system** label accepts an
update to — Inbox may be recoloured and may not be renamed.

Tokens rather than hex is the part that matters to this client, and it is why the adoption was one
day's work rather than a week's: `blue` resolves through `PlMailColors.labelColor` per theme, so the
same label is the right blue in all six of them. A hex value would have been one fixed light-mode
colour drawn on Nord's Polar Night.

Main has since gained commits mapping Gmail and Outlook colours onto the same vocabulary by hue, so
a real account arrives already coloured.

### The inbox categories — **merged into plMail `main`** (`84c3f1b`), 2026-08-02

Not previously in this file, because the ask and the implementation happened in one session. plMail
has classified inbox mail into Gmail's five categories for a long time and the web has had a tab bar
over it; `grep -rn category src/Jmap/` returned nothing, so no client but the browser could see it.

The branch adds three things and the split between them is the point:

- `Thread.category` — the **resolved** conversation value, most-recent-wins, and the only one a tab
  may be drawn from. Null means never classified, which is a real state and is not Primary.
- `Email.category` — the raw per-message signal, read-only, published so a client can explain why a
  conversation is where it is.
- `Email/query`'s **`threadCategory`** filter condition, which matches on the *thread's* value.

Both halves of that were forced by the same two facts. A tab holds conversations, so filtering the
per-message column would put a newsletter somebody answered into two tabs where the web shows it in
one. And `Email/query` windows by position and limit, so a client that fetched a page and sieved it
locally would draw a nearly-empty Promotions tab under a list that had already reported its end —
which is indistinguishable, from the device, from a genuinely quiet category.

A thread with a null category matches no tab, exactly as `MessageThreadRepository::findForUnifiedInbox`
has it. `app:backfill category` is what fills those in.

**Merged**, and verified on the wire afterwards rather than taken on trust — the whole point of a
merge is that the client now meets the same code every other client will. Against the ordinary
stack, on 2026-08-02: `Thread/get` publishes `category` and `Email/get` publishes its per-message
counterpart; `Email/query` with a `threadCategory` condition returns exactly that tab's
conversations and no others; and a token outside the vocabulary is refused with `invalidArguments`
rather than quietly matching nothing, which is the behaviour a client can actually detect. The
`feat/jmap-categories` branch and the `~/pl_mail_categories` worktree are both gone.

---

## Verified present, despite the docs

Kept because these cost time to establish and the documentation still disagrees.

### `SearchSnippet/get` — implemented and working

`src/Jmap/README.md` lists it under "Not implemented" in two places. It exists at
`src/Jmap/Method/Mail/SearchSnippetGetMethod.php` and answers correctly: `ts_headline` over the same
`search_vector` and `websearch_to_tsquery` that ran the query, `<mark>` around hits, everything else
escaped.

Two behaviours worth knowing rather than debugging:

- A **stopword** term (`the`, `is`) returns a snippet whose `subject` and `preview` are both `null`.
  The query still matched; `websearch_to_tsquery` simply compiles the term to nothing. That is a
  result with no highlight, not a miss.
- Matching is **stemmed** and **English-configured**, so `running` highlights `run`, and German text
  is stemmed as if it were English.

### The `m-` blob is a reconstruction, and for some messages it is almost empty

The reader's "View source" downloads the message's own blob — `m-<id>`, `message/rfc822` — and what
comes back is not always the bytes that arrived. `BlobResolver::message()` returns the stored raw
file when `RawMessageResolver` can find one, and otherwise falls back to
`MessageSourceBuilder::build()`, which reassembles a source from the parsed header map and the
decoded body. Its own docblock is explicit that this is byte-faithful to neither the transfer
encoding nor the MIME structure and will not verify a DKIM signature.

Two consequences the client has to live with, both verified against the 8002 stack on 2026-08-01:

- A message with **no stored headers reconstructs to `"\n\n" + body`** — two blank lines and the
  text. Everything created over JMAP is in this state, and so is everything `app:test:seed-mail`
  writes, because neither populates `message.headers`. Downloading `m-9` returned exactly
  `\n\nSeeded body for "E2E Read Me".`; the same message with a realistic header map written into
  the column returned a full, correct source. So a bare source view is a message plMail never had
  the headers for, not a failed download.
- The reconstruction is **not multipart**, so the attachment parts are not in it whatever the
  `Content-Type` header says. Anyone reading the source to find out how a message was assembled is
  reading the client's summary of it.

Not filed as an ask: storing raw source is already what the server does when it has it, and a
message composed through JMAP has no original bytes to store. Written down because "View source
shows nothing" looks exactly like a broken download, and the client cannot tell the difference —
which is why the empty case says "the server has no source stored for this message" rather than
reporting an error.

### `Email/set` `destroy` leaves a draft in Drafts

`destroy` adds the Trash label and removes **Inbox**. A draft never had Inbox, so a destroyed draft
comes back with `"mailboxIds": {"3": true, "4": true}` — Drafts *and* Trash — and keeps appearing in
the Drafts list. Verified on 2026-08-01: destroying draft 10 returned `"destroyed": ["10"]` and the
following `Email/get` showed both bindings.

Correct for received mail, where Inbox is the label to leave; wrong-looking for a draft. The client
discards drafts with an explicit `mailboxIds` patch instead — add Trash, remove Drafts, both in one
patch, because removing the last mailbox is refused with "An Email must belong to at least one
Mailbox".

Not filed as an ask, because "destroy means move to Trash" is a deliberate product rule and the
client has a correct expression of it. Written down because the obvious call does the wrong thing.

### `EmailSubmission/set` cannot be verified end to end on the test stack

The 8002 stack runs with `MESSENGER_TRANSPORT_DSN=in-memory://` and no consumer, so
`SendMessageMessage` is dispatched and never handled. A submission is accepted and answers
`"undoStatus": "pending"` with a `sendAt`, and the draft then stays in Drafts with `sentAt: null`
forever. `MAILER_DSN=null://null` as well, so nothing would leave the box regardless.

That means the client's *request* is verifiable against this stack and the draft→sent transition is
not. Worth knowing before spending an evening deciding whether the send path is broken.

### System labels are created lazily, so an account can have no Spam and no Archive

The seeded 8002 account reports exactly four mailboxes — Inbox, Drafts, Trash and one custom label.
There is no Sent, no Spam and no Archive until something needs one: snoozing a conversation caused a
`snoozed`-role Snoozed mailbox to appear out of nowhere, on the same request.

This is `LabelResolver::systemLabel` doing its job and it is not a bug. It does mean a client cannot
assume a role binding exists, and cannot create one either — `Mailbox/set` `create` makes a *custom*
label, with no role, whatever it is named. So "mark as spam" on a fresh account has nothing to move
the message to, and the honest answer is the error the client already raises rather than a silently
successful no-op.

Written down because the obvious reading of `Mailbox/get` is that these mailboxes are missing.

### Destroying a label with children is refused, not cascaded

`Mailbox/set` `destroy` on a parent answers
`notDestroyed: {"5": {"type":"mailboxHasChild","description":"Destroy the child mailboxes first."}}`.
Destroying a leaf that still has mail in it is fine, and the mail keeps its other bindings —
verified: a message in Inbox and a custom label came back with `mailboxIds: {"1": true}` after the
label was destroyed. So the client deletes depth-first and never has to warn about losing mail.

### `Thread/set` snooze moves the mail, it does not flag it

Verified end to end on 2026-08-01. `{"snoozedUntil":"2026-08-05T08:00:00Z"}` answers
`updated: {"6": null}`, `Thread/get` returns the timestamp, and the conversation's messages come
back **out of Inbox and into a `snoozed`-role mailbox** — `mailboxIds` went from `{"1":true}` to
`{"7":true}`. Setting it back to `null` returns them to Inbox.

That is the behaviour the plan assumed, and it means a snooze must never be modelled client-side as
a flag on the row: the mail genuinely leaves the inbox, and a client that only hid the row would
disagree with the web UI the moment either changed.

### There is no way to make inbound mail through JMAP, and three reasons why

Worth writing down because notifications, delta sync and push all need one and the obvious routes
all fail differently. Established on 2026-08-01 against the 8002 stack while trying to make a
message *arrive*.

- **`Email/set` `create` always files into Drafts**, whatever `mailboxIds` says. Creating with
  `{"mailboxIds":{"1":true},"keywords":{}}` — Inbox, no keywords — answers `created` and produces a
  message in mailbox 3 with `$draft` and `$seen` both set. `JmapDraftWriter` is the only creation
  path and a draft is what it writes.
- **`$draft` cannot be removed.** Not by `keywords/$draft: null`, which reports `updated` and
  changes nothing, and not by replacing the whole map with `"keywords": {}`, which also reports
  `updated` and leaves `$draft` in place. `$seen` and `mailboxIds` in the *same* patch are applied
  correctly, so this is one silently-ignored key rather than a rejected request.
- **A `#creationId` key in an `Email/set` `update` map is refused** with
  `notUpdated: {"#n1": {"type":"notFound","description":"No such Email in this account."}}`, in the
  same request that created it. That is worth knowing next to the note above about submission,
  where the creation-id form *does* work — the two mechanisms look identical and only one exists.

The workable recipe, for whoever needs it next: create the draft, read its real id out of
`createdIds`, then patch `mailboxIds/1: true`, `mailboxIds/3: null`, `keywords/$seen: null` in a
second request. The result is an unread message in the Inbox that still carries `$draft`, which is
close enough to test everything downstream and is a state the product itself can never produce.

None of this is filed as an ask. It is a test-harness problem, not a client one.

### JMAP state records on every ingest path, seeders included — **this entry used to say the opposite**

This section previously read: *"`app:test:seed-mail` writes messages directly without advancing the
Email state, so it cannot trigger a push however much mail it creates."* **That is now false, and
it is worth saying plainly because it was wrong in a way that misled debugging across several
sessions** — it made "the seeded message never appeared on the phone" look like a known and
expected property of the harness, so nobody looked at the client, where the actual bug was. If a
statement in this file ever explains away a symptom that neatly, re-probe it before building on it.

State recording landed on all three ingest paths this session, all merged into `main`:
`ComposeController::persistDraft` for mail written on the web, `MessageSendService` for mail sent,
and the test seeders. Everything now records through `PostIngestPipeline`, so the change log
carries an inserted message whichever route it arrived by.

Verified rather than read: **Email state moved 593 → 837 across one `app:test:seed-mail` run**, and
`Email/changes` from 593 reported the whole difference. The seeder is therefore a usable way to
test push and delta sync, which is exactly what the old note said it could never be.

One caveat that is a real limitation rather than a correction. `SendDraftCommand` writes its
change-log rows but has no push drain behind it: the console fires neither `kernel.terminate` nor
the worker events the drain hangs off, so the immediate push is skipped and the next `/changes` is
what reports the send. A send made from the console therefore looks like a dropped push and is not
one.
