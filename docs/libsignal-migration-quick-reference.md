# libsignal-client v3 Migration — Quick Reference

Fast-facts index for the crypto library migration. The authoritative, detailed plan lives in
[libsignal-client-v3-upgrade-plan.md](libsignal-client-v3-upgrade-plan.md).

## Build & test

- Android app is the **root** Gradle project (`build.gradle`); vendored libs are subprojects under `libs/`.
- Gradle 9.5.1 runs on JDK 25. App: minSdk 23, compile/targetSdk 36, Java 17, core-library desugaring on.
- Build the app: `./gradlew :assembleDebug --console=plain`
  - Native `.so` size measurement must use a **clean** build — incremental packaging can emit a corrupt
    oversized APK. Use `./gradlew clean :assembleDebug`.
- Migration spike tests (JVM, no emulator): `./gradlew :java:test --tests "org.whispersystems.libsignal.migration.*" --console=plain`

## Pin & protocol

Replace the vendored 2016 `org.whispersystems.libsignal` with the maintained Rust-core
`org.signal:libsignal-client`, **staying on Signal Protocol session v3** (X3DH, no Kyber/PQXDH) for SMS
~140-byte segment fit and interop with un-upgraded peers.

- **Pin = `org.signal:libsignal-android:0.72.1` / `libsignal-client:0.72.1` from Maven Central.**
  0.72.1 is the last release whose Java API can both CREATE and RECEIVE v3 (0.73.0 removed the no-Kyber
  `PreKeyBundle` ctor; 0.74.1 makes Kyber mandatory; 0.75.0 removed Rust X3DH). Do **not** bump it.
- Pin verification: Gradle built-in dependency verification (`gradle/verification-metadata.xml`), not
  gradle-witness (legacy, unused). `libsignal-client-0.72.1.jar` SHA-256:
  `c7391c55072c792f664c51ee297b1c68b91ccdea9c08a02fce8f8d32d14e1a5f`.
- 0.72.1 API is the classic shape: `SessionBuilder(store, remote)`, `SessionCipher(store, remote)`,
  `process(bundle)`/`decrypt(...)` with **no `UsePqRatchet`**; ecc via `Curve.generateKeyPair()`
  (`ECKeyPair.generate()` does not exist at 0.72.1). Build only **no-Kyber (8-arg) `PreKeyBundle`s**.
- Old on-disk v3 records deserialize as-is under the new library (`SessionRecord`, `PreKeyRecord`,
  `SignedPreKeyRecord`, `IdentityKeyPair`, `IdentityKey` — byte pass-through, all proven) — no converter.

## AAR packaging gotchas (already fixed in `build.gradle`)

- Exclude the AAR's testing lib: `packaging.jniLibs.excludes += ['**/libsignal_jni_testing.so']`.
- Exclude desktop natives leaked by the transitive JVM jar:
  `packaging.resources.excludes += ['**/*.dll','**/*.dylib','**/*_amd64.so','**/*_aarch64.so']`.
- No Kotlin Gradle plugin needed (app compiles no Kotlin; `kotlin-stdlib` is a runtime-only transitive).
- Android `libsignal_jni.so` needs stripping: install **NDK r28c (28.2.13676358)** and declare
  `ndkVersion = '28.2.13676358'` in `build.gradle` (done). Strips 60 MB → 5.5 MB/ABI; clean debug APK ≈ 32 MB.
  The NDK is a local SDK component (not in git); only the one-line `ndkVersion` is committed.

## Key Exchange decision — Option A (hybrid)

The maintained library exposes no manual-session-construction API, so Silence's **Key Exchange** flow
(`crypto/SessionBuilder.process(KeyExchangeMessage)`) STAYS on the vendored ratchet; the new
`SessionCipher` ciphers vendored-established sessions (byte-compat proven). Key Exchange is Silence's
only session initiation (`process(PreKeyBundle)` is dead code — no server). Option B (build a new
prekey-bundle exchange protocol, drop Key Exchange) is deferred; see plan §6.6.

## Remaining live work (order)

1. ~~New-API store layer over the existing on-disk storage~~ **DONE** — `Silence*StoreV2` classes
   (`SessionStore`/`PreKeyStore`/`SignedPreKeyStore`/`IdentityKeyStore` + aggregate with no-op
   `KyberPreKeyStore`/`SenderKeyStore`), backing the same files as the vendored stores.
2. ~~Migrate the message-cipher call sites~~ **DONE** — `SmsCipher`/`MmsCipher` use the new
   `SessionCipher` + `SilenceSignalProtocolStoreV2` (exceptions translated back to vendored at the
   boundary); `SessionUtil` on `SilenceSessionStoreV2`; Key Exchange stays vendored (hybrid).
3. ~~Gate Mitigation B~~ **DONE** — lazy in `SilenceSessionStoreV2.loadSession` (unreadable session →
   fresh → re-handshake); no schema flag needed (byte-compatible records load on demand).
4. **Phase 4a + 4b DONE** — guava `Optional` migrated to `java.util.Optional` across 37 app files
   (`crypto/SessionBuilder` stays on guava, Key-Exchange-coupled); and the `V2` naming retired: the new-API
   stores are now the canonical `Silence*Store` and the vendored stores are `Vendored*Store`
   (`VendoredSessionStore`/`VendoredPreKeyStore`/`VendoredIdentityKeyStore`/`VendoredSignalProtocolStore`).
   Builds clean. **Remaining Phase 4 (optional):** slim the vendored subproject to the Key-Exchange core.
   Full deletion of the vendored lib is Option B (deferred); see plan §6.6.
5. Validate on-device: interop vs an old build, upgrade-in-place over a populated DB, stored messages stay readable.
