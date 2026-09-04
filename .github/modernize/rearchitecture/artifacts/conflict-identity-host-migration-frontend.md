# Conflict Identity Host Migration

## Summary

`VerifyIdentityActivity` and `IdentityKeyParcelable` were removed. Normal verification now opens the
authenticated `ConversationListActivity` host with recipient and subscription IDs. Conflict
verification places the maintained-library `IdentityKey` and its IDs in a memory-only, unlock-generation
bound store and sends the private host only an opaque token.

## Security Evidence

- Conflict entries bind to the exact verification owner, current unlock generation, and a 60-second
  elapsed-time expiry; consumption removes the entry before validation so every outcome is one-shot.
- Wrong-owner, stale-generation, expired, missing, duplicate, malformed, and replaced-token access
  fails closed without exposing or serializing identity material.
- `VERIFY_IDENTITY` accepts exactly one argument shape: recipient/subscription primitive IDs or one
  bounded token. Unexpected keys, mixed shapes, invalid values, and type confusion are rejected.
- `VerifyIdentityFragment` consumes conflict state once during live creation, never saves the identity,
  clears it on relock and view teardown, and navigates up when recreation cannot recover the entry.
- Both the host relock callback and `KeyCachingService.handleClearKey()` synchronously clear the store.
- Caller/reference auditing confirms no class, manifest, bootstrap, retained-policy, or navigation
  reference remains for `VerifyIdentityActivity` or `IdentityKeyParcelable`. Existing translated
  resource identifiers retain their legacy names to avoid unrelated localization churn.

## Changed Files

- Added `ConflictIdentityStore` and focused owner/generation/expiry/consume-once tests.
- Hardened `VerifyIdentityFragment`, `HostNavigationCommand`, `ConversationListActivity`,
  `ConversationActivity`, `ReceiveKeyDialog`, `BootstrapContinuationStore`, and `KeyCachingService`.
- Removed the legacy Activity, parcelable, manifest declaration, retained inventory row, and dead
  bootstrap destination; updated destination tests, policy contracts, and migration documentation.

## Upstream Artifacts Consumed

- `artifacts/identity-recipient-settings-host-migration-frontend.md` - supplied the retained
  `VerifyIdentityActivity` conflict bridge gate and primitive-only host navigation contract.

## Evidence Mapping

- `identity-recipient-settings-host-migration-frontend.md#Retained Gates` -> removed the conflict-only
  Activity after replacing its uncommitted identity transport with the generation-bound one-shot store.
- `identity-recipient-settings-host-migration-frontend.md#Security Evidence` -> preserved normal
  recipient/subscription ID routing and kept all identity material out of Intents, Bundles, and saved state.

## Test Results

- Command: `./gradlew.bat :app:testDebugUnitTest --tests org.smssecure.smssecure.VerifyIdentityFragmentTest --tests org.smssecure.smssecure.HostNavigationCommandTest --tests org.smssecure.smssecure.ConversationListDestinationTest`
- Passed: focused Fragment and host-navigation suites; Failed: 0; Skipped: 0
- Command: focused conflict-store, identity, host-command, destination, bootstrap, retained-Activity,
  manifest, and relock tests plus 10 repository policy gates
- Passed: all focused tests and 10 policy gates; Failed: 0; Skipped: 0
- Command: `./gradlew.bat :app:test`
- Passed: 350; Failed: 0; Skipped: 0
- Command: `./gradlew.bat :app:lint`
- Passed: lint gate (42 existing/baselined warnings reported); Failed: 0
- Command: `./gradlew.bat :app:test :app:lint :app:assembleDebug` (exact final tree)
- Passed: 350 tests, lint gate, debug assembly; Failed: 0; Skipped: 0
- Command: `./gradlew.bat :app:verifyCryptoReleaseStages :app:assemblePhaseARelease :app:assembleRelease :app:assembleDebug`
- Passed: crypto stage verification and all three APK assemblies (279 tasks); Failed: 0
- Connected tests: not run, as required.

## Residual Risks

- Conflict verification intentionally fails closed after process death or Fragment recreation because
  sensitive identity state is not persisted. The user must reopen the conflict link.
- No connected-device UI test was run; JVM/Robolectric, lint, policy, crypto-stage, and release builds
  cover the implemented boundary.