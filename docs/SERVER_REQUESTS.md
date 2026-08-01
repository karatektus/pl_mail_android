# Server-side asks

Things the Android client wants that `pl_mail` does not currently offer.

**Nothing here has been implemented, and nothing here may be implemented from this repo.** The
server is committed to concurrently by other sessions; this file is a queue for a human to triage,
not a to-do list anyone is working from. Read `~/pl_mail` freely — that is how these entries get
written accurately — but never write to it.

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

### `Email/set` update accepts `attachments` and silently drops them

**What the client wants to do.** Let someone attach a file to a message they have already started
writing. The composer autosaves as it goes, so by the time a file is picked the draft usually
exists on the server already.

**What it can do today.** Nothing, by that route. `EmailPatchApplier::DRAFT_PROPERTIES` lists
`attachments` among the properties a draft may change, so the patch is accepted, and
`JmapDraftWriter::update()` never looks at the key — the call answers `updated`, the attachment is
not stored, and nothing anywhere says so. The Android composer therefore *creates a new draft*
whenever the attachment set changes and moves the old one to Trash, which costs a round trip, an
upload of anything already uploaded, and one stray message in the bin per change.

**What was checked.** Against the running 8002 stack on 2026-08-01. Uploaded a blob
(`POST /jmap/upload/1` → `{"blobId":"u-1"}`), then
`Email/set {"update":{"10":{"attachments":[{"blobId":"u-1",…}],"subject":"…"}}}`. Response:
`"updated": {"10": null}`, no `notUpdated`. A following `Email/get` returns
`"attachments": [], "hasAttachment": false` — while the `subject` from the same patch *did* change.
The same `attachments` array on a `create` works correctly. Source: `src/Jmap/Mail/EmailPatchApplier.php`
(the property list) and `src/Jmap/Mail/JmapDraftWriter.php` (`update()` vs `applyAttachments()`,
which is only called from `create()`).

**Smallest change that would unblock it.** Either handle `attachments` in `JmapDraftWriter::update()`
the way `create()` does, or — if editing attachments on a saved draft is deliberately out of scope —
reject the property so the client learns, rather than reporting success. The silent success is the
part that is actively harmful; a `notUpdated` entry would be an improvement on its own.

**Client-side workaround.** In place, and it works: recreate the draft and bin the old one. It costs
a Trash entry the user did not ask for and re-uploads nothing (existing parts are re-attached by
blob id), so the risk of it being wrong is low — but it is a second implementation of "what a draft
is", and it disagrees with the web composer, which edits attachments in place.

### The chosen identity does not reach the sent message

**What the client wants to do.** Show an always-visible From picker and honour it. plMail accounts
can hold several sendable aliases and `Identity/get` publishes them, so "which of me is this from"
is a question the composer should be able to answer.

**What it can do today.** Choose the **account**, which works, because a draft is created under one.
Choosing a different *alias within* an account has no effect at all: the message goes out as the
account's own address, and nothing reports the difference. So the Android picker deliberately shows
one entry per account rather than one per alias — a control that silently ignores half its options
is worse than a smaller control.

**What was checked.** 2026-08-01, 8002 stack. `Email/set` create with
`"from":[{"name":"Alias Person","email":"alias@plmail.test"}]` stores and returns
`"from":[{"name":"E2E Mailbox","email":"E2E Mailbox"}]` — the account's own address.
`JmapDraftWriter::persistDraft()` sets `->setFromAddress($account->getEmail())` unconditionally and
never reads `from`. `EmailSubmissionSetMethod::submit()` reads `emailId` and ignores `identityId`
entirely. The web composer *does* honour the choice, through
`ComposeController::resolveFromAddress()`, so this is a JMAP-only gap rather than a product decision.

**Smallest change that would unblock it.** Have `EmailSubmission/set` resolve `identityId` against
`Account::getSendableAliases()` and set the From from it — the same resolution
`resolveFromAddress()` already performs. Honouring `from` on the draft would be a bonus; the
submission is the point where it matters.

**Client-side workaround.** None that is honest. Guessing the alias from the draft would mean
sending as an address the server did not agree to, which is the one thing a mail client must not do
quietly.

### Contact autocomplete has no JMAP surface

**What the client wants to do.** Suggest addresses as a recipient is typed, ranked by how often and
how recently the user has written to them. The server already harvests exactly this —
`HarvestContactsMessage`, a `Contact` entity with a `frequency` column.

**What it can do today.** The Android composer suggests from its **own cache**: senders and
recipients of mail already synced to the device, plus the OS address book when the user has granted
that permission. That is a good answer for anyone whose recent mail is on the phone and a poor one
for a new device, a rarely-used address, or someone the user has only ever mailed from the web.

**What was checked.** `grep -rn 'Contact' src/Jmap/` finds nothing — there is no JMAP method and no
JMAP Contacts capability in the session. The functionality exists only at
`src/Controller/Mail/ContactController.php`, `GET /contacts/autocomplete?q=`, which is one of the
HTML/Turbo routes clients are explicitly told never to build against.

**Smallest change that would unblock it.** Any JMAP-shaped surface over the same
`ContactRepository::findForAutocomplete()` — an `urn:plmail:params:jmap:contacts` capability with a
single query method would do. It does not need to be RFC 8621 Contacts.

**Client-side workaround.** In place and deliberately limited. The cost of being wrong is small —
a suggestion list that is shorter than it could be — which is why this was built rather than
blocked. It should be replaced rather than extended.

### Label colour has no JMAP surface, and `create` accepts one without storing it

**What the client wants to do.** Let someone colour a label, and draw that colour on the sidebar row
and on the label chips shown against a conversation. Colour is how a label list stops being a wall
of identical grey text once it is longer than about six entries, and plMail already models it —
`Label::$color` is a real column with a real setter.

**What it can do today.** Nothing. The Android label editor offers a name and a parent and no
colour, because there is nothing to send it to and nothing to read it back from. Every label in the
sidebar is drawn in the same ink.

**What was checked.** Against the running 8002 stack on 2026-08-01.

- `Mailbox/get` never returns a `color` key. The full property set is
  `id, labelId, name, parentId, role, sortOrder, totalEmails, unreadEmails, totalThreads,
  unreadThreads, myRights, isSubscribed` — `App\Jmap\Mapper\MailboxMapper::toJmap()` builds that
  list literally and `color` is not in it.
- `Mailbox/set` **update** with `{"color":"#ff8800"}` is correctly refused:
  `notUpdated: {"2": {"type":"invalidPatch","description":"Property \"color\" cannot be updated."}}`.
- `Mailbox/set` **create** with the same key **succeeds and drops it**: `created: {"c1": {"id":"5"}}`,
  no `notCreated`, and the following `Mailbox/get` shows no colour anywhere. That asymmetry is worth
  fixing on its own — update tells the client the truth and create does not.

**Smallest change that would unblock it.** Add `color` to `MailboxMapper::toJmap()` and to the
patch arm in `MailboxSetMethod`, over the column that already exists. If colour is deliberately
web-only, then reject it on `create` the way `update` already does, so a client cannot believe it
worked.

**Client-side workaround.** None worth having. The obvious one — colouring by a hash of the label
name, the way the avatars are coloured — would give the same label a different colour on the phone
than on the web, which is worse than no colour at all: the whole point of colouring a label is that
it is the same colour everywhere the user sees it.

### Scheduled send: `maxDelayedSend` is 0

**What the client wants to do.** "Send tomorrow at 8am", which is table stakes against Gmail.

**What it can do today.** Nothing. The undo window the Android app offers is a client-side delay
before calling `EmailSubmission/set` at all — it is not scheduling, it does not survive the process,
and it is measured in seconds.

**What was checked.** The live session advertises
`"urn:ietf:params:jmap:submission": {"maxDelayedSend": 0, "submissionExtensions": {}}`, and
`EmailSubmissionSetMethod` dispatches `SendMessageMessage` immediately with no `sendAt` handling.

**Smallest change that would unblock it.** Accept `sendAt` on the submission and hold the message
until then, advertising a non-zero `maxDelayedSend`. The messenger bus already has delayed dispatch.

**Client-side workaround.** A local alarm that submits later — rejected. It would send only if the
phone were awake, unblocked by Doze and still holding a valid credential, so a scheduled mail would
sometimes simply not go, with no way for the user to tell in advance.

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

### JMAP state moves only on real mutations

`app:test:seed-mail` writes messages directly without advancing the Email state, so it cannot
trigger a push however much mail it creates — `queryState` stays put. A real `Email/set` does move
it. This is correct behaviour, not a bug, but it makes the seeder useless for testing push and delta
sync, which is worth knowing before spending an evening on it.
