# Authentication UPGRADE_DATABASE Host Slice

## Summary

`AuthenticationActivity` now accepts the exact `UPGRADE_DATABASE` surface alongside WELCOME,
CREATE_PASSPHRASE, and PROMPT_PASSPHRASE. Its route remains limited to the surface enum, sanitized
destination enum, and opaque one-shot token. Surface-qualified continuation ownership rejects
cross-surface replay and cold, expired, stale-generation, or duplicate use.

`DatabaseUpgradeFragment` preserves the existing upgrade UI and observes the application-singleton
`DatabaseUpgradeCoordinator` through a lifecycle controller. The coordinator remains the restartable
owner of persisted stages, operation-time generation checks, database/SIM work, job keys, notification
refresh, and version update. Recreation reattaches to current progress without persisting progress or
secret state; stopped/replaced observers and queued callbacks cannot navigate or mutate UI.

`DatabaseUpgradePolicy` now owns versions 143, 200, and 216 plus `isUpdate`, `needsUpgrade`, and the
progress callback contract. `DatabaseFactory`, `DatabaseUpgradeCoordinator`, and `KeyCachingService`
no longer depend on an Activity. The no-upgrade path preserves clear-record, version update, JobManager
key setup, notification refresh, then continuation ordering under the current `UnlockSession`.

`DatabaseUpgradeActivity` was removed from source, manifest, and retained inventory only after all
production callers disappeared. `DatabaseMigrationActivity` and `PassphraseChangeActivity` remain.

## Changed Files

- Auth host/UI: `AuthenticationActivity.java`, `DatabaseUpgradeFragment.java`,
  `ui/databaseupgrade/DatabaseUpgradeController.java`, `database_upgrade_activity.xml`
- Policy/work: `domain/upgrade/DatabaseUpgradePolicy.java`, `DatabaseUpgradeCoordinator.java`,
  `DatabaseFactory.java`, `KeyCachingService.java`, `PassphraseRequiredActionBarActivity.java`
- Removal/contracts: deleted `DatabaseUpgradeActivity.java`; updated `AndroidManifest.xml` and
  `app/config/retained-activities.tsv`
- Tests: `AuthenticationActivityTest.java`, `DatabaseUpgradeFragmentTest.java`,
  `BootstrapContinuationStoreTest.java`, `RetainedActivityContractTest.java`,
  `DatabaseUpgradePolicyTest.java`, `DatabaseUpgradeControllerTest.java`, and
  `DatabaseUpgradeCoordinatorTest.java`
- Status: `docs/single-activity-security-migration-plan.md`, `docs/plan-index.md`
- Generated knowledge graph: `graphify-out/GRAPH_REPORT.md`, `graph.html`, `graph.json`, `manifest.json`

## Upstream Artifacts Consumed

- `.github/modernize/rearchitecture/artifacts/authentication-bootstrap-continuation-frontend.md` -
  owner-bound, expiring, consume-once continuation and fresh-policy fallback contract.
- `.github/modernize/rearchitecture/artifacts/authentication-prompt-passphrase-host-frontend.md` -
  exact auth-host surface validation, restoration, and lifecycle navigation ownership.

## Evidence Mapping

- `authentication-bootstrap-continuation-frontend.md#Summary` -> exact `AuthenticationActivity:UPGRADE_DATABASE`
  owner tests for mismatch, cold, expiry, stale generation, duplicate use, and retained database migration.
- `authentication-prompt-passphrase-host-frontend.md#Summary` -> one restored upgrade Fragment, exact
  three-field route, no READY/progress/secret saved state, and current-surface lifecycle navigation guard.

## Test Results

- Focused command: `./gradlew.bat :app:testDebugUnitTest --tests org.smssecure.smssecure.DatabaseUpgradeFragmentTest --tests org.smssecure.smssecure.AuthenticationActivityTest --tests org.smssecure.smssecure.BootstrapContinuationStoreTest --tests org.smssecure.smssecure.RetainedActivityContractTest --tests org.smssecure.smssecure.domain.upgrade.DatabaseUpgradePolicyTest --tests org.smssecure.smssecure.ui.databaseupgrade.DatabaseUpgradeControllerTest --tests org.smssecure.smssecure.domain.upgrade.DatabaseUpgradeCoordinatorTest --tests org.smssecure.smssecure.domain.upgrade.UpgradeOperationStoreTest`
- Focused: 56 passed; 0 failed; 0 errors; 0 skipped.
- Policy command: `./gradlew.bat :app:checkNoNewAsyncTaskUsage :app:checkAndroidDeprecationAllowlist :app:checkConversationListArchitecture :app:checkConversationThreadArchitecture :app:checkConversationScreenArchitecture :app:checkEventBusAllowlist :app:checkModernArchitectureBoundaries :app:checkUiDataAccessAllowlist :app:checkRetainedActivityInventory :app:checkHostDestinationSecurityPolicy`
- Policy gates: 10 passed; 0 failed.
- Full command: `./gradlew.bat :app:test`
- Full JVM suite: 401 passed; 0 failed; 0 errors; 0 skipped.
- Lint command: `./gradlew.bat :app:lint`
- Lint: passed with 49 current warnings; `app/lint-baseline.xml` unchanged. Lint reports 42 stale
  baseline entries, which were not removed because this task forbids baseline modification.
- Release command: `./gradlew.bat :app:verifyCryptoReleaseStages :app:assemblePhaseARelease :app:assembleRelease :app:assembleDebug`
- Release: crypto stage verification, Phase A, release, and debug assemblies passed.
- Graph command: `graphify update .`
- Graph: passed with 11,597 nodes and 36,358 edges; five pre-existing parser warnings remain for C
  headers and vendored Gradle scripts.
- Connected tests: not run, as required.

## Residual Risks

- Robolectric covers recreation, route/state persistence, lifecycle callback suppression, and
  generation changes. No connected-device process-kill, first-draw, or task-restoration validation was
  run.
- The protected lint baseline intentionally retains stale entries, including the removed Activity,
  because the task explicitly forbids baseline edits.