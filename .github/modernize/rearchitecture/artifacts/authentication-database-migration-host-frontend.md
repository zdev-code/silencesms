# Authentication DATABASE_MIGRATION Host Slice

## Summary

`AuthenticationActivity` now owns the exact `DATABASE_MIGRATION` surface alongside WELCOME,
CREATE_PASSPHRASE, PROMPT_PASSPHRASE, and UPGRADE_DATABASE. Its route remains limited to the surface
enum, sanitized destination enum, and opaque one-shot token. Surface-qualified ownership rejects
cross-surface substitution, arbitrary nested authentication tokens, cold state, expiry, stale unlock
generation, and duplicate consumption.

`DatabaseMigrationFragment` preserves the existing prompt and progress layout, explicit import/skip
semantics, and non-dismissible Back behavior. `DatabaseMigrationController` pairs service binding and
completion-receiver registration under Fragment lifecycle, clears Handler work and service references
on teardown, tolerates disconnect-before-connect, and generation-guards stopped or replaced callbacks.
Recreation rebinds and asks `ApplicationMigrationService` for current state; durable imported state
also completes a surface that resumes after the service stopped while it was off-screen.

Import captures the current `UnlockSession`; `ApplicationMigrationService.createMigrationIntent`
receives only its primitive generation and reacquires the secret during execution. The service remains
the durable operation owner, so Fragment teardown does not cancel import. No secret, progress object,
URI, nested Intent, READY marker, or protected state enters Fragment arguments or saved state.

`SystemSmsImportReminder` and `ImportExportFragment` now launch the authentication migration surface.
The obsolete `DatabaseMigrationActivity` source, manifest entry, and retained-inventory row were
removed. `PassphraseChangeActivity` remains a separate hardened private security flow pending its own
host slice.

## Changed Files

- Host and route: `AuthenticationActivity.java`, `BootstrapContinuationStore.java`
- UI and lifecycle: `DatabaseMigrationFragment.java`,
  `ui/databasemigration/DatabaseMigrationController.java`, existing `database_migration_activity.xml`
- Callers/removal: `ImportExportFragment.java`, `components/reminder/SystemSmsImportReminder.java`,
  deleted `DatabaseMigrationActivity.java`, `AndroidManifest.xml`, `app/config/retained-activities.tsv`
- Tests: `AuthenticationActivityTest.java`, `BootstrapContinuationStoreTest.java`,
  `DatabaseMigrationFragmentTest.java`, `ui/databasemigration/DatabaseMigrationControllerTest.java`,
  `DatabaseUpgradeFragmentTest.java`, `RetainedActivityContractTest.java`
- Status: `docs/single-activity-security-migration-plan.md`, `docs/plan-index.md`
- Generated knowledge graph: `graphify-out/GRAPH_REPORT.md`, `graph.html`, `graph.json`, `manifest.json`

## Upstream Artifacts Consumed

- `.github/modernize/rearchitecture/artifacts/authentication-bootstrap-continuation-frontend.md` -
  owner-bound, expiring, consume-once continuation and operation-time generation contract.
- `.github/modernize/rearchitecture/artifacts/authentication-database-upgrade-host-frontend.md` -
  exact auth-host route, lifecycle reattachment, saved-state exclusion, and nested upgrade handoff.

## Evidence Mapping

- `authentication-bootstrap-continuation-frontend.md#Summary` -> exact
  `AuthenticationActivity:DATABASE_MIGRATION` owner tests, generation-only service Intent, and
  cold/expired/stale/duplicate fail-closed coverage.
- `authentication-database-upgrade-host-frontend.md#Summary` -> nested UPGRADE_DATABASE to
  DATABASE_MIGRATION continuation validation, one restored Fragment, current-surface completion guard,
  lifecycle reattachment, and no progress/readiness/secret saved state.

## Test Results

- Focused command: `./gradlew.bat :app:testDebugUnitTest --tests org.smssecure.smssecure.DatabaseMigrationFragmentTest --tests org.smssecure.smssecure.ui.databasemigration.DatabaseMigrationControllerTest --tests org.smssecure.smssecure.AuthenticationActivityTest --tests org.smssecure.smssecure.BootstrapContinuationStoreTest --tests org.smssecure.smssecure.domain.security.AuthenticationBootstrapControllerTest --tests org.smssecure.smssecure.domain.security.UnlockSessionTest --tests org.smssecure.smssecure.ui.authentication.AuthenticationCompletionCoordinatorTest --tests org.smssecure.smssecure.DatabaseUpgradeFragmentTest --tests org.smssecure.smssecure.ui.databaseupgrade.DatabaseUpgradeControllerTest --tests org.smssecure.smssecure.domain.upgrade.DatabaseUpgradeCoordinatorTest --tests org.smssecure.smssecure.domain.upgrade.UpgradeOperationStoreTest --tests org.smssecure.smssecure.RetainedActivityContractTest`
- Focused: 80 passed; 0 failed; 0 errors; 0 skipped.
- Policy command: `./gradlew.bat :app:checkNoNewAsyncTaskUsage :app:checkAndroidDeprecationAllowlist :app:checkConversationListArchitecture :app:checkConversationThreadArchitecture :app:checkConversationScreenArchitecture :app:checkEventBusAllowlist :app:checkModernArchitectureBoundaries :app:checkUiDataAccessAllowlist :app:checkRetainedActivityInventory :app:checkHostDestinationSecurityPolicy`
- Policy gates: 10 passed; 0 failed.
- Full command: `./gradlew.bat :app:test`
- Full JVM suite: 418 passed; 0 failed; 0 errors; 0 skipped.
- Lint command: `./gradlew.bat :app:lint`
- Lint: passed with 51 current warnings; 45 stale baseline entries were reported and intentionally left
  unchanged. `git diff --exit-code -- app/lint-baseline.xml` passed.
- Release command: `./gradlew.bat :app:verifyCryptoReleaseStages :app:assemblePhaseARelease :app:assembleRelease :app:assembleDebug`
- Release: crypto-stage verification, Phase A, release, and debug assemblies passed.
- Graph command: `graphify update .`
- Graph: passed with 11,696 nodes and 36,696 edges; five pre-existing parser warnings remain for C
  headers and vendored Gradle scripts.
- Connected tests: not run, as required.

## Residual Risks

- Robolectric covers lifecycle replacement, service-state replay, durable completion replay, and route
  restoration. No connected-device process-kill, ordered-broadcast, first-draw, or task-restoration
  validation was run.
- The protected lint baseline intentionally retains stale `DatabaseMigrationActivity` entries because
  this task explicitly forbids baseline modification.
