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

_(none yet)_

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

### JMAP state moves only on real mutations

`app:test:seed-mail` writes messages directly without advancing the Email state, so it cannot
trigger a push however much mail it creates — `queryState` stays put. A real `Email/set` does move
it. This is correct behaviour, not a bug, but it makes the seeder useless for testing push and delta
sync, which is worth knowing before spending an evening on it.
