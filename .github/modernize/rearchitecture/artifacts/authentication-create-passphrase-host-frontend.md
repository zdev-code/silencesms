# Authentication CREATE_PASSPHRASE Host Slice

## Summary

`AuthenticationActivity` now accepts exactly `WELCOME` or `CREATE_PASSPHRASE` plus the existing
sanitized destination and opaque continuation token. It restores only the matching authentication
Fragment and still contains no application Navigation graph or protected Fragment references.

`PassphraseCreateFragment` preserves the legacy progress layout and centered action bar. Its dedicated
controller owns the serial `AppTaskExecutor` handle, operation generation, cancellation, retry, and
late-callback suppression while preserving the existing master-secret, asymmetric-secret, per-SIM
identity, version-tracker, and password-disabled writes. A reusable completion coordinator owns the
temporary `MasterSecret`, primes/starts/binds/sets `KeyCachingService`, rechecks owner, Activity, and
cache identity before activation, hides the pending prime from cache/snapshot readers until checked
promotion, and conditionally discards only its pending primed secret on teardown.

Successful establishment drops coordinator-held secret state before `AuthenticationActivity` consumes
the continuation once under its own owner and launches the sanitized target. Missing, cold, stale,
expired, duplicate, or malformed continuations fall back to private `ConversationListActivity` for
fresh policy evaluation. No READY state is saved.

`PassphraseCreateActivity` is removed from source, manifest, routing, and retained inventory.
`PassphrasePromptActivity`, `DatabaseUpgradeActivity`, `DatabaseMigrationActivity`, and
`PassphraseChangeActivity` remain separate and unchanged in ownership.

## Changed Files

- `app/src/main/java/org/smssecure/smssecure/AuthenticationActivity.java`
- `app/src/main/java/org/smssecure/smssecure/PassphraseCreateFragment.java`
- `app/src/main/java/org/smssecure/smssecure/ui/passphrasecreate/PassphraseCreateController.java`
- `app/src/main/java/org/smssecure/smssecure/ui/authentication/AuthenticationCompletionCoordinator.java`
- `app/src/main/java/org/smssecure/smssecure/BootstrapContinuationStore.java`
- `app/src/main/java/org/smssecure/smssecure/service/KeyCachingService.java`
- `app/src/main/java/org/smssecure/smssecure/PassphraseRequiredActionBarActivity.java`
- `app/src/main/java/org/smssecure/smssecure/PassphraseActivity.java`
- `app/src/main/java/org/smssecure/smssecure/PassphraseCreateActivity.java` (removed)
- `app/src/main/AndroidManifest.xml`
- `app/config/retained-activities.tsv`
- Focused tests under `app/src/test/java/org/smssecure/smssecure/`
- `docs/single-activity-security-migration-plan.md` and `docs/plan-index.md`
- Incrementally refreshed `graphify-out/` graph artifacts

## Upstream Artifacts Consumed

- `.github/modernize/rearchitecture/artifacts/authentication-bootstrap-continuation-frontend.md` - owner-bound consume-once continuation and fresh-policy fallback contract.
- `.github/modernize/rearchitecture/artifacts/authentication-welcome-host-frontend.md` - exact private authentication host and restoration boundary.
- `.github/modernize/rearchitecture/artifacts/passphrase-pipeline-hardening-frontend.md` - cancellable generation ownership and stale-callback suppression conventions.

## Evidence Mapping

- `authentication-bootstrap-continuation-frontend.md#Summary` -> exact two-surface route validation, AuthenticationActivity-owned consume, and cold/duplicate/expired fallback tests.
- `authentication-welcome-host-frontend.md#Summary` -> exactly one matching authentication Fragment restored with no app graph or protected Fragment references.
- `passphrase-pipeline-hardening-frontend.md#Security Boundary` -> controller generation invalidation, task cancellation, retry, and late success/failure suppression tests.

## Test Results

- Command: `./gradlew.bat :app:testDebugUnitTest --tests org.smssecure.smssecure.AuthenticationActivityTest --tests org.smssecure.smssecure.ui.passphrasecreate.PassphraseCreateControllerTest --tests org.smssecure.smssecure.ui.authentication.AuthenticationCompletionCoordinatorTest --tests org.smssecure.smssecure.BootstrapContinuationStoreTest --tests org.smssecure.smssecure.domain.security.AuthenticationBootstrapControllerTest --tests org.smssecure.smssecure.RetainedActivityContractTest`
- Passed: 43; Failed: 0; Skipped: 0.
- Command: `./gradlew.bat :app:checkNoNewAsyncTaskUsage :app:checkAndroidDeprecationAllowlist :app:checkConversationListArchitecture :app:checkConversationThreadArchitecture :app:checkConversationScreenArchitecture :app:checkEventBusAllowlist :app:checkModernArchitectureBoundaries :app:checkUiDataAccessAllowlist :app:checkRetainedActivityInventory :app:checkHostDestinationSecurityPolicy`
- Passed: 10 policy gates; Failed: 0; Skipped: 0.
- Command: `./gradlew.bat :app:test`
- Passed: 373; Failed: 0; Skipped: 0.
- Command: `./gradlew.bat :app:lint`
- Passed: build with 47 current warnings and no unbaselined errors; Failed: 0. The baseline has 41 stale entries and was not modified.
- Command: `./gradlew.bat :app:verifyCryptoReleaseStages :app:assemblePhaseARelease :app:assembleRelease :app:assembleDebug`
- Passed: crypto stage verification plus Phase A, release, and debug assemblies; Failed: 0; Skipped: 0.
- Command: `graphify update .`
- Passed: 11,384 nodes, 35,750 edges, and 362 communities; five pre-existing parser warnings remain in C headers and vendored Gradle files.
- Connected tests: not run, as required.

## Residual Risks

- Robolectric covers Fragment recreation and lifecycle invalidation, but no device service-binding or process-kill timing validation was run in this batch.
- Cancellation prevents late activation and removes the exact pending primed cache reference; already persisted key-generation output intentionally retains the existing storage and retry semantics.
