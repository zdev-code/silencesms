# libsignal-client (Rust) — Session v3 Upgrade Plan

> Status: **Phase 0 (JVM cross-validation) + Phase 1 store-adapter design COMPLETE** on branch
> `libsignal-client-spike`; live app integration (Android AAR + cipher/store call-site rewrite) is still
> pending and targeted for a major version. See §4 for per-phase status and §6 for empirical findings.
> Goal: replace the vendored, EOL `libsignal-protocol-java` (2.7.1/2.8.1-era, frozen since 2019)
> with Signal's maintained `libsignal-client` (Rust core + JNI bindings), pinned at **0.72.1**, staying
> on **Signal Protocol session version 3** and **without enabling PQXDH**.

## 1. Objective & Non-Goals

### Objective

- Adopt a **maintained, audited, fuzzed** crypto core for the SMS/MMS transport encryption.
- Keep the **on-the-wire format at session v3** so existing Silence/SMSSecure peers stay interoperable.
- Gain ongoing CVE fixes and constant-time / side-channel hardening from the Rust implementation.

### Non-Goals (explicitly out of scope)

- **PQXDH / Kyber post-quantum** handshake — impractical over SMS (≈1.5 KB payloads vs ≈140-byte
  segments) and would bump the protocol version, breaking peer interop.
- Sealed sender, ACI/PNI identity split, registration lock — server/data-channel concepts with no
  meaning over peer-to-peer SMS.
- Local at-rest crypto (`MasterCipher` / `MasterSecretUtil`) — handled separately in the current
  version; unaffected by this library swap.

## 2. Why this is a major-version change

| Factor | Current (libsignal-protocol-java) | Target (libsignal-client) |
| --- | --- | --- |
| Language | Pure Java (vendored source) | Rust core + Java/Kotlin JNI bindings (Kotlin stdlib may be a transitive dep of the Android AAR — unconfirmed at 0.72.1; the JVM jar needs none) |
| Packaging | `.java` compiled into APK | Prebuilt AAR (`org.signal:libsignal-android`) bundling native `.so` per ABI |
| Namespace | `org.whispersystems.libsignal.*` | `org.signal.libsignal.protocol.*` (every crypto-layer import changes) |
| API surface | `SessionCipher`, `SessionBuilder`, `*Store` interfaces | Same shape + **mandatory `KyberPreKeyStore` + `SenderKeyStore`** wired in, even for v3 (Silence ships no-op adapters) |
| Session record format | `StorageProtos.SessionStructure` (protobuf) | Same protobuf lineage; old v3 records load **as-is** via `new SessionRecord(byte[])` (byte pass-through **confirmed**, §6.2) |
| Maintenance | Frozen since 2019 | Actively maintained (rapid 0.x cadence) |

The native packaging and reshaped store/record APIs make this a substantial change, not a drop-in
dependency bump — hence deferral to a major release.

## 3. Compatibility impact for current users

**Short answer: on-the-wire interop is preserved; stored session state is the migration risk.**

### 3.1 What does NOT break

- **Message wire format** — v3 `SignalMessage` / `PreKeySignalMessage` byte format is unchanged, so
  messages exchanged with existing peers (running old Silence/SMSSecure) still encrypt/decrypt.
- **Stored message bodies** — at rest, message text is encrypted by `MasterCipher` (the local layer),
  **not** by the session ratchet. Therefore losing/recreating a session does **not** lose stored
  messages. This is the key safety property.
- **Identity keys & safety numbers** — the long-term identity keypair is stored independently and can
  be re-loaded; safety numbers remain stable if the same identity key is reused.

### 3.2 What is at risk and needs a migration shim

- **Existing session records.** On-device sessions are serialized with the old library's
  `SessionStructure` protobuf. This was the single scariest unknown; it is now **resolved**.
  - **Mitigation A (CONFIRMED — reduces to a byte pass-through):** existing serialized records load
    directly under the new library via `new SessionRecord(oldBytes)` and still report v3 — proven by
    `OldToNewSessionRecordTest` (§6.2). **No field-for-field converter is needed**, and the existing
    `SilenceSessionStore` v2 read path already deserializes exactly this way.
  - **Mitigation B (fallback):** if a session can't be migrated, **drop it**. The next inbound/outbound
    message triggers a fresh key exchange (a new PreKey handshake). Cost: a one-time re-key and a new
    safety number for that contact — **not** message loss.
- **Prekey / signed-prekey stores.** Re-load from existing storage into the new store interfaces;
  format is simple (key id + keypair), low risk. Store-adapter design validated in the spike harness
  (§4 Phase 1).

### 3.3 Net user-visible effect

- **Expected outcome** (Mitigation A — byte pass-through **confirmed**, §6.2): **invisible** — no re-key,
  existing sessions continue uninterrupted.
- Worst case (Mitigation B, only if a specific record ever fails to load): that conversation performs a
  **silent re-handshake** on next message and the contact's **safety number changes once**. No stored
  messages are lost.

## 4. Implementation phases

### Phase 0 — Spike / feasibility

- **JVM cross-validation DONE** (branch `libsignal-client-spike`): with `org.signal:libsignal-client:0.72.1`
  on the test classpath, a no-Kyber handshake negotiates **session v3** and round-trips encrypt/decrypt
  (`NewLibraryV3RoundTripTest`), and an old vendored-library v3 `SessionRecord` deserializes natively as
  v3 (`OldToNewSessionRecordTest`). See §6.1/§6.2.
- **Android AAR integrated (2026-07):** `implementation 'org.signal:libsignal-android:0.72.1'` added to
  the app; `./gradlew :assembleDebug` **BUILD SUCCESSFUL**, coexisting with the vendored lib. **No Kotlin
  Gradle plugin needed.** Two packaging fixes were required (see §6.3): exclude the AAR's bundled
  `libsignal_jni_testing.so`, and exclude the transitive JVM jar's desktop natives
  (`.dll`/`.dylib`/`*_amd64.so`/`*_aarch64.so`). ABI coverage matches `armeabi-v7a`/`arm64-v8a`. The
  Android `.so` is **stripped** once an NDK is installed + `ndkVersion` declared (done: r28c → APK 32 MB) — see §6.3.

### Phase 1 — Store adapters

- Implement the new library's store interfaces (identity / prekey / signed-prekey / session) backed by
  the **existing** SQLCipher-free databases and `MasterCipher`-encrypted columns.
- Keep storage on disk byte-compatible where possible.
- **Design validated in the spike harness** (branch `libsignal-client-spike`): a Silence-shaped
  `SignalProtocolStore` against the 0.72.1 API — sessions persisted as `record.serialize()` bytes and
  rebuilt via `new SessionRecord(bytes)` on every load (mirroring the `MasterCipher` disk round-trip),
  plus a **production no-op `KyberPreKeyStore`** and a **no-op `SenderKeyStore`** (no group messaging
  over SMS) — drives a 21-message bidirectional conversation with the session version pinned at **3**
  throughout (`SilenceStyleSignalProtocolStore` + `SilenceStyleStoreV3Test`). Proves the v3 Double
  Ratchet survives repeated serialize/deserialize cycles through the new container.
- **New-API shape deltas to carry into the live rewrite:** `IdentityKeyStore.saveIdentity` now returns
  `IdentityChange` (was `boolean`); `isTrustedIdentity` takes a `Direction`; `SessionStore` adds
  `loadExistingSessions`; the store surface also includes `KyberPreKeyStore` + `SenderKeyStore`.
- **All bridged record types proven byte-compatible old→new** (`OldToNewKeyRecordsTest`, in addition to
  `OldToNewSessionRecordTest`): `PreKeyRecord`, `SignedPreKeyRecord`, `IdentityKeyPair`, and `IdentityKey`
  (both directions) all deserialize across libraries. So the live stores **bridge**: read the existing
  `MasterCipher`-decrypted bytes and reconstruct the new-lib object; for the identity DB, convert new-lib
  `IdentityKey` → vendored via `new IdentityKey(newKey.serialize(), 0)`. No regeneration needed.
- **Contained live-rewrite strategy** (keeps the vendored lib on the classpath, blast radius ~12 files):
  migrate only the **session-protocol path** — `crypto/storage/*`, `SmsCipher`, `MmsCipher`,
  `SessionUtil`, `crypto/SessionBuilder` — to the new API, and **re-wrap** new-lib protocol exceptions
  back into the vendored exception types at the `SmsCipher`/`MmsCipher` boundary so the job/UI/database
  layers (which catch vendored `InvalidMessageException` etc., mostly for the unrelated local
  `MasterCipher`) stay untouched. The vendored `logging.Log`, ecc used by local at-rest crypto, and
  `guava.Optional` remain until Phase 4.
- **IMPLEMENTED (2026-07-10) — new-API store layer added, compiles clean.** Four classes under
  `src/org/smssecure/smssecure/crypto/storage/`, backing the **same on-disk storage** as the vendored
  stores (so the maintained `SessionCipher` and the vendored Key-Exchange path share data):
  `SilenceSessionStoreV2` (new `SessionStore`), `SilencePreKeyStoreV2` (new `PreKeyStore` +
  `SignedPreKeyStore`), `SilenceIdentityKeyStoreV2` (new `IdentityKeyStore`, bridges via
  `IdentityKeyUtil` + `IdentityDatabase`), and `SilenceSignalProtocolStoreV2` (aggregate + no-op
  `KyberPreKeyStore` + no-op `SenderKeyStore`). The vendored `Silence*Store` classes are kept for the
  Key-Exchange path (hybrid). Legacy single-state session files (marker 1) that the new library cannot
  parse fall through to Mitigation B (fresh session → re-handshake). Wiring these into `SmsCipher`/etc.
  is Phase 2; `./gradlew :compileDebugJavaWithJavac` is green.

### Phase 2 — Cipher call-site migration (Option A / hybrid — see §6.6)

- **Two session-establishment paths, handled differently under the chosen hybrid:**
  - **PreKey bundles** (`IncomingPreKeyBundleMessage`) and all **message encrypt/decrypt** move to the
    new lib (`SessionCipher`, `SessionBuilder.process(PreKeyBundle)`). Portable.
  - **Manual Key Exchange over SMS** (`KeyExchangeMessage` via `src/org/smssecure/smssecure/crypto/SessionBuilder.java`)
    **stays on the vendored ratchet** — the maintained library exposes no manual-session-construction
    API. New-lib `SessionCipher` then ciphers the vendored-established session (proven byte-compat).
- Migrate the message-cipher call sites to the new API in:
  - `src/org/smssecure/smssecure/crypto/SmsCipher.java` (decrypt/encrypt/prekey paths; leave `process(KeyExchangeMessage)` delegating to the vendored `crypto/SessionBuilder`)
  - `src/org/smssecure/smssecure/crypto/MmsCipher.java`
  - `src/org/smssecure/smssecure/crypto/SessionUtil.java`
  - `src/org/smssecure/smssecure/crypto/storage/*`
- Build only no-Kyber (8-arg) `PreKeyBundle`s; the 0.72.1 ciphers take no `UsePqRatchet`.
- Re-wrap new-lib protocol exceptions into the vendored exception types at the `SmsCipher`/`MmsCipher`
  boundary so the job/UI/database layers stay untouched (they mostly catch vendored
  `InvalidMessageException` for the unrelated local `MasterCipher`).
- **Validate first:** add a harness test proving a **Key-Exchange-established** `SessionState` (built by
  the vendored `crypto/SessionBuilder`) ciphers correctly under the new `SessionCipher` — the one new
  hand-off the hybrid introduces.
- **IMPLEMENTED (2026-07-10), builds clean (`:assembleDebug` green).** The hand-off is proven
  (`OldToNewSessionRecordTest#vendoredEstablishedSessionCiphersUnderNewLibrary`: a vendored-established
  session ciphers 10 messages under the new `SessionCipher` at v3). `SmsCipher` and `MmsCipher` now use
  the new `SessionCipher` + `SilenceSignalProtocolStoreV2` for all message encrypt/decrypt and prekey
  paths, translating new-lib protocol exceptions back to the vendored types at the boundary (incl.
  `UntrustedIdentityException` via `getName()` + key conversion) so the job/UI/database callers are
  untouched. `SmsCipher.process(KeyExchangeMessage)` still runs on the vendored `crypto/SessionBuilder`
  - a retained vendored store. Construction sites updated (`SmsDecryptJob` ×3, `SmsSendJob`,
  `MmsDownloadJob`, `MmsSendJob`) to `new SmsCipher/MmsCipher(context, masterSecret, subscriptionId)`;
  `SessionUtil` switched to `SilenceSessionStoreV2`.

### Phase 3 — Session record migration

- **No converter needed** — old v3 records load directly under the new library (byte pass-through
  **confirmed**, §6.2 / `OldToNewSessionRecordTest`); the existing `SilenceSessionStore` v2 read path
  (`new SessionRecord(bytes)`) already does exactly this.
- Keep **Mitigation B** (drop an unreadable session → re-handshake on next message, no message loss) as
  the guaranteed fallback (wired lazily in the store — see IMPLEMENTED note below; no schema flag needed).
- Belt-and-braces: re-run the deserialize assertion against a record pulled from a populated on-device
  database (the harness proof used an in-process record, which is byte-identical to the on-disk form).
- **IMPLEMENTED (2026-07-10).** Mitigation B is wired **lazily** in `SilenceSessionStoreV2.loadSession`:
  a session file that is absent returns a fresh record (normal “no session”); a file that exists but the
  maintained library cannot parse (legacy marker-1 raw `SessionStructure`, or any deserialize failure)
  is logged as “Mitigation B” and also returns a fresh record → the next message triggers a re-handshake,
  no stored message lost. **No schema/version flag or one-time migration pass is needed** because
  byte-compatible marker-2 records load on demand; only genuinely unreadable records fall through. The
  stale file is left in place (the vendored Key-Exchange store can still read marker-1) and is overwritten
  when the session re-establishes.

### Phase 4 — Trim the vendored library (NOT full removal — Option A)

Under the chosen hybrid (§6.6 Option A), the vendored `org.whispersystems.libsignal` **cannot be fully
deleted**: the Key-Exchange establishment path (`crypto/SessionBuilder.process(KeyExchangeMessage)`) is
built on vendored internals the maintained library does not expose (`ratchet.*`, `state.SessionState`,
`kdf.*`, `ecc.*`, and the vendored exceptions/`IdentityKey`). So Phase 4 is a **reduction**, not a removal:

- **Decouple the general-utility dependency first — IMPLEMENTED (2026-07-10), builds clean.**
  `org.whispersystems.libsignal.util.guava.Optional` was migrated to `java.util.Optional` across the
  **37 app files** that used it as a general utility (`absent()`→`empty()`, `fromNullable`→`ofNullable`,
  `.or(v)`→`.orElse(v)`, `.orNull()`→`.orElse(null)`; no `.transform`/`.asSet`/`.or(Optional)` were
  present). `crypto/SessionBuilder` deliberately **stays** on guava `Optional` (it is Key-Exchange /
  vendored-ratchet-coupled). `:assembleDebug` is green.
- **Keep only the Key-Exchange establishment core** of the vendored lib on the classpath: the classes
  `crypto/SessionBuilder` transitively needs (`SessionBuilder`/`SessionCipher` internals it calls,
  `ratchet.*`, `state.SessionState`/`SessionRecord`, `kdf.*`, `ecc.*`, `IdentityKey(Pair)`, the vendored
  exceptions, `SignalProtocolAddress`, `protocol.*` message/KeyExchange types, `util.KeyHelper`/`Medium`).
  Optionally slim the vendored subproject to just these packages to reduce APK/method count.
- **Retire the temporary `V2` naming (a rename *swap*, since both stores coexist) — IMPLEMENTED
  (2026-07-10), builds clean.** The `*V2` classes took the canonical names and the vendored stores were
  renamed to a `Vendored*` prefix (chosen over `KeyExchange*` because, per the finding below, they also
  serve a few non-Key-Exchange session queries — `Vendored` is the accurate umbrella):
  - `SilenceSessionStoreV2` → `SilenceSessionStore`, `SilencePreKeyStoreV2` → `SilencePreKeyStore`,
    `SilenceIdentityKeyStoreV2` → `SilenceIdentityKeyStore`, `SilenceSignalProtocolStoreV2` → `SilenceSignalProtocolStore` (canonical, new-API).
  - Vendored `Silence*Store` → `Vendored*Store` (`VendoredSessionStore`, `VendoredPreKeyStore`,
    `VendoredIdentityKeyStore`, `VendoredSignalProtocolStore`) — used by `crypto/SessionBuilder`
    (Key Exchange), `SmsCipher.vendoredStore`, `KeyExchangeInitiator`, `ReceiveKeyDialog`, and the residual
    session queries in `SmsSentJob` / `DualSimUtil` / `VerifyIdentityActivity`.
  - The canonical `SilenceSessionStore` still calls `VendoredSessionStore.getSessionDirectory(...)` (the
    single source of the `sessions-v2` path) — a harmless cross-reference; can be consolidated later.
  - Done as a manual find/replace (the Java language-server rename provider returned no edits in this
    environment) + file moves; verified by `:assembleDebug`.
- Do **not** delete `libs/org.whispersystems.libsignal/` or its `settings.gradle`/`build.gradle` module
  reference while the hybrid stands.
- **Full removal of the vendored lib is Option B** (build a new prekey-bundle exchange protocol to
  replace Key Exchange) — deferred to a future major version; see §6.6. Only then do
  `settings.gradle`/`build.gradle` lose the `:java` module.

### Phase 5 — Validation (on-device / QA — requires a human + device or emulator)

Everything above is verified only by compile + JVM harness tests. For security-critical SMS crypto this
is **not** sufficient to ship: the JNI native path, the real on-device MasterCipher-backed stores, and
true wire interop are only exercisable on a device. This phase must be run by hand before release.

#### Environment

- Device/emulator A: this build (Rust-core hybrid).
- Device/emulator B: **an unmodified old Silence build** (vendored libsignal), for interop.
- Both need working SMS (two SIMs, two emulators with the SMS gateway, or a carrier test pair).
- Optional device A': a *pre-migration* build for the upgrade-in-place test (§ below).

#### 5.1 Fresh-session happy path (A ↔ A, new install)

- [ ] Start secure session (Key Exchange) from A→A2; confirm both sides show the session established and
      the same **safety number**.
- [ ] Send/receive several encrypted SMS both directions; confirm decrypt + correct order.
- [ ] Confirm ciphertext fits SMS segments (no unexpected multi-part explosion → guards the v3 size goal).

#### 5.2 Key Exchange establishment (the hybrid seam — highest-value test)

- [ ] Establish a brand-new session via Key Exchange, then send messages — proves the **vendored-established
      session is correctly ratcheted by the new `SessionCipher`** (the one genuinely new behavior in Option A).
- [ ] Reject/accept an untrusted-identity Key Exchange; confirm the identity-change prompt appears.

#### 5.3 PreKey / first-message-after-establish path

- [ ] Receive a `PreKeySignalMessage` (first inbound after a session exists) and confirm it decrypts.

#### 5.4 Interop with an OLD build (A ↔ B) — proves no wire-format break

- [ ] B(old) → A(new): establish + exchange messages both directions.
- [ ] A(new) → B(old): establish + exchange messages both directions.
- [ ] Verify safety numbers match across old/new.

#### 5.5 Upgrade-in-place over a populated database (the migration risk)

- [ ] On device A', run the *old* build and build up real conversations (several established sessions +
      message history).
- [ ] Install this build over it (no wipe). Confirm:
  - [ ] Existing conversations still open and **prior messages remain readable**.
  - [ ] Sending into an existing session still works (existing `SessionRecord` ciphers under the new lib).
  - [ ] Any session that can't be read triggers **Mitigation B** (logged, fresh session, clean re-handshake)
        rather than a crash or silent data loss.

#### 5.6 Lifecycle & identity flows

- [ ] End session / `TERMINATE`; confirm the session is torn down and a new Key Exchange re-establishes.
- [ ] Identity change (reinstall peer): confirm the verify/identity-mismatch UX fires correctly.
- [ ] Verify-identity: safety-number compare **and** QR scan.

#### 5.7 MMS encrypted path

- [ ] Send/receive an encrypted MMS (attachment) over an established session; confirm decrypt.

#### 5.8 Release build on device

- [ ] Install the **release (R8/minified)** APK and repeat 5.1, 5.2, 5.5 — confirms the shipped ProGuard
      config keeps the JNI-reachable types at runtime (build-time R8 already passes).

**Exit criteria:** all boxes checked on release build across at least one old↔new interop pair and one
upgrade-in-place device. Capture logs for any Mitigation-B fallback to confirm it fires by design.

## 5. Rollback

- Keep the change behind a feature branch / staged rollout.
- Because the wire format is unchanged, a build reverting to the vendored library can still talk to
  upgraded peers; only locally re-keyed sessions differ.

## 6. Resolved open questions (researched 2026-06; empirically validated 2026-07, branch `libsignal-client-spike`)

> Source: `signalapp/libsignal` repository and release history, plus the Phase 0 spike on branch
> `libsignal-client-spike`. Items marked **EMPIRICALLY RESOLVED** were proven by running tests
> against the pinned artifact; re-confirm the Android-AAR-only items when the `.so` is added.

### 6.1 Which `libsignal-client` version exposes the classic (non-PQ) session v3 API?

**Answer (EMPIRICALLY RESOLVED): pin `org.signal:libsignal-client:0.72.1` /
`org.signal:libsignal-android:0.72.1`, from Maven Central. It is the last release whose Java API can
both CREATE and RECEIVE a classic X3DH (session v3) handshake.**

- The earlier assumption — "use the latest version and just omit the Kyber prekey" — is **WRONG** and
  was disproven by the spike. On **0.96.2** the no-Kyber `PreKeyBundle` constructor no longer exists and
  a Kyber-bearing handshake negotiates **session v4 (PQXDH)** (the spike printed `session version = 4`).
  Modern releases force PQXDH; a v4 session breaks both the ~140-byte SMS segment fit and interop with
  un-upgraded peers (§1 Non-Goals).
- **v3-removal timeline** (read from upstream source at each git tag):

  | Version | v3 status |
  | --- | --- |
  | **0.72.1** ✅ chosen pin | no-Kyber 8-arg `PreKeyBundle` ctor present; `SessionBuilder(store, remote)` / `process(bundle)` with **no `UsePqRatchet` switch**; Rust core speaks X3DH |
  | 0.73.0 | **removes** the no-Kyber `PreKeyBundle` ctor → can no longer CREATE a v3 bundle from stock Java |
  | 0.75.0 | **removes** the Rust X3DH protocol entirely → can no longer even RECEIVE v3 |

- **Spike result (this branch):** with 0.72.1 on the test classpath, a no-Kyber bundle negotiates
  **session version 3** in both directions and round-trips encrypt/decrypt (`NewLibraryV3RoundTripTest`,
  prints `session version = 3`). The JVM `libsignal-client:0.72.1` jar bundles desktop natives (incl.
  Windows x86_64), so the test runs on the dev host with no emulator.
- **Source = Maven Central, NOT `build-artifacts.signal.org`.** Central is immutable/append-only and
  GPG-signed; Signal's own repo can prune old releases. Everything ≤ 0.86.5 is on Central, so we never
  need Signal's repo for this pin. (The spike `build.gradle` was updated to drop the `SignalBuildArtifacts`
  repo and pin `0.72.1`.)
- **Tradeoff (accepted consciously):** pinning 0.72.1 forgoes future upstream security patches on that
  line. Mitigated by: (a) 0.72.1 is a 2025-era audited/fuzzed Rust core — vastly safer than the 2016
  vendored Java library it replaces; (b) Silence uses only the offline protocol primitives, so the
  high-churn Net/PQ surface that drives most upstream releases (§6.5) does not affect us; (c) the exact
  artifact is recorded by SHA-256 so any substitution is detectable. **Recorded hash** of the JVM test
  artifact `libsignal-client-0.72.1.jar` (Maven Central):
  `c7391c55072c792f664c51ee297b1c68b91ccdea9c08a02fce8f8d32d14e1a5f`. **Pin mechanism:** Gradle's
  built-in **dependency verification** (`gradle/verification-metadata.xml`, SHA-256/512 + optional PGP)
  — the modern replacement for the legacy gradle-witness plugin (which is *not* applied to this Gradle 9
  build). The shipping `libsignal-android` AAR hash is recorded the same way when that artifact is added.
- **Three API realities to budget for (these enlarge Phase 2):**
  1. **Namespace rename** `org.whispersystems.libsignal.*` → `org.signal.libsignal.protocol.*`.
     Every import in `SmsCipher`, `MmsCipher`, `SessionUtil`, `crypto/storage/*`, and the
     `Curve`/`ECPrivateKey`/`ECPublicKey` references in `MasterCipher`/`MasterSecretUtil` changes.
     (At 0.72.1 the ecc helpers are still `Curve.generateKeyPair()` /
     `ECPrivateKey.calculateSignature(byte[])`, close to the vendored API — `ECKeyPair.generate()` does
     NOT exist at this version.)
  2. **`KyberPreKeyStore` is part of the store surface.** At 0.72.1 the `SessionCipher` /
     `SessionBuilder` convenience constructors that take a single `SignalProtocolStore` require it to
     implement `KyberPreKeyStore`. Implement a **no-op `KyberPreKeyStore`**: the v3 path never stores or
     loads a Kyber prekey, so it is never invoked.
  3. **Constructors are clean at 0.72.1.** `SessionBuilder(store, remoteAddress)` and
     `SessionCipher(store, remoteAddress)` take only the remote address, and `process(bundle)` /
     `decrypt(...)` take **no `UsePqRatchet` argument** — closely mirroring the vendored call sites, so
     Phase 2 is mostly an import + store-wiring change.
  4. **Kotlin stdlib may be a transitive dependency of the AAR.** The JVM `libsignal-client` jar used
     in the spike needed no Kotlin plugin; confirm whether `libsignal-android:0.72.1` pulls
     `kotlin-stdlib` / `kotlinx-coroutines` when the AAR is added.
- **minSdk / toolchain — RESOLVED.** The current `libsignal-android` `build.gradle`
  (`java/android/build.gradle` on `main`) declares **`minSdkVersion 23`**, `compileSdk 34`,
  **`ndkVersion 28.x`**, Java **17** source/target, and **`coreLibraryDesugaringEnabled = true`**.
  The app has been moved to match (**minSdk 23, Java 17, core library desugaring enabled**) — see §7.
  Note the AAR ships **unstripped** `.so` (`doNotStrip '**/*.so'`) and defers stripping to the app.

### 6.2 Can the new version ingest old `SessionStructure` protobufs directly?

**Answer (EMPIRICALLY CONFIRMED at 0.72.1): yes — Mitigation A reduces to a byte pass-through; no
field-for-field converter is needed.**

- Spike `OldToNewSessionRecordTest`: a session record serialized by the **current vendored library**
  (`org.whispersystems.libsignal`) deserializes natively under
  `org.signal.libsignal.protocol.state.SessionRecord(byte[])` at 0.72.1, reports **version 3**, and
  survives a re-serialize round-trip through the new container. **PASSED.**
- Why: the record container is the **same protobuf lineage** (`RecordStructure` wrapping
  `SessionStructure`) carried forward into the Rust `rust/protocol` crate, and protobuf is
  **field-number based**, so the `org.whispersystems` → `org.signal` package rename does not affect
  deserialization. Existing bytes load directly via `SessionStore.storeSession(... new SessionRecord(oldBytes))`.
- **The field-for-field converter (Phase 3 / Mitigation A) is therefore unnecessary.** Keep
  **Mitigation B (drop session → re-handshake, no message loss)** as the guaranteed fallback.
- **Residual nuance:** the spike record was produced by the vendored library **in-process** (byte-identical
  to the on-device serialization path). Re-running the same assertion against a record pulled from a
  populated on-device database in Phase 3 is a belt-and-braces confirmation, not a blocker.

### 6.3 APK size / NDK build-time budget for native `.so` files

**Answer: build-time cost is ~zero (prebuilt AAR, no Rust toolchain in our build); APK size grows by
roughly +10–16 MB for two ARM ABIs, mitigable to ~+5–8 MB per device via App Bundle.**

- `libsignal-android` is a **prebuilt AAR** containing stripped native `.so` per ABI (arm64-v8a,
  armeabi-v7a, x86, x86_64). **We do not compile Rust** — there is no NDK/Rust build step on our side,
  so "build budget" is essentially nil beyond a larger dependency download.
- **Size:** the native core is large (order of several MB of stripped `.so` per ABI). With our
  existing `abiFilters 'armeabi-v7a','arm64-v8a'` (x86 excluded), expect roughly **+10–16 MB** to a
  universal APK. The 20–30 MB `*-debuginfo.sym` files in releases are **debug symbols and are NOT
  shipped**.
- **Mitigations:** ship an **Android App Bundle** so each device downloads only its ABI (~+5–8 MB per
  device); keep `abiFilters` to the two ARM ABIs; confirm symbols are stripped.
- **Caveat:** the AAR is a single artifact bundling the **whole** libsignal client (CDSI, SVR,
  zkgroup, key transparency, backups, …). We use only `org.signal.libsignal.protocol`, but the unused
  code still contributes to the native `.so` size and **cannot** be stripped by R8/ProGuard (which only
  touch Java/Kotlin, not native code).
- **EMPIRICAL (2026-07, AAR on the app classpath):**
  - The transitive `libsignal-client` **JVM jar** leaks **desktop** natives (Windows `.dll`, macOS
    `.dylib`, Linux `*_amd64.so`/`*_aarch64.so`) into the APK root as Java resources (~180 MB). Excluded
    via `packaging.resources.excludes += ['**/*.dll','**/*.dylib','**/*_amd64.so','**/*_aarch64.so']`.
  - The AAR also bundles a **`libsignal_jni_testing.so`** (~63–69 MB per ABI) that must never ship.
    Excluded via `packaging.jniLibs.excludes += ['**/libsignal_jni_testing.so']`.
  - Before stripping, a clean debug APK was **137.6 MB** (unstripped `libsignal_jni.so` 60.2 / 55.0 MB per ABI).
  - **RESOLVED:** AGP's `stripDebugSymbols` needs an NDK (none was installed → “Unable to strip”). After
    installing **NDK r28c (28.2.13676358)** and declaring `ndkVersion = '28.2.13676358'`, the libs strip to
    **5.5 MB (arm64-v8a) / 4.1 MB (armeabi-v7a)** and the clean debug APK drops to **32 MB** — matching the
    ~+10–16 MB native estimate. The NDK is a local SDK component; only the one-line `ndkVersion` is committed.

### 6.4 Residual items to confirm in the Phase 0 spike

- [x] minSdk floor identified: `libsignal-android` requires **minSdk 23** (app updated to 23).
- [x] Pin an exact version: **0.72.1**, from **Maven Central** (last release that can create *and* receive v3; see §6.1).
- [x] Empirically deserialize a vendored-library v3 `SessionRecord` under the new library — `OldToNewSessionRecordTest` PASSED (§6.2); on-device-record re-check folded into Phase 3.
- [x] No-op `KyberPreKeyStore` keeps the session at v3 — **proven rigorously**: a store subclass whose every Kyber method *throws* survives a full v3 PREKEY→WHISPER round-trip (`NewLibraryV3RoundTripTest#noOpKyberStoreKeepsV3SessionEndToEnd`), so the v3 path provably never touches Kyber. Phase 1 can ship a no-op adapter.
- [x] Per-ABI `.so` measured & **stripped**: with NDK r28c installed + `ndkVersion` declared, `libsignal_jni.so` = **5.5 MB (arm64-v8a) / 4.1 MB (armeabi-v7a)**; clean debug APK **32 MB** (after excluding the testing lib + desktop natives) — §6.3.
- [x] Kotlin Gradle plugin **not required** — the app compiles no Kotlin; `kotlin-stdlib` / `kotlinx-coroutines` resolve as runtime-only transitives and `assembleDebug` succeeds without the plugin.
- [x] Record the pinned artifact SHA-256: `c7391c55072c792f664c51ee297b1c68b91ccdea9c08a02fce8f8d32d14e1a5f` (`libsignal-client-0.72.1.jar`). The app depends on the **AAR** `org.signal:libsignal-android:0.72.1`, whose official Maven Central SHA-256 is `9859acc14aab4f4744abbc9ad150829afce090aa143a58a95af7da1767a6761f`. **ENFORCED** by a scoped `verifyLibsignalPin` Gradle task (wired into `preBuild`) that hashes the resolved AAR and fails the build on mismatch — chosen over full `gradle/verification-metadata.xml` (all-or-nothing; would require checksums for every dependency). gradle-witness is legacy and not applied.

### 6.5 Async / Net surface is out of scope — ProGuard & `CompletableFuture` risks do not apply

**Silence uses only the synchronous, offline Signal Protocol primitives and never connects to Signal
servers**, so a large class of upstream concerns simply cannot be hit:

- Only `SessionCipher`, `SessionBuilder`, and the `*Store` interfaces are used — no chat, CDSI, SVR, key
  transparency, registration, backups, or async stores.
- Consequently `org.signal.libsignal.internal.CompletableFuture` is **never loaded**. The upstream
  v0.73.2 "CompletableFuture stripped by R8" ProGuard hardening and the later CompletableFuture
  cancellation bridge are **irrelevant** to this pin — no extra `-keep` rules are needed for them.
- The consumer ProGuard rules shipped inside the AAR (`META-INF/proguard/libsignal.pro`) are auto-applied
  to `libsignal-android` consumers and already keep the JNI-reachable types we do use.

### 6.6 Manual Key Exchange has no equivalent in the maintained library — DECISION: Option A (hybrid)

**Silence establishes sessions two ways; only the PreKey-bundle path is portable.** The manual
**Key Exchange over SMS** flow (`SmsCipher.process(IncomingKeyExchangeMessage)` →
`src/org/smssecure/smssecure/crypto/SessionBuilder.java`, a fork of the old libsignal `SessionBuilder`)
builds sessions directly from vendored-lib **internal ratchet primitives**:
`ratchet.AliceSignalProtocolParameters` / `BobSignalProtocolParameters` / `RatchetingSession` /
`SymmetricSignalProtocolParameters` and `state.SessionState`. The maintained Rust-core library **does
not expose any of these** — it offers only `SessionBuilder.process(PreKeyBundle)`. So this path cannot
be ported as-is.

Note (verified in code): **Key Exchange is Silence's *only* session-initiation mechanism.**
`KeyExchangeInitiator.initiate(...)` (the "Start secure session" action) sends an
`OutgoingKeyExchangeMessage`; the app's `crypto/SessionBuilder.process(PreKeyBundle)` is **dead code**
(no caller — there is no server to fetch bundles from). The PreKey-message path only (a) lets a
*receiver* establish from a self-contained incoming `PreKeySignalMessage`, and (b) is the first-message
wire form after a session already exists.

**DECISION (2026-07-10): Option A — Hybrid.** Keep the vendored lib for Key Exchange session
*establishment*; use the new lib for `SessionCipher` encrypt/decrypt (proven `SessionRecord`
byte-compat lets new-lib ciphering read vendored-established sessions). Rationale: preserves
**interoperability with existing/older sessions and peers** (no wire-format break) and the current
**ease-of-use** of one-tap Key Exchange, and introduces **no new cryptographic or protocol code** (the
lowest-risk path — see §6.7). Cost accepted: the vendored lib is *trimmed*, not deleted (Phase 4
reduces to the Key-Exchange establishment core: `ratchet.*`, `state.SessionState`, `kdf.*`, `ecc.*`).

**Option B — Drop manual Key Exchange (DEFERRED; revisit later).** Because Silence has no standalone
prekey-bundle initiation today, "prekey-only" is not a feature *removal* — it requires **designing and
building a new prekey-bundle-exchange protocol over SMS** (request → bundle reply → `process(PreKeyBundle)`)
on the new library, plus new UX and a **wire-format/interop break** with un-upgraded peers for new
sessions. The primitives would be the audited library, but the surrounding protocol/trust model
(TOFU, signed-prekey verification, replay, one-time-prekey lifecycle, downgrade) is security-sensitive
design work. Kept on record as a clean long-term end-state (fully deletes the vendored lib) to be
reconsidered in a future major version.

**Option C — Reimplement** the DH exchange atop the new lib's low-level `Curve`, hand-serializing
ratchet state — highest risk (hand-rolled crypto); **rejected**.

### 6.7 Relative security-sensitivity of the options (why A)

- **A (chosen):** no new crypto, no new protocol. Reuses Silence's years-in-production Key-Exchange
  establishment unchanged; swaps only the message cipher to the audited library. The one new thing to
  verify — new `SessionCipher` correctly ratchets a vendored-established session — is *testable*, not new
  cryptography. Tradeoff is a *maintenance* one: legacy establishment code stays (no upstream fixes).
- **B:** audited primitives, but **new protocol/trust-model design** is security-sensitive; plus interop break.
- **C:** **hand-written cryptographic session init** (key agreement, HKDF, ratchet state) — highest risk.

## 7. Build-target prerequisites (status)

These were prerequisites for adopting `libsignal-android` and have been **applied ahead of the swap**
(verified with `assembleDebug` → BUILD SUCCESSFUL):

| Setting | Before | Now | Reason |
| --- | --- | --- | --- |
| `minSdkVersion` | 21 | **23** | libsignal-android floor; ~99% device coverage retained |
| `targetSdkVersion` / `compileSdk` | 34 | **36** | Play compliance; AGP 8.13.0 accepts compileSdk 36 |
| Java source/target | 11 | **17** | libsignal requires Java 17 (max Android supports) |
| Core library desugaring | off | **on** (`desugar_jdk_libs:2.1.5`) | required by libsignal; enables `java.time`/`Optional` at minSdk 23 |
| Kotlin Gradle plugin | absent | **not needed** | AAR is consumed as a binary; the app compiles no Kotlin, so only the runtime `kotlin-stdlib` (transitive) is required |
| NDK | absent | **r28c (28.2.13676358)** via `ndkVersion` | AGP `stripDebugSymbols` needs it to strip the AAR's unstripped `libsignal_jni.so` (60 MB → 5.5 MB/ABI). Local SDK component; only `ndkVersion` is in git |

**Still outstanding from the targetSdk 36 bump:** an edge-to-edge / window-insets pass. A centralized,
opt-out-able inset handler now lives in `BaseActionBarActivity` (pads content with system-bar +
display-cutout insets; `MediaPreviewActivity` opts out). The deprecated `setStatusBarColor` /
`setNavigationBarColor` calls remain (harmless no-ops on API 35+, still functional below) and per-screen
visual QA on a device is recommended — especially the overlay action bar in `ConversationActivity` and
the bottom input panel.
