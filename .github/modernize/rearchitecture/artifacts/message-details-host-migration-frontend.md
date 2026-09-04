# Message Details Host Migration - Completed

## Summary

`MessageDetailsFragment` is now the sole normal message-details owner under `ConversationListActivity`.
Routes contain only positive message, thread, and recipient IDs plus an integer SMS/MMS transport type.
Generation-gated repository loading supplies sensitive data in memory, and host relock synchronously clears
the ViewModel, bound message rows, recipient rows, identity dialog, and secret reference before auth routing.

Failed-message taps and context-menu details actions route through `ConversationScreenFragment`. Recipient
resend and identity-conflict behavior remains unchanged, and Navigation back returns to that conversation.
The legacy `MessageDetailsActivity`, manifest declaration, and retained-Activity inventory row were removed.

## Upstream Artifacts Consumed

- `.github/modernize/rearchitecture/artifacts/conversation-host-migration-frontend.md` - authoritative host ownership, primitive route, relock, and deferred MessageDetails boundary.

## Evidence Mapping

- `conversation-host-migration-frontend.md#Retained Boundary` -> `MessageDetailsFragment`, strict `MESSAGE_DETAILS` route tests, synchronous host cleanup, and deleted legacy Activity.
- `conversation-host-migration-frontend.md#Summary` -> details destination is stacked above the authoritative `ConversationScreenFragment` host route.

## Test Results

- Command: `./gradlew.bat :app:testDebugUnitTest --tests org.smssecure.smssecure.ui.messagedetails.MessageDetailsViewModelTest --tests org.smssecure.smssecure.ConversationListDestinationTest --tests org.smssecure.smssecure.HostNavigationCommandTest --tests org.smssecure.smssecure.RetainedActivityContractTest`
- Passed: 24
- Failed: 0
- Skipped: 0
- Command: `./gradlew.bat :app:compileDebugJavaWithJavac :app:checkRetainedActivityInventory :app:checkHostDestinationSecurityPolicy`
- Passed: Java compilation and 2 policy gates
- Failed: 0
- Skipped: 0
- Command: `./gradlew.bat :app:lint :app:assembleDebug`
- Passed: lint and debug APK assembly; 29 non-fatal lint warnings
- Failed: 0
- Skipped: 0
- Command: `graphify update .`
- Passed: graph rebuilt with 10,717 nodes and 33,961 edges
- Failed: 0; five pre-existing parser warnings in C headers and vendored Gradle files
- Skipped: 0

No connected-device tests were run.
