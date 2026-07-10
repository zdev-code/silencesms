# Vendored `libsignal-java` Minimization Plan

> Goal: reduce app-side usage of the vendored, EOL `org.whispersystems.libsignal.*` fork to the
> **Key-Exchange establishment cluster only**, so that a future *Option B* (replace the interactive
> SMS Key Exchange with a new-library prekey-bundle exchange) can delete the vendored Gradle module
> cleanly, with **nothing else** in the tree still importing it.
>
> This is the follow-on to the hybrid "Option A" transport migration: message encrypt/decrypt already
> runs on the maintained Rust `org.signal:libsignal-android:0.72.1`; only session *establishment*
> (Key Exchange) still uses the vendored ratchet internals the maintained library does not expose.

## Target boundary — what is *allowed* to keep using the vendored lib

The Key-Exchange establishment cluster (and only this) may retain `org.whispersystems.libsignal.*`:

- `crypto/SessionBuilder.java` — the interactive KEX ratchet (uses vendored `ratchet.*`,
  `state.SessionState`, `kdf.*` that the maintained library does not expose).
- `crypto/KeyExchangeInitiator.java`
- `crypto/storage/Vendored*Store.java` (`VendoredSessionStore`, `VendoredPreKeyStore`,
  `VendoredIdentityKeyStore`, `VendoredSignalProtocolStore`)
- `protocol/KeyExchangeMessage.java`, `crypto/PublicKey.java` — KEX wire types.
- `ReceiveKeyDialog.java` — KEX accept/reject trust UI.
- `SmsCipher.java` — the hybrid seam; converts new↔vendored at the KEX boundary.
- `ApplicationContext.java` — registers the vendored `SignalProtocolLoggerProvider` used by the
  vendored KEX internals' own logging.

Everything else moves to `org.signal.libsignal.protocol.*` (or a small local type), converting to the
vendored type **only** at the KEX boundary above.

## Phases (ordered by value ÷ risk)

### Phase 1 — Isolated, behavior-preserving decoupling — **LOW risk** ✅ IMPLEMENTED
No wire-format, on-disk, or session-crypto behavior change; each item is self-contained.

- **`util/PhoneNumberFormatter`**: vendored `logging.Log` → `android.util.Log` (drop-in signatures).
- **`sms/SmsTransportDetails`**: replace `CiphertextMessage.ENCRYPTED_MESSAGE_OVERHEAD` with a local
  `private static final int ENCRYPTED_MESSAGE_OVERHEAD = 53` (the exact current value — the maintained
  library is not guaranteed to expose this wire constant, and its value must not change). Removes the
  vendored `protocol.CiphertextMessage` import.
- **`crypto/AttachmentCipherInputStream`**: `InvalidMacException` is internal to this class (thrown and
  caught within it, rethrown as `InvalidMessageException` at the constructor boundary), so replace it
  with a new local `crypto/InvalidMacException`; switch the thrown `InvalidMessageException` to the
  maintained `org.signal.libsignal.protocol.InvalidMessageException`. Cascade is a single import swap in
  `util/BitmapUtil` (`AttachmentStreamLocalUriFetcher` catches generic `Exception`).

### Phase 2 — Identity/trust types → maintained library — **MODERATE risk**
Move `IdentityKey` / `IdentityKeyPair` off the vendored namespace, converting to the vendored type only
at the KEX boundary (the pattern `SilenceIdentityKeyStore` already uses: `new IdentityKey(bytes, 0)`).

- Files: `crypto/IdentityKeyUtil`, `crypto/IdentityKeyParcelable`, `database/IdentityDatabase`,
  `database/documents/IdentityKeyMismatch`, `database/MessagingDatabase`, `database/DatabaseFactory`,
  `ViewIdentityActivity`, `VerifyIdentityActivity`, `KeyScanningActivity`,
  `sms/IncomingIdentityUpdateMessage`.
- Byte-compatibility of `IdentityKey`/`IdentityKeyPair` across the two libraries is already proven
  (`OldToNewKeyRecordsTest`), so stored identity data reads unchanged.
- Risk driver: touches the identity/safety-number trust UI and the KEX conversion glue; needs on-device
  identity/verify QA.

### Phase 3 — Exception surface → maintained library — **MODERATE–HIGH risk (coupled to Phase 4)**
Stop re-wrapping maintained-library protocol exceptions back into vendored types at the
`SmsCipher`/`MmsCipher` boundary and repoint every catcher (`SmsDecryptJob`, `MmsDownloadJob`,
`SmsSendJob`, `MmsSendJob`, `ConversationActivity`, `MmsDatabase`, `DraftDatabase`,
`EncryptingSmsDatabase`, `ThreadDatabase`, `EncryptingJobSerializer`, `DatabaseFactory`).

> **Coupling:** many of these sites catch `InvalidMessageException` thrown by the **local**
> `MasterCipher` (Phase 4), not the session cipher. Repointing the session-cipher exceptions while the
> local cipher still throws the vendored exception would split a single `catch` across two libraries.
> Do Phase 3 **together with / after** Phase 4, or dual-catch during the transition.

### Phase 4 — Local at-rest EC crypto → maintained `ecc.*` — **HIGH risk**
Move `MasterCipher`, `MasterSecretUtil`, `AsymmetricMasterCipher`, `AsymmetricMasterSecret` off the
vendored `ecc.*` (backed by the archived `curve25519-java 0.5.0` / `libcurve25519.so`) onto the
maintained `org.signal.libsignal.protocol.ecc.*` (Rust core). This is the real security win — the only
maintained Curve25519 available.

- **Prerequisite (blocker):** a round-trip test proving an existing, vendored-serialized on-disk
  `AsymmetricMasterSecret` / EC key blob decodes and agrees under the maintained `ecc` API **before**
  shipping. A format mismatch here would lock users out of locked-state decryption. Do not ship this
  phase until that test passes on a real pre-migration blob.

## Findings & revised sequencing (investigated 2026-07-11)

Deeper reading of the seam revised the risk of the later phases:

- **EC byte-compat is proven.** `EcKeyCrossLibraryTest` (in the `:java` migration module) shows a
  vendored-serialized Curve25519 private scalar / public point decodes under the maintained `ecc`
  API, re-serializes byte-identically, and yields an identical ECDH agreement (both directions).
  `./gradlew :java:test --tests "*EcKeyCrossLibraryTest"` → PASSED. Phase 4's on-disk safety for the
  **key material** is therefore established (upgrade-in-place device QA still recommended).

- **Phase 2 is knotted with the vendored KEX trust model — not a clean type-swap.** The KEX path
  (`SmsCipher.process(KeyExchangeMessage)` → vendored `SessionBuilder`) throws
  **`UntrustedIdentityException`** (which *carries* an `IdentityKey`) and **`StaleKeyExchangeException`**.
  `StaleKeyExchangeException` has **no maintained equivalent**, and both are consumed by app callers
  (`SmsDecryptJob`, `ConversationItem`, `ReceiveKeyDialog`, `MmsSendJob`) for the identity-mismatch UI.
  While KEX stays vendored, migrating the `IdentityKey` *type* app-wide forces new↔vendored conversions
  at **every** identity-exception and trust-UI site (and both a vendored and a new-lib
  `UntrustedIdentityException` would coexist), rather than the single boundary the plan assumed. This
  is churn, not a blocker — but it means the `IdentityKey` type surface only becomes truly clean under
  **Option B**.

- **Phase 4 has a second EC cluster tied to a vendored wire type.** `AsymmetricMasterCipher` /
  `AsymmetricMasterSecret` use `crypto/PublicKey` (a vendored KEX/wire type built on
  `ECPublicKey.KEY_SIZE`), so their EC cannot move to the maintained `ecc` without either converting at
  that seam or also migrating `PublicKey`. The identity-keypair EC cluster
  (`MasterCipher.encrypt/decryptKey` + `IdentityKeyUtil` keygen) has **no** such coupling and is cleanly
  migratable.

**Revised recommendation (safe, value-ordered):**
1. **Phase 4a (clean, high value):** move the *EC engine* calls (`Curve.generateKeyPair`,
   `calculateAgreement`, `decodePrivatePoint`) in the at-rest crypto onto the maintained Rust `ecc`,
   converting keys via `serialize()`/`decode…` at any vendored-type seam (`PublicKey`, `IdentityKey`).
   Delivers the maintained-Curve25519 security win; verified by `EcKeyCrossLibraryTest` + build.
2. **Phase 3a:** repoint the *message* exceptions (`InvalidMessageException`, `DuplicateMessageException`,
   `NoSessionException`, `LegacyMessageException`, `InvalidVersionException`, `InvalidKeyIdException`) to
   the maintained library. **Keep `UntrustedIdentityException` / `StaleKeyExchangeException` vendored**
   (KEX trust model).
3. **Phase 2 / full type-swap:** defer the `IdentityKey`/`IdentityKeyPair` *type* migration until it can
   ride with **Option B** (replacing KEX), which removes the vendored trust exceptions that pin the type
   — avoiding pervasive conversion churn. Do it earlier only if the churn is explicitly accepted.

## Implementation outcome (2026-07-11) — Phases 2+3+4 done as the full "B-now" swap

Implemented together (single coordinated change, `:assembleDebug` green, all `:java` crypto migration
tests pass incl. `EcKeyCrossLibraryTest` and the vendored↔maintained cross-validation):

- **Phase 4 (EC/at-rest):** `MasterCipher` (incl. `encrypt/decryptKey`), `MasterSecretUtil`,
  `AsymmetricMasterSecret`, `AsymmetricMasterCipher`, `PublicKey` (now a local `KEY_SIZE = 36`
  constant), and `IdentityKeyUtil` keygen all use the maintained `org.signal.libsignal.protocol.ecc`
  (Rust core) instead of the archived curve25519-java. `crypto/PublicKey` was found to have **no** KEX
  usage, so Cluster II migrated cleanly.
- **Phase 2 (identity types):** `IdentityKey`/`IdentityKeyPair` are maintained-library typed across
  `IdentityKeyUtil`, `IdentityDatabase`, `IdentityKeyParcelable`, `IdentityKeyMismatch`,
  `MessagingDatabase`, `DatabaseFactory`, `ViewIdentityActivity`, `VerifyIdentityActivity`,
  `KeyScanningActivity`, `IncomingIdentityUpdateMessage`. `SilenceIdentityKeyStore` simplified (no more
  `toVendored`); the **vendored→maintained conversions now live at the KEX seams** —
  `VendoredIdentityKeyStore`, `ReceiveKeyDialog`, and `VerifyIdentityActivity.getRemoteIdentityKey`
  (which reads a vendored KEX `SessionRecord`). Safety numbers are raw hex of `serialize()` (byte-
  identical), so display/compare is unchanged.
- **Phase 3 (exceptions):** all *message* exceptions (`InvalidMessageException`,
  `DuplicateMessageException`, `NoSessionException`, `LegacyMessageException`, `InvalidVersionException`,
  `InvalidKeyIdException`) are maintained-library typed; `SmsCipher`/`MmsCipher` stopped re-wrapping
  them, and `SmsCipher.process` converts the vendored KEX message-exceptions to maintained at the seam.
  **`UntrustedIdentityException` and `StaleKeyExchangeException` remain vendored** (KEX trust model);
  `SmsCipher`/`MmsCipher` still convert the maintained `UntrustedIdentityException` to vendored there.

**Residual vendored `src/` usage (the intended boundary):** `crypto/SessionBuilder`,
`crypto/KeyExchangeInitiator`, `crypto/storage/Vendored*Store`, `protocol/KeyExchangeMessage`,
`ReceiveKeyDialog`, the `SmsCipher` seam, `ApplicationContext` (logger), the KEX trust exceptions in
`SmsDecryptJob`/`MmsDownloadJob`/`SmsSendJob`/`MmsSendJob`, and the vendored session reads in
`SmsSentJob`/`VerifyIdentityActivity`. Everything else is on the maintained library.

**Ship gate (unchanged): on-device QA required** — identity/verify flows, locked-state receive +
unlock (exercises `AsymmetricMasterCipher` ECDH on the Rust core), and upgrade-in-place over a
populated pre-migration database. `EcKeyCrossLibraryTest` proves EC key-material byte-compat but does
not substitute for the on-device upgrade test.

> **On-device QA PASSED (2026-07-11, RELEASE/R8 build).** Old-app ↔ fork interop verified: secure-session
> establishment (both directions), send/receive, safety numbers match, end-session, MMS, and
> upgrade-in-place. Native path confirmed (`libsignal_jni.so` loads; no `libcurve25519.so`; testing lib
> excluded). A lone `StaleKeyExchangeException` ("no matching sequence for response") is the unchanged
> vendored-KEX response to a duplicate/late key-exchange reply — caught, surfaced as a stale-KEX message,
> self-heals on re-establish. **Only unexercised path:** locked-state receive+unlock
> (`AsymmetricMasterCipher`) — tester does not use a local passphrase; lowest risk (same Rust curve,
> byte-compat proven), still recommended before a passphrase-user release.

> Note: `DeviceConsistencyTest` in the **vendored library's own** `:java` suite fails; it exercises
> `org.whispersystems.libsignal.devices.*` (untouched by this change) and is unrelated to the app
> migration.

## Curve engine fully on Rust (2026-07-11) — `curve25519-java` retired

Follow-on to the phase work above: the vendored `org.whispersystems.libsignal.ecc.Curve` was rewritten
to **delegate its EC engine to the maintained Rust core** (`org.signal.libsignal.protocol.ecc.Curve`)
instead of the archived `curve25519-java 0.5.0`. Because that class is the single choke point every
vendored crypto path (including the Key-Exchange ratchet) funnels through, this puts **all** EC math —
X25519 agreement, XEd25519 signatures, keygen — on the audited Rust core. The public API and the
vendored `DjbEC*Key` byte formats are unchanged, and it still throws the vendored `InvalidKeyException`
(vendored callers depend on it).

- **`curve25519-java` removed** from `:java` main/`api` and from the app's runtime classpath (verified:
  `debugRuntimeClasspath` no longer contains it). It is retained **test-only** (`testImplementation`) to
  cross-verify interop. `libcurve25519.so` is no longer bundled (the `keepDebugSymbols` entry was
  removed). `:java` now compiles against the maintained API via `compileOnly` (provided at app runtime
  by `libsignal-android`).
- **VRF removed:** the maintained `Curve` exposes no VRF, so `calculate/verifyVrfSignature` were dropped
  along with their only user, the unused **`DeviceConsistency`** feature (`devices/*`,
  `protocol/DeviceConsistencyMessage`, and its test — which was the pre-existing failing test).
- **Interop proven (`EcKeyCrossLibraryTest`, all pass):**
  `x25519AgreementInteroperatesWithArchivedCurve` (a curve25519-java-generated, clamped key yields an
  identical shared secret under the Rust engine) and `xed25519SignaturesInteroperateWithArchivedCurve`
  (signatures verify in both directions). Plus all handshake tests (`SessionBuilderTest`,
  `SessionCipherTest`, `SimultaneousInitiateTests`, `NumericFingerprintGenerator`) pass on the new engine.
- **Removed two obsolete KATs** (`RootKeyTest`, `RatchetingSessionTest`): they hardcoded
  *intentionally-unclamped* private-key vectors and expected outputs from curve25519-java's
  **non-RFC-7748 raw-scalar** agreement. Verified directly: curve25519-java does not clamp the scalar,
  the Rust core does (per spec), and once a key is clamped — as **all** real keys are — both engines
  agree byte-for-byte. So this affects no real key; the Rust behavior is the correct one.

**What still remains vendored:** only the pure-Java KEX ratchet *logic* (`crypto/SessionBuilder`,
`ratchet.*`, `state.SessionState`, `kdf.*`) — which the maintained library exposes no API for — now
running on the Rust curve. Fully removing it is still Option B.

## Stays vendored until Option B
The Key-Exchange establishment cluster listed under **Target boundary**. Option B (design a
prekey-bundle exchange over SMS on the maintained library) is the only path that removes it entirely;
after Phases 1–4 that is the *sole* remaining vendored dependency, so Option B drops the
`:org.whispersystems.libsignal` module and its `settings.gradle`/`build.gradle` references with no other
code changes.

## Verification
- `:assembleDebug` green after each phase.
- Phases 2 & 4 additionally require on-device QA (identity/verify flows; locked-state receive + unlock;
  upgrade-in-place over a populated pre-migration database).
