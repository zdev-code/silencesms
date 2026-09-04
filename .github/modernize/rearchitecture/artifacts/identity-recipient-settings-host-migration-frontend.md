# Identity and Recipient Settings Host Migration

## Summary

Local identity viewing, normal conversation identity verification, and recipient settings are now
owned by the authenticated `ConversationListActivity` Navigation host. Routes contain only recipient
IDs, subscription IDs, and destination primitives. `ViewIdentityActivity` and
`RecipientPreferenceActivity` were removed. `VerifyIdentityActivity` remains a thin non-exported
Fragment host for the uncommitted conflict identity supplied by `ReceiveKeyDialog`.

`PassphraseChangeActivity` remains retained. Its atomic storage coordinator is preserved, but the
current async boundary captures immutable passphrase `String` values and is not unlock-generation
bound, so moving it would not satisfy synchronous wipe and stale-generation requirements.

## Security Evidence

- Local identity material is derived through a current-generation capability at render, scan,
  display, and share operation time.
- Scan callbacks are one-shot and bound to expected recipient ID, subscription ID, identity bytes,
  and unlock generation; stale, duplicate, cross-recipient, cross-subscription, and replaced-identity
  results are rejected.
- Relock synchronously clears fingerprints, QR images, pending scan bindings, injected conflict keys,
  recipient header views, dialogs, and pending recipient mutations.
- Recipient preference Fragments retain cloned ID arrays only and resolve `Recipients` inside
  generation-gated render and action calls.
- Navigation contains no identity parcelable, fingerprint, secret, passphrase, recipient object,
  plaintext, URI, or arbitrary intent payload.

## Retained Gates

- `VerifyIdentityActivity`: retained only as a thin Fragment host for `ReceiveKeyDialog` conflict
  identities that are not yet committed to session storage.
- `PassphraseChangeActivity`: retained until passphrase material is wipeable, async replacement is
  unlock-generation bound/cancel-safe, and the existing atomic wrapper activation tests remain valid.

## Upstream Artifacts Consumed

- none - no dependency artifacts provided

## Evidence Mapping

- none - no dependency artifacts provided

## Test Results

- Command: focused identity, recipient, destination, command, retained, and atomic passphrase tests;
  all ten policy gates; Java/Kotlin compilation; `:app:assembleDebug`
- Passed: 55 tests, 10 policy gates, Java compile, Kotlin compile, debug assembly
- Failed: 0
- Skipped: 0
- Connected tests: not run, as required
