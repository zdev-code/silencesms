# Identity and Recipient Host Boundaries

Bind identity results to primitive context plus unlock generation, and resolve recipient projections from IDs at operation time.

## What Happened

In `silencesms/identity-recipient-settings-host-migration`, local identity and recipient settings moved
to the authenticated host. A shared Fragment scanner owns barcode results. The follow-up
`conflict-identity-host-migration` removed the remaining Activity bridge: uncommitted conflict identities
now use an owner-bound, generation-bound, 60-second, consume-once memory token. Recipient settings
preserve their custom header and mutations without retaining a `Recipients` field.

## Takeaway

Keep recipient/subscription IDs in routes, clone and wipe expected identity bytes around one-shot result
validation, and clear rendered views before relock routing. For uncommitted identity handoff, keep IDs
and maintained-library identity material inside the memory entry and route only a bounded opaque token;
reject recreation after consumption rather than persisting sensitive state. Retain passphrase-change
ownership when an async boundary captures immutable strings or cannot reject a stale unlock generation
before commit.

## History

- 2026-09-04 (silencesms/identity-recipient-settings-host-migration): initial
- 2026-09-04 (silencesms/conflict-identity-host-migration): replaced the final Activity bridge with a generation-bound one-shot token handoff
