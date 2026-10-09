# TODO

## Check the reader for the runaway message height loop

The web reader had a bug where opening certain mails made the message body grow
without end, pushing reply/forward far down below a long stretch of empty space.
Fixed on the web in `pl_mail/templates/mail/_frame_script.js` (2026-10-02). The
Android reader has not been checked.

**The loop:** the WebView is sized to the height its content reports. A mail
that sizes something by the viewport (`min-height: 100vh` on a wrapper with a
footer under it, or an absolutely positioned `height: 100%`) measures
viewport + something, the view grows to fit, the content grows with it, and so
on.

**Where to look:** `feature/mail/src/main/kotlin/de/plmail/feature/mail/reader/MessageWebView.kt`
feeds the WebView's `contentHeight` into the Compose height, and
`MessageDocument.kt` builds the document and its fitting CSS. Android measures
differently from the web (native `contentHeight`, not a script reading
`scrollHeight`), so it may well be unaffected — but the view height still
follows the content, so viewport-sized content can still chase it.

**To do:**

- [ ] Reproduce: render a body containing
      `<div style="min-height:100vh"><p>hi</p></div><p>footer</p>` and watch
      whether the item keeps growing.
- [ ] If it does, neutralise viewport-unit declarations (`vh`, `dvh`/`svh`/`lvh`,
      `vb`, `vmin`, `vmax`) in `MessageDocument`, and ignore a content-height
      change that follows a height-only resize of the view.
- [ ] Check whether inline styles reach the WebView at all here (the web
      sanitizer keeps them; that is what lets `100vh` through).

## Implement email templates

The server has templates since 2026-10-09 (pl_mail issue #26): messages the
user writes once and inserts while composing. The web UI is done; the app shows
none of it yet. Everything it needs is served over JMAP.

**Read first:** `pl_mail/docs/CLIENT_DEVELOPMENT.md` → "Templates" is the wire
reference (objects, variables, `Template/render`, what to do with the answer).
`pl_mail/docs/features/templates.md` is what the user sees on the web, which is
the behaviour to match.

**The API, in one paragraph:** capability `urn:plmail:params:jmap:templates`.
`Template/get|set` and `TemplateFolder/get|set` are per user and take **no**
`accountId` argument; `accountId` is a property saying which mail account an
object is filed under (`null` = top level). There is no `/query`, no `/changes`
and no push: `state` is a hash, re-read the list when the picker opens.
`Template/render` (`id`, `accountId` of the From account, optional `identityId`
and `recipient {name, email}`) returns `subject`, `htmlBody`, `textBody` and
`openVariables`. The Session capability lists the variables and date formats.

**To do:**

- [ ] Add the capability to `using`, and models + API calls for `Template`,
      `TemplateFolder` and `Template/render` in the JMAP layer.
- [ ] Composer: a "Insert template" action next to the signature one. Picker
      with search; the From account's templates first, then top level, then the
      rest. Build the tree client-side: one node per Session account, plus the
      user's folders (`TemplateFolder`).
- [ ] Insert through `Template/render`, never from `Template/get`'s `htmlBody`
      (that still has `{{tokens}}` in it). Pass the first To recipient when
      there is one.
- [ ] `subject` fills an **empty** subject line only.
- [ ] One signature: if the rendered body has a `data-pl-signature` block,
      replace the one already in the draft. Do not swap a block that also has
      `data-pl-signature-pinned` when From changes.
- [ ] Open recipient variables (`openVariables` not empty): markers are
      `<span data-pl-var="…">` in `htmlBody` and `{{recipient.…}}` in
      `subject`/`textBody`. Fill them when To gains a recipient (re-render if
      the user has not edited yet, else replace the markers), show them as
      chips, and **ask before sending** while any remain. Never guess a first
      name from the address.
- [ ] Settings: list, create, edit, move, duplicate and delete templates;
      create, rename and delete folders (a folder cannot be moved — `accountId`
      and `parentId` are create-only). Account folders are not objects and
      cannot be renamed or deleted.
- [ ] Editor: variables as chips, stored as tokens. Date chip settings are
      `offset` (`+7d`, `-2w`, `+1m`) and `format` (a preset from the Session or
      `pattern:<ICU pattern>`); signature chip is automatic, `account=<id>` or
      `alias=<identityId>`.
- [ ] "Save this message as a template" from the composer: strip the quoted
      original and replace the signature block with `{{signature}}` before
      `Template/set create`.
- [ ] The server sanitises `htmlBody`; when the response echoes a different
      `htmlBody`, keep the server's.

## Follow up on reminder notifications

Calendar reminders arrive as pushes since 0.0.29 (pl_mail issue #34): over FCM
they are sealed to a key pair the app registers (`PushKeys`, `SealedPush`,
`PushRepository.deliverSealed`), and over UnifiedPush they arrive already
opened by the connector. `ReminderNotifier` shows them.

**What was verified and what was not.** The decryption is tested against a
payload sealed by the server's own code and against the RFC 8291 vector, and
the registration, delivery and notification paths have unit tests. None of it
has run on a device: no real FCM message has been received, and no reminder has
been seen in a real notification shade.

**To do:**

- [ ] On a `google` build against a server at or after the #34 fixes: set a
      reminder a few minutes out and confirm it arrives. Check Admin → Push on
      the server for the delivery (`Accepted`, not `no-encryption-key`) and
      Settings → Diagnostics in the app for a `CalendarAlert` entry.
- [ ] The same on a `foss` build through a UnifiedPush distributor.
- [ ] A device that was already registered before updating: confirm the keys
      are handed over on the first launch (`ensureSealingKeys`) without the
      subscription losing its verified state.
- [ ] Tapping a reminder opens the calendar, not the day. The payload carries
      `url` (`/calendar/day/2026-10-16`); `CalendarActivity` takes no date yet.
- [ ] `UpdateAvailable` (administrators only) is the other payload the server
      seals. The app does not recognise it and logs it as unknown.
- [ ] The reminders channel is one channel at `HIGH` importance. Decide whether
      it belongs in the app's own notification settings screen beside the
      per-account mail channels.
