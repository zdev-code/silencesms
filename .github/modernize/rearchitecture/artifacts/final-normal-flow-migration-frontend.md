# Final Normal-Flow Migration - Completed

## Summary

`NewConversationActivity`, `PushContactSelectionActivity`, and the unused country-selection flow are removed. New-conversation and group-contact selection now run inside the private authenticated host without persisting plaintext, URIs, recipient addresses, or selector state.

## Changes

- `ShareActivity` and `SmsSendtoActivity` create generation-, owner-, expiry-, and cleanup-bound payload entries and launch a typed `NEW_CONVERSATION` host command containing only an opaque token.
- `NewConversationFragment` retains only the opaque token for configuration changes, validates its memory entry before rendering, and atomically retargets the payload to validated recipient IDs and the conversation owner.
- `ConversationPayloadStore` preserves one-shot consumption, unlock generation, expiry, and cleanup while retargeting; cold process startup sweeps orphaned app-owned capture blobs.
- `PushContactSelectionFragment` preserves multi-select and cancellation, returning only unique positive recipient IDs through a one-shot FragmentResult.
- Host relock synchronously clears selector query text, adapters, selections, pending callbacks, group members, and payload entries before graph reset.
- No active caller referenced `CountrySelectionActivity`; its Activity, Fragment, ViewModel, settings helper, layouts, manifest declaration, and retained-inventory row were removed.
- `ContactSelectionActivity` was removed after both subclasses became dead.

## Upstream Artifacts Consumed

- `.github/modernize/rearchitecture/artifacts/conversation-host-migration-frontend.md` - established private-host command and generation-bound payload contracts.
- `docs/single-activity-security-migration-plan.md` - supplied security invariants, removal gates, and retained-boundary requirements.

## Evidence Mapping

- `conversation-host-migration-frontend.md#Changes` -> typed `NEW_CONVERSATION` host command, one-shot payload retargeting, and strict destination tests.
- `single-activity-security-migration-plan.md#Security validation required in every phase` -> focused payload, command, destination, relock, retained-contract, policy, lint, and build evidence below.
- `single-activity-security-migration-plan.md#Reassess authentication and distinct Activities` -> removal of three normal-flow Activities, dead country support code, and obsolete selector base.

## Test Results

- Command: focused GroupCreateViewModel, NewConversationViewModel, ConversationPayloadStore, HostNavigationCommand, ConversationListDestination, and RetainedActivityContract suites plus Java/Kotlin compilation and all app policy gates
- Passed: all focused suites, compilation tasks, and selected architecture/policy gates
- Failed: 0
- Skipped: 0
- Command: `./gradlew.bat :app:test`
- Passed: 308
- Failed: 0
- Skipped: 0
- Command: `./gradlew.bat :app:lint`
- Passed: build successful; 36 warnings, with 34 now-stale baseline entries reported but baseline unchanged
- Failed: 0
- Skipped: 0
- Command: `./gradlew.bat :app:assembleDebug`
- Passed: build successful
- Failed: 0
- Skipped: 0
- Command: `graphify update .`
- Passed: graph rebuilt with 10,922 nodes and 34,560 edges
- Failed: 0; five pre-existing parser warnings in C headers and vendored Gradle files
- Skipped: 0

No connected-device tests were run, as required.
