# Passphrase Pipeline Hardening

## Summary

Passphrase replacement now copies `Editable` values directly into closeable character buffers,
validates without immutable strings, delegates task ownership to a generation-aware controller, and
checks the captured unlock generation before derivation/staging and immediately before atomic wrapper
activation. Cancellation, replacement, relock, teardown, success, and error wipe caller and operation
buffers and suppress stale callbacks. Existing wrapper formats, salts, KDF parameters, release-stage
write policy, legacy readers, device wrapping, and atomic activation remain unchanged.

`PassphraseChangeActivity` remains a private retained security flow. No `MasterSecret` or passphrase is
added to intents, navigation, saved state, or continuation storage.

## Security Boundary

- `WipeablePassphrase` owns character arrays and zeros them on close.
- `PassphraseChangeController` validates before dispatch, replaces/cancels one active task, owns worker
  buffers, and rejects late callbacks after cancellation or unlock-generation change.
- `MasterSecretMigration.ActivationGuard` runs before candidate derivation/staging and after candidate
  verification immediately before activation.
- Legacy PBKDF uses `PBEKeySpec(char[])`, calls `clearPassword()`, and wipes encoded key bytes.
- Argon2 accepts `char[]` and wipes UTF-8 bytes and derived keys. Java's NFC `Normalizer` necessarily
  returns one short-lived immutable normalized `String`; it is confined to `Argon2id.derive(char[])`
  and is not falsely described as wipeable.

## Compatibility Evidence

- Character input produces and reads the existing Argon2 envelope magic and stored KDF parameters.
- Failed staging, failed final commit, and a guard rejection preserve the prior active wrapper.
- Existing legacy/current master-cipher and device-key envelope round trips pass unchanged.
- Storage failure remains surfaced as `MasterSecretStorageException`.

## Upstream Artifacts Consumed

- none — no dependency artifacts provided

## Evidence Mapping

- none — no dependency artifacts provided

## Test Results

- Command: focused wipeable-buffer, controller, migration, `MasterSecretUtil`, and Argon2 JVM tests
  via `:app:testDebugUnitTest --tests ...`
- Passed: all focused suites
- Failed: 0
- Skipped: 0
- Command: crypto compatibility suites, `:app:compileDebugAndroidTestJavaWithJavac`, and all eleven
  architecture/allowlist/inventory guards
- Passed: all tasks
- Failed: 0
- Skipped: 0
- Command: `./gradlew.bat :app:test`
- Passed: 328
- Failed: 0
- Skipped: 0
- Command: `./gradlew.bat :app:verifyCryptoReleaseStages :app:assemblePhaseARelease :app:assembleRelease :app:assembleDebug`
- Passed: crypto policy plus Phase A, release, and debug builds
- Failed: 0
- Skipped: 0
- Command: `./gradlew.bat :app:lint`
- Passed: build with 0 errors outside the baseline; 36 warning-level findings, none in changed files
- Failed: 0
- Skipped: 0
- Command: `graphify update .`
- Passed: code graph updated to 11,102 nodes and 35,048 edges
- Connected tests: not run, as required