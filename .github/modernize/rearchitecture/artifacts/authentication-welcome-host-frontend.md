# Authentication Welcome Host Slice

## Summary

Authentication host consolidation is partial. Private `AuthenticationActivity` now accepts only the
`WELCOME` surface plus the bootstrap store's sanitized destination and opaque token, and hosts only
`WelcomeFragment`. The former welcome layouts, status color, permission arrays, dialogs, result
handling, first-run flags, transition, and permission notification semantics are preserved.

Completion consumes the continuation once under `AuthenticationActivity` ownership and starts its
explicit sanitized target. Missing, cold, duplicate, expired, malformed, or unusable continuations
fail closed to explicit private `ConversationListActivity`, which freshly reevaluates
`AuthenticationBootstrapController`. The authentication host has no application Navigation graph and
cannot restore protected Fragments.

`WelcomeActivity` is removed. Passphrase creation, passphrase prompting, database upgrade, and database
migration remain separate Activities and were not migrated in this slice.

## Upstream Artifacts Consumed

- `.github/modernize/rearchitecture/artifacts/authentication-bootstrap-continuation-frontend.md` - owner-bound, expiring, consume-once continuation and fresh-policy fallback contract.

## Evidence Mapping

- `authentication-bootstrap-continuation-frontend.md#Summary` -> `AuthenticationActivity` exact Intent validation, `BootstrapContinuationStore` ownership, and cold/duplicate/expired fallback tests.
- `docs/single-activity-security-migration-plan.md#12-authenticationbootstrap-controller-consolidation` -> WELCOME-only Fragment host with no protected Navigation graph; remaining auth surfaces explicitly retained.

## Test Results

- Command: `./gradlew.bat :app:testDebugUnitTest --tests org.smssecure.smssecure.AuthenticationActivityTest --tests org.smssecure.smssecure.BootstrapContinuationStoreTest --tests org.smssecure.smssecure.domain.security.AuthenticationBootstrapControllerTest --tests org.smssecure.smssecure.RetainedActivityContractTest`
- Passed: 30; Failed: 0; Skipped: 0.
- Command: `./gradlew.bat :app:checkNoNewAsyncTaskUsage :app:checkAndroidDeprecationAllowlist :app:checkConversationListArchitecture :app:checkConversationThreadArchitecture :app:checkConversationScreenArchitecture :app:checkEventBusAllowlist :app:checkModernArchitectureBoundaries :app:checkUiDataAccessAllowlist :app:checkRetainedActivityInventory :app:checkHostDestinationSecurityPolicy`
- Passed: 10 architecture/policy gates; Failed: 0; Skipped: 0.
- Command: `./gradlew.bat :app:test`
- Passed: 360; Failed: 0; Skipped: 0.
- Command: `./gradlew.bat :app:lint`
- Passed: build with 44 current warnings and no unbaselined errors; Failed: 0. The existing dirty baseline reports 37 stale entries and was not refreshed.
- Command: `./gradlew.bat :app:verifyCryptoReleaseStages :app:assemblePhaseARelease :app:assembleRelease :app:assembleDebug`
- Passed: crypto stage verification plus Phase A, release, and debug assemblies; Failed: 0; Skipped: 0.
- Command: `graphify update .`
- Passed: 11,239 nodes, 35,367 edges, and 351 communities; five pre-existing parser warnings remain in C headers and vendored Gradle files.
- Connected tests: not run, as required.

## Residual Risks

- Rotation/process restoration and permission dialogs are covered by Robolectric contracts, but no device lifecycle or permission UI validation was run in this batch.
- Passphrase create/prompt and database upgrade/migration remain separate Activities. Their later migration must preserve wipeable secret ownership, restartability, and rollback behavior independently.