# Authentication CHANGE_PASSPHRASE Host Slice

## Summary

`AuthenticationActivity` now owns `CHANGE_PASSPHRASE` as an unlocked security surface distinct from
bootstrap gates. Its helper creates the only supported entry: an explicit private host Intent carrying
the surface enum, sanitized `CONVERSATION_LIST` destination, and bounded opaque token. The in-memory
entry is bound to the exact `AuthenticationActivity:CHANGE_PASSPHRASE` owner and current unlock
generation; locked creation, stale/relocked state, wrong owner, cross-surface substitution, expiry,
duplicate consumption, and process loss fail closed.

`PassphraseChangeFragment` preserves the existing theme, language, form, disabled-password compatibility
input, validation, errors, toast, OK, and cancel behavior. Editable content is copied directly into
`WipeablePassphrase` and fields are cleared before dispatch. The existing controller and guarded
`MasterSecretUtil` operation remain authoritative, preserving KDF parameters, wrapper formats,
activation ordering, and atomic rollback.

Relock, cancellation, Back, teardown, and stale callbacks close controller/cache work, wipe fields,
discard route authority, and suppress navigation. After guarded activation succeeds, the lifecycle-
checked `AuthenticationCompletionCoordinator` establishes the replacement secret. The exact route is
then rebound from the pre-change generation to the newly cached generation and consumed once to return
to the private app-protection settings destination. The legacy `PassphraseChangeActivity`, unused
`PassphraseActivity`, manifest entry, and retained-inventory row are removed.

## Changed Files

- Host/route: `AuthenticationActivity.java`, `BootstrapContinuationStore.java`,
  `AuthenticationCompletionCoordinator.java`, `HostNavigationCommand.java`,
  `ConversationListActivity.java`
- UI/callers: new `PassphraseChangeFragment.java`, `AppProtectionPreferenceFragment.java`
- Removal/policy: deleted `PassphraseChangeActivity.java`, deleted `PassphraseActivity.java`,
  `AndroidManifest.xml`, `app/config/retained-activities.tsv`
- Tests: `PassphraseChangeFragmentTest.java`, `PassphraseChangeControllerTest.java`,
  `AuthenticationActivityTest.java`, `BootstrapContinuationStoreTest.java`,
  `RetainedActivityContractTest.java`
- Status/graph: `docs/single-activity-security-migration-plan.md`, `docs/plan-index.md`,
  `graphify-out/GRAPH_REPORT.md`, `graphify-out/graph.html`, `graphify-out/graph.json`,
  `graphify-out/manifest.json`

## Upstream Artifacts Consumed

- `.github/modernize/rearchitecture/artifacts/passphrase-pipeline-hardening-frontend.md` - wipeable
  buffers, controller cancellation, guarded activation, compatibility, and rollback contract.
- `.github/modernize/rearchitecture/artifacts/authentication-database-migration-host-frontend.md` -
  exact auth-host Intent shape, surface-qualified ownership, restoration, and fail-closed routing.

## Evidence Mapping

- `passphrase-pipeline-hardening-frontend.md#Security Boundary` -> unchanged
  `PassphraseChangeController`/`MasterSecretUtil` operation plus Fragment Editable-copy, wipe, relock,
  validation, storage-error, disabled-password, and late-callback tests.
- `authentication-database-migration-host-frontend.md#Summary` -> exact CHANGE route-shape tests,
  owner/generation/expiry/process-loss/cross-surface rejection, one expected Fragment, private return,
  and legacy Activity/base absence checks.

## Test Results

- Focused command: `./gradlew.bat :app:testDebugUnitTest` with 21 explicit authentication,
  passphrase, bootstrap, retained, completion, upgrade/migration, and crypto activation test classes.
- Focused: 149 passed; 0 failed; 0 errors; 0 skipped.
- Policy command: `./gradlew.bat :app:checkNoNewAsyncTaskUsage :app:checkAndroidDeprecationAllowlist :app:checkConversationListArchitecture :app:checkConversationThreadArchitecture :app:checkConversationScreenArchitecture :app:checkEventBusAllowlist :app:checkModernArchitectureBoundaries :app:checkUiDataAccessAllowlist :app:checkRetainedActivityInventory :app:checkHostDestinationSecurityPolicy`
- Policy gates: 10 passed; 0 failed.
- Full command: `./gradlew.bat :app:test`
- Full JVM suite: 430 passed; 0 failed; 0 errors; 0 skipped.
- Lint command: `./gradlew.bat :app:lint`
- Lint: passed with 51 current warnings; 45 stale baseline entries were reported and intentionally left
  unchanged. `git diff --exit-code -- app/lint-baseline.xml` passed.
- Release command: `./gradlew.bat :app:verifyCryptoReleaseStages :app:assemblePhaseARelease :app:assembleRelease :app:assembleDebug`
- Release: crypto-stage verification, Phase A, release, and debug assemblies passed.
- Graph command: `graphify update .`
- Graph: passed with 11,750 nodes and 36,977 edges; five pre-existing parser warnings remain for C
  headers and vendored Gradle scripts.
- Connected tests: not run, as required.

## Residual Risks

- Robolectric covers route restoration/process loss, relock broadcast handling, Back/cancel, stale
  callbacks, and successful return. It cannot prove device-specific process kill, task restoration,
  configuration recreation, IME focus, service-binding timing, or protected-broadcast timing.
- No connected-device tests were run. Those lifecycle scenarios remain release validation on a
  disposable emulator or an explicitly approved signature-compatible device.
- Java NFC normalization still necessarily creates one short-lived immutable normalized `String` in
  the unchanged Argon2 derivation boundary; mutable UTF-8 and derived-key buffers remain wiped.
