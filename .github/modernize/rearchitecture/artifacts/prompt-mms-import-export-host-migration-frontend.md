# Prompt MMS and Import/Export Host Migration - Completed

## Summary

`PromptMmsDialogFragment` is now the sole normal MMS configuration prompt. Both conversation callers
request the zero-argument host modal; OK navigates to the existing MMS preferences destination and
cancel dismisses. `ImportExportFragment` is a strict `ConversationListActivity` destination with locale
as its only permitted route state.

Import/export document launchers retain only captured unlock generations and memory-only recovery-key
bytes. Every permission or document callback consumes its generation once, and APIs receive
`MasterSecret` only inside the protected invocation. Relock synchronously invalidates pending callbacks,
cancels worker tasks, suppresses late results, dismisses dialogs, and wipes recovery material before
authentication routing. The existing encrypted archive coordinator remains the owner of atomic staging,
verification, commit, rollback, and interruption behavior.

The two legacy Activities, manifest declarations, and retained-Activity inventory rows were removed.

## Upstream Artifacts Consumed

- `.github/modernize/rearchitecture/artifacts/message-details-host-migration-frontend.md` - authoritative host destination, relock ordering, and strict primitive-route conventions.

## Evidence Mapping

- `message-details-host-migration-frontend.md#Summary` -> host-owned modal/import-export destinations and synchronous fragment cleanup in `ConversationListActivity.onMasterSecretCleared()`.
- `message-details-host-migration-frontend.md#Test Results` -> matching strict destination and retained-Activity contract coverage for Activity removal.

## Test Results

- Command: `./gradlew.bat :app:testDebugUnitTest --tests org.smssecure.smssecure.backup.SecureBackupRestoreCoordinatorTest --tests org.smssecure.smssecure.ui.importexport.ImportExportViewModelTest --tests org.smssecure.smssecure.ConversationListDestinationTest --tests org.smssecure.smssecure.RetainedActivityContractTest`
- Passed: 25
- Failed: 0
- Skipped: 0
- Command: `./gradlew.bat :app:compileDebugJavaWithJavac :app:checkNoNewAsyncTaskUsage :app:checkAndroidDeprecationAllowlist :app:checkConversationListArchitecture :app:checkConversationThreadArchitecture :app:checkConversationScreenArchitecture :app:checkEventBusAllowlist :app:checkModernArchitectureBoundaries :app:checkUiDataAccessAllowlist :app:checkRetainedActivityInventory :app:checkHostDestinationSecurityPolicy`
- Passed: Java compilation and 10 policy gates
- Failed: 0
- Skipped: 0
- Command: `./gradlew.bat :app:assembleDebug`
- Passed: debug APK assembly
- Failed: 0
- Skipped: 0
- Command: `graphify update .`
- Passed: graph rebuilt with 10,748 nodes and 34,018 edges
- Failed: 0; five pre-existing parser warnings in C headers and vendored Gradle files
- Skipped: 0

No connected-device tests were run.
