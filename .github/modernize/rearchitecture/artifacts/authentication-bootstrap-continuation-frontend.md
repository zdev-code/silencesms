# Authentication Bootstrap Continuation - Completed

## Summary

`BootstrapContinuationStore` now owns a 15-minute, memory-only continuation contract. Gate Intents
carry only a destination enum and opaque token. Entries are explicit-component allowlisted, sanitized,
owner-bound, consume-once, and generation-bound when created while unlocked. Cold process state,
wrong ownership, expiry, duplicate use, and stale unlock generations fail closed to a fresh private
inbox policy evaluation.

Welcome, passphrase creation/prompt, database upgrade, and database migration use this contract without
removing their Activities. Upgrade and SMS import reacquire the current `MasterSecret` through a captured
`UnlockSession`; the import service receives only the primitive unlock generation. Existing Share,
conversation, popup, media-preview, and identity bridge target contracts remain intact.

## Upstream Artifacts Consumed

- `.github/modernize/rearchitecture/artifacts/prompt-mms-import-export-host-migration-frontend.md` - generation-bound import/export operation and relock conventions.

## Evidence Mapping

- `prompt-mms-import-export-host-migration-frontend.md#Summary` -> import permission callback retains its captured generation while service and migration Activity launches no longer carry a secret or nested Intent.
- `docs/single-activity-security-migration-plan.md#12-authenticationbootstrap-controller-consolidation` -> typed bounded continuation store, fail-closed recovery, and operation-time secret acquisition.

## Test Results

- Command: `./gradlew.bat :app:testDebugUnitTest --tests org.smssecure.smssecure.BootstrapContinuationStoreTest --tests org.smssecure.smssecure.domain.security.AuthenticationBootstrapControllerTest --tests org.smssecure.smssecure.domain.security.UnlockSessionTest --tests org.smssecure.smssecure.RoutingActivityTest --tests org.smssecure.smssecure.RetainedActivityContractTest --tests org.smssecure.smssecure.ConversationListDestinationTest --tests org.smssecure.smssecure.domain.upgrade.DatabaseUpgradeCoordinatorTest --tests org.smssecure.smssecure.domain.upgrade.UpgradeOperationStoreTest --tests org.smssecure.smssecure.ui.importexport.ImportExportViewModelTest --tests org.smssecure.smssecure.backup.SecureBackupRestoreCoordinatorTest --tests org.smssecure.smssecure.domain.conversation.ConversationPayloadStoreTest --tests org.smssecure.smssecure.domain.media.MediaPreviewDraftStoreTest --tests org.smssecure.smssecure.IdentityScanBindingTest`
- Passed: 65
- Failed: 0
- Skipped: 0
- Command: `./gradlew.bat :app:compileDebugJavaWithJavac :app:checkNoNewAsyncTaskUsage :app:checkAndroidDeprecationAllowlist :app:checkConversationListArchitecture :app:checkConversationThreadArchitecture :app:checkConversationScreenArchitecture :app:checkEventBusAllowlist :app:checkModernArchitectureBoundaries :app:checkUiDataAccessAllowlist :app:checkRetainedActivityInventory :app:checkHostDestinationSecurityPolicy`
- Passed: Java compilation and 10 policy gates
- Failed: 0
- Skipped: 0
- Command: `./gradlew.bat :app:verifyCryptoReleaseStages :app:assemblePhaseARelease :app:assembleRelease :app:assembleDebug`
- Passed: crypto stage verification and all three APK assemblies
- Failed: 0
- Skipped: 0
- Command: `./gradlew.bat :app:testDebugUnitTest --tests org.smssecure.smssecure.BootstrapContinuationStoreTest :app:lint`
- Passed: final continuation contract and full app lint
- Failed: 0
- Skipped: 0
- Command: `graphify update .`
- Passed: graph rebuilt with 11,003 nodes and 34,779 edges
- Failed: 0; five pre-existing parser warnings in C headers and vendored Gradle files
- Skipped: 0

No connected-device tests were run. The lint baseline was not refreshed.