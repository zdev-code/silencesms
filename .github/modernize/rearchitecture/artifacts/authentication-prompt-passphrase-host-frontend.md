# Authentication PROMPT_PASSPHRASE Host Slice

## Summary

`AuthenticationActivity` now accepts exactly `WELCOME`, `CREATE_PASSPHRASE`, or
`PROMPT_PASSPHRASE` plus the sanitized destination and opaque continuation token. Each token is bound
in memory to its exact authentication surface, so changing only the surface extra cannot replay a
continuation created for another surface. The private host remains non-exported and has no application
Navigation graph or protected destination state.

`PassphrasePromptFragment` preserves the centered title, dynamic intro theme and language, IME/button
submission, delayed progress, automatic password-disabled unlock, invalid-passphrase clearing/error,
and locked log-submission menu. The Activity delegates only prompt menu preparation and dispatch.

`PassphrasePromptController` copies a cleared `Editable` into controller-owned mutable buffers and
zeros caller and worker buffers on success, invalid passphrase, exception, replacement, cancellation,
close, and stale callback paths. `MasterSecretUtil` exposes its existing `char[]` KDF/wrapper path
without changing parameters or formats. Successful secrets move immediately into the existing
lifecycle-checked `AuthenticationCompletionCoordinator`; only the host consumes the one-shot
continuation after cache establishment.

`PassphrasePromptActivity` is removed from source, manifest, routing, and retained inventory.
`PassphraseActivity` remains because the out-of-scope `PassphraseChangeActivity` still subclasses it.
Database upgrade and database migration remain separate Activities.

## Changed Files

- Authentication host/routing: `AuthenticationActivity.java`, `BootstrapContinuationStore.java`,
  `PassphraseRequiredActionBarActivity.java`
- Prompt implementation: `PassphrasePromptFragment.java`,
  `ui/passphraseprompt/PassphrasePromptController.java`
- Mutable KDF entry: `crypto/MasterSecretUtil.java`
- Legacy cleanup: `PassphrasePromptActivity.java` removed; `PassphraseActivity.java` stripped of its
  dead prompt-only continuation branch; manifest and retained inventory updated
- Tests: prompt controller, Fragment, auth host, continuation store, and retained Activity contracts
- Status: `docs/single-activity-security-migration-plan.md`, `docs/plan-index.md`

## Upstream Artifacts Consumed

- `.github/modernize/rearchitecture/artifacts/authentication-bootstrap-continuation-frontend.md` -
  owner-bound, expiring, consume-once continuation and fail-closed inbox contract.
- `.github/modernize/rearchitecture/artifacts/authentication-welcome-host-frontend.md` - exact private
  authentication host and restoration boundary.
- `.github/modernize/rearchitecture/artifacts/authentication-create-passphrase-host-frontend.md` -
  lifecycle-checked cache establishment and pending-prime ownership.
- `.github/modernize/rearchitecture/artifacts/passphrase-pipeline-hardening-frontend.md` - mutable
  passphrase ownership, cancellation, and stale-callback conventions.

## Evidence Mapping

- `authentication-bootstrap-continuation-frontend.md#Summary` -> surface-qualified token owner plus
  exact-shape, mismatch, one-shot, cold, expiry, and fallback tests.
- `authentication-welcome-host-frontend.md#Summary` -> one restored Fragment matching the validated
  surface and no protected graph/state in the authentication host.
- `authentication-create-passphrase-host-frontend.md#Summary` -> direct handoff to
  `AuthenticationCompletionCoordinator` with destruction and stale-generation suppression.
- `passphrase-pipeline-hardening-frontend.md#Security Boundary` -> direct `Editable` copy, immediate UI
  clear, mutable KDF overload, and caller/worker zeroing tests.

## Test Results

- Focused command: `./gradlew.bat :app:testDebugUnitTest --tests org.smssecure.smssecure.ui.passphraseprompt.PassphrasePromptControllerTest --tests org.smssecure.smssecure.PassphrasePromptFragmentTest --tests org.smssecure.smssecure.AuthenticationActivityTest --tests org.smssecure.smssecure.BootstrapContinuationStoreTest --tests org.smssecure.smssecure.domain.security.AuthenticationBootstrapControllerTest --tests org.smssecure.smssecure.ui.authentication.AuthenticationCompletionCoordinatorTest --tests org.smssecure.smssecure.RetainedActivityContractTest`
- Focused: 52 passed; 0 failed; 0 errors; 0 skipped.
- Policy command: `./gradlew.bat :app:checkNoNewAsyncTaskUsage :app:checkAndroidDeprecationAllowlist :app:checkConversationListArchitecture :app:checkConversationThreadArchitecture :app:checkConversationScreenArchitecture :app:checkEventBusAllowlist :app:checkModernArchitectureBoundaries :app:checkUiDataAccessAllowlist :app:checkRetainedActivityInventory :app:checkHostDestinationSecurityPolicy`
- Policy gates: 10 passed; 0 failed.
- Full command: `./gradlew.bat :app:test`
- Full JVM suite: 385 passed; 0 failed; 0 errors; 0 skipped.
- Lint command: `./gradlew.bat :app:lint`
- Lint: passed with 48 current warnings and no unbaselined errors; 41 stale baseline entries reported;
  `app/lint-baseline.xml` unchanged.
- Release command: `./gradlew.bat :app:verifyCryptoReleaseStages :app:assemblePhaseARelease :app:assembleRelease :app:assembleDebug`
- Release: crypto stages, Phase A, release, and debug assemblies passed.
- Connected tests: not run, as required.

## Residual Risks

- Java NFC normalization in the existing Argon2 path still necessarily creates one method-local
  immutable normalized `String`; surrounding character and byte buffers are wiped. The legacy
  `UNENCRYPTED_PASSPHRASE` constant also remains immutable for compatibility, but each automatic unlock
  now scopes it to a temporary wipeable copy.
- Robolectric verifies lifecycle, restoration, menu, progress, and routing behavior; no connected-device
  service-binding or process-kill timing validation was run in this batch.