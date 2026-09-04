# Conversation Host Migration - Completed

## Summary

`ConversationListActivity` is now the authoritative normal conversation host and back-stack owner. The normal conversation screen is a Navigation destination backed by a reusable Fragment/controller; `ConversationPopupActivity` retains its distinct Activity/window behavior through a thin Fragment host. Sensitive external text and media use a generation-bound, one-shot `ConversationPayloadStore` handoff rather than Navigation arguments.

## Changes

- Extracted the normal conversation controller into `ConversationScreenFragment`, including its six platform launchers, draft, crypto, SIM, payload, and relock behavior.
- Reduced `ConversationActivity` to the popup-compatible Fragment host and removed it as a normal manifest destination.
- Added strict typed conversation routing to `ConversationListActivity`, including replacement of an already-visible conversation and selector pop-before-navigation behavior.
- Removed host command extras synchronously before dispatch and allowlisted only primitive route fields plus an opaque payload token.
- Tokenized share and SENDTO handoff through `ConversationPayloadStore`; the child message-list Fragment no longer reads host Intent state.
- Retained `ConversationPopupActivity` for popup window semantics and `MessageDetailsActivity` for its distinct secure result flow.

## Upstream Artifacts Consumed

- none - no dependency artifacts provided

## Evidence Mapping

- none - no dependency artifacts provided

## Test Results

- Command: `./gradlew.bat :app:testDebugUnitTest --tests org.smssecure.smssecure.ConversationListDestinationTest --tests org.smssecure.smssecure.HostNavigationCommandTest --tests org.smssecure.smssecure.RetainedActivityContractTest --tests org.smssecure.smssecure.domain.conversation.ConversationPayloadStoreTest :app:compileDebugJavaWithJavac`
- Passed: 4 focused suites and Java compilation; build successful
- Failed: 0
- Skipped: 0
- Command: `./gradlew.bat :app:assembleDebug`
- Passed: build successful
- Failed: 0
- Skipped: 0
- Command: `./gradlew.bat :app:checkRetainedActivityInventory :app:checkHostDestinationSecurityPolicy`
- Passed: 2 policy tasks
- Failed: 0
- Skipped: 0
- Command: `graphify update .`
- Passed: graph rebuilt with 10,704 nodes and 33,939 edges
- Failed: 0 (five pre-existing parser warnings in C headers and vendored Gradle files)
- Skipped: 0

## Retained Boundary

`MessageDetailsActivity` remains intentionally retained. Moving it is not required for authoritative conversation hosting and would require a separate retained-secret/result-flow redesign. No connected-device tests were run.
