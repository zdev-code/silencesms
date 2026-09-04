# Exported Router Minimization - Completed

## Summary

The retained launcher, ACTION_SEND, and SMS/MMS protocol Activities are transparent exported shells.
They validate bounded external input, clear source Intent state, dispatch only an opaque payload token
to the non-exported authenticated host, and finish without rendering protected UI or retaining a secret.

## Changes

- `RoutingActivity` enforces the exact data-free MAIN/LAUNCHER contract and explicitly targets the private host.
- `ShareActivity` validates one bounded text/stream share, delegates only read access, stores cleanup with the token, and preserves direct-share metadata inside memory only.
- `SmsSendtoActivity` accepts exact SENDTO/VIEW sms, smsto, mms, and mmsto URIs, rejects ambiguous fields, normalizes bounded recipients to IDs, and never waits on UI state.
- `NewConversationFragment` privately encrypts external media into app-owned storage, revokes delegated grants after replacement, auto-routes preselected shares, and privately resolves SENDTO recipients.
- `ConversationPayloadStore` supports locked issuance followed by first-host generation binding, route inspection, one-shot consumption, and atomic media cleanup transfer.
- The obsolete protected `ShareFragment` and share bootstrap continuation were removed.

## Upstream Artifacts Consumed

- `.github/modernize/rearchitecture/artifacts/final-normal-flow-migration-frontend.md` - established selector ownership and opaque payload retargeting.
- `.github/modernize/rearchitecture/artifacts/authentication-bootstrap-continuation-frontend.md` - established locked continuation binding and typed private-host bootstrap rules.
- `docs/single-activity-security-migration-plan.md` - supplied router exit criteria and retained-boundary requirements.

## Evidence Mapping

- `final-normal-flow-migration-frontend.md#Changes` -> selector-owned token inspection, retargeting, and cleanup transfer.
- `authentication-bootstrap-continuation-frontend.md#Summary` -> locked payload issuance binds at first authenticated host validation and stale generations fail closed.
- `single-activity-security-migration-plan.md#Worthwhile follow-up tracks` -> all three exported routers are immediate, UI-free validation shells.

## Test Results

- Command: focused `ExternalConversationIntentParserTest`, `RetainedActivityContractTest`, `RoutingActivityTest`, `ConversationPayloadStoreTest`, `HostNavigationCommandTest`, and `BootstrapContinuationStoreTest`
- Passed: all 44 focused tests
- Failed: 0
- Skipped: 0
- Command: all ten app architecture and policy gates
- Passed: 10
- Failed: 0
- Skipped: 0
- Command: `./gradlew.bat :app:test`
- Passed: full app JVM suite
- Failed: 0
- Skipped: 0
- Command: `./gradlew.bat :app:lint`
- Passed: build successful; 42 warnings and 35 stale baseline entries reported, baseline unchanged
- Failed: 0
- Skipped: 0
- Command: `./gradlew.bat :app:verifyCryptoReleaseStages :app:assemblePhaseARelease :app:assembleRelease :app:assembleDebug`
- Passed: crypto stage verification and all three APK assemblies
- Failed: 0
- Skipped: 0
- Command: `graphify update .`
- Passed: graph rebuilt with 11,149 nodes and 35,108 edges
- Failed: 0; five pre-existing parser warnings in C headers and vendored Gradle files
- Skipped: 0

No connected-device tests were run. A combined lint-and-release invocation exposed an Android lint
generated-source race; standalone lint and the standalone release matrix both passed against the same
final source tree.