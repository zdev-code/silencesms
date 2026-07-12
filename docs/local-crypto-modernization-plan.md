# Local At-Rest Crypto Modernization — Implementation Plan

> Status: **PLANNING ONLY** — no code changes yet.
> Scope: modernize the **local (on-device) encryption** of the key store and database fields.
> This is independent of the transport (Signal Protocol) layer and does **not** affect peer interop.

## 0. Guiding constraint: do not lock out or wipe existing users

Several structural facts make every change here dangerous if done naively:

1. **Single root secret.** A `MasterSecret` (one AES encryption key + one HMAC key) is generated once
   and wrapped by a passphrase-derived KDF in
   [`MasterSecretUtil`](../src/org/smssecure/smssecure/crypto/MasterSecretUtil.java). Everything else
   derives from it. There is exactly **one** enc/MAC pair on disk
   ([`MasterSecret`](../src/org/smssecure/smssecure/crypto/MasterSecret.java) holds only
   `encryptionKey` + `macKey`), so *replacing* that pair makes all data still encrypted under the old
   pair unreadable.
2. **No version byte in the stored format.** `MasterCipher` writes raw
   `IV ‖ AES-128-CBC(plaintext) ‖ HMAC-SHA1` with **no algorithm/version tag**
   ([`MasterCipher`](../src/org/smssecure/smssecure/crypto/MasterCipher.java)). At read time there is
   no way to tell which algorithm produced a blob. `MasterCipher` encrypts message bodies, drafts,
   conversation-list data, and encrypted private keys across the whole database.
3. **Not every stored value is a `MasterCipher` envelope.** Identity rows store a **raw HMAC-SHA1**
   (via [`MasterCipher.getMacFor`](../src/org/smssecure/smssecure/crypto/MasterCipher.java), used at
   [`IdentityDatabase`](../src/org/smssecure/smssecure/database/IdentityDatabase.java) save/verify) —
   a bare MAC, **not** an encrypted blob. So the envelope/version-prefix scheme below does **not**
   cover it, and globally re-pointing `getMacFor` at HMAC-SHA256 would invalidate every existing
   identity row (see §2A).
4. **The secret has several unversioned in-memory / serialized representations.**
   [`MasterSecret` is `Parcelable`](../src/org/smssecure/smssecure/crypto/MasterSecret.java) (the
   parcel hardcodes `"AES"` / `"HmacSHA1"` and carries **no version field**), it is passed between
   activities, and its bytes seed the job-manager's encryption keys. Any keyring / root-secret
   redesign must version **all** of these, not just the on-disk wrapper. The locked-state path in
   [`AsymmetricMasterCipher`](../src/org/smssecure/smssecure/crypto/AsymmetricMasterCipher.java)
   independently derives **legacy-sized** ephemeral keys (16-byte AES, 20-byte HMAC-SHA1) and must
   keep reading old locked messages while emitting whichever new envelope is selected.
5. **Wrapped-secret writes are not atomic.** `MasterSecretUtil.generateMasterSecret` /
   `changeMasterSecretPassphrase` persist `encryption_salt`, `mac_salt`, `passphrase_iterations`,
   `master_secret`, and `passphrase_initialized` through **five separate `commit()`s**. A process
   death between them can already leave an undecryptable combination; any migration that rewraps the
   secret must fix this (see Phase D).

**Two derived principles:**

- **Derive, don't regenerate.** New field keys are obtained by **HKDF-SHA256 domain separation** from
  the *existing combined root secret*, not by generating a second independently wrapped key. This
  keeps `v0` data readable with the `v0` keys while `v1+` writes use derived keys — no second key
  generation, no lockout. Argon2 (Phase C) modernizes protection **of the root secret**; it does not
  trigger a field-key migration.
- **Additive and versioned only.** No algorithm may be swapped in place. Every change is additive and
  versioned, keeping old readers alive and migrating lazily or via a one-time guarded pass.

### Key hierarchy (target)

```
root secret  = existing combined MasterSecret bytes (enc16 ‖ mac20), unchanged on disk
  ├─ v0 field keys : the existing AES-128 + HMAC-SHA1 keys (used verbatim for v0 reads)
  ├─ v2 field enc  : HKDF-SHA256(root, info="silence/mastercipher/v2/aead")   → AES-256-GCM key
  └─ (future keys) : HKDF-SHA256(root, info="silence/<purpose>/<version>/...") — distinct labels
```

Every derived key uses a **distinct, versioned `info` label** so keys can never collide across
purposes or versions. The root itself is only ever *re-wrapped* (Phase D), never regenerated.

## 1. Current state (verified)

| Concern | Current implementation | Location |
|---|---|---|
| Passphrase KDF | `PBEWITHSHA1AND128BITAES-CBC-BC` (PBKDF1/SHA-1), adaptive iterations (10k–100k) | `MasterSecretUtil.getKeyFromPassphrase` |
| Master secret wrap | PBE cipher + PBE-MAC over the encrypted secret | `MasterSecretUtil.encryptWithPassphrase` / `macWithPassphrase` |
| Field encryption | AES-128-CBC/PKCS5 | `MasterCipher` |
| Field authentication | HMAC-SHA1 (encrypt-then-MAC) | `MasterCipher` |
| Master key material | AES-128 enc key + HmacSHA1 mac key | `MasterSecretUtil.generate*Secret` |
| Key wrapping location | App-managed blob in `SharedPreferences` | `MasterSecretUtil.save` |
| Stored-format versioning | **None** | — |

## 2. Target changes & sequencing

Phase ordering is by **value ÷ risk**. SQLCipher (#5) is **explicitly deferred to a later release**
because it has the **least incremental benefit** (the data is already field-encrypted) and the
**highest migration risk** (whole-database re-encryption).

### Phase A — Foundation: versioned `MasterCipher` envelope (PREREQUISITE, ships on its own)
**No algorithm change.** Phase A only introduces the framing that later phases need, and it must ship
**in its own release, before** any algorithm change (B–D), so the format machinery is proven in the
field with zero cryptographic risk. (The previous draft both claimed "no algorithm change" and
defined `v1` as the new Phase B/C primitives — that was contradictory. Fixed below by making the
Phase A envelope carry the **unchanged legacy primitives**.)

- **`v0` — implicit legacy (unchanged).** Existing blobs: `IV(16) ‖ AES-128-CBC(pt) ‖ HMAC-SHA1(20)`,
  no header. Left exactly as-is and read by the legacy path.
- **Explicit envelope (new writes).** A self-describing container:

  ```
  MAGIC(4)  ‖ env_ver(1) ‖ alg_id(1) ‖ nonce_len(1) ‖ nonce(nonce_len)
            ‖ ct_len(4, big-endian) ‖ ciphertext(ct_len) ‖ auth_tag / MAC
  ```

  - `MAGIC`: a fixed **4-byte** marker chosen so the whole prefix cannot be produced by a valid legacy
    blob (see discriminator rule).
  - `env_ver`: envelope-format version (starts at 1).
  - `alg_id`: algorithm identifier (`1` = legacy AES-128-CBC+HMAC-SHA1 for Phase A; `2` = AES-256-GCM
    for Phase B).
  - `nonce_len` / `nonce`: IV or GCM nonce.
  - `ct_len` / `ciphertext`: explicit length so trailing bytes are unambiguous.
  - **authenticated header:** for AEAD algorithms the entire header (`MAGIC…ct_len`) is passed as
    **AAD** so the version/algorithm fields are tamper-evident; for encrypt-then-MAC the MAC covers
    the header too.
- **`v1`:** the explicit envelope with `alg_id = 1` (legacy primitives). Shipping this first proves
  detection, round-tripping, and cross-version reads **without changing any cryptography**.

- **Collision-free discriminator (critical).** A **single** known-version prefix byte is unsafe: it
  can always equal the first byte of a legacy random IV. Detection must therefore combine a
  **multi-byte `MAGIC`** with a **legacy length invariant**, not magic alone:
  - A legacy `v0` blob length is always `16 + (16·n) + 20` for `n ≥ 1` CBC blocks (IV + PKCS5-padded
    ciphertext + 20-byte HMAC). The reader first checks the `MAGIC`; a match is only accepted as an
    envelope when the buffer **also** cannot be a well-formed legacy blob of the declared shape.
  - Equivalently: treat any buffer that satisfies the legacy length invariant **and** verifies under
    the legacy HMAC as `v0`; only otherwise consult `MAGIC`. This makes misclassification require an
    HMAC-SHA1 forgery, not a 1-in-256 IV coincidence.
- [ ] Reader auto-detects `v0` vs explicit envelope by the rule above; all existing call sites keep
      working unchanged (they still go through `MasterCipher`).
- [ ] Unit tests: legacy blob round-trips as `v0`; `v1` envelope round-trips; a random legacy IV that
      happens to start with a `MAGIC` byte is **still** read as `v0`; truncated/tampered header
      rejected.

### Phase B — Upgrade field crypto to AES-256-GCM (writes as envelope `alg_id = 2`, "`v2`")
**No second wrapped key generation.** The stronger key is **derived** from the existing root secret,
so existing users are never re-keyed and `v0` data stays readable.

- [ ] Derive the `v2` field key with **HKDF-SHA256** from the existing combined root secret:
      `enc256 = HKDF(root, info = "silence/mastercipher/v2/aead", L = 32)`. The `v0` AES-128 +
      HMAC-SHA1 keys remain the literal root bytes and are used verbatim for `v0` reads.
- [ ] `MasterCipher` `v2` path: **AES-256-GCM** (authenticated encryption in one primitive; the
      envelope header is the AAD). No separate HMAC key is needed for `v2`.
- [ ] **Lazy re-encryption:** when a `v0` record is decrypted and rewritten, persist it as `v2`.
      Optional one-time guarded background pass to migrate all rows.
- [ ] **Locked-state path.**
      [`AsymmetricMasterCipher`](../src/org/smssecure/smssecure/crypto/AsymmetricMasterCipher.java)
      derives its own ephemeral 16-byte AES / 20-byte HMAC-SHA1 keys from an ECDH secret and feeds
      them to `MasterCipher`. It must continue to **read** old (`v0`) locked messages while **writing**
      the selected new envelope — i.e. its ephemeral derivation must also grow a versioned `v2`
      branch, or explicitly stay `v0` until a dedicated migration. Do not let the global `MasterCipher`
      write-version silently change what this path emits.
- [ ] Tests: GCM tamper/AAD-tamper detection, wrong-key rejection, `v0`→`v2` upgrade-on-write,
      locked-state read of a `v0` message after the switch.

> Note: AES-256-GCM is preferred over AES-256-CBC+HMAC-SHA256 (single authenticated primitive, header
> as AAD). If encrypt-then-MAC is kept instead, reserve a separate `alg_id` and derive an independent
> HMAC-SHA256 key with its own HKDF label (`"…/v2/mac"`).

## 2A. Out-of-envelope value: the identity HMAC (must be handled separately)

`IdentityDatabase` stores a **raw HMAC-SHA1** over `recipientId ‖ serializedIdentity` produced by
[`MasterCipher.getMacFor`](../src/org/smssecure/smssecure/crypto/MasterCipher.java) and verified by
`verifyMacFor` (see `IdentityDatabase.saveIdentity` / `isValidIdentity` /
`getStoredIdentity`). This is a **bare MAC, not a `MasterCipher` envelope**, so the Phase A
version-prefix/auto-detection scheme does **not** apply to it, and it has no length/IV structure to
key detection off.

**Do not** globally change `getMacFor` to HMAC-SHA256 — that would invalidate every stored identity
row at once. Instead, pick one:

- **Own versioned representation + dual verifier (preferred long-term).** Store the identity MAC with
  its own small tag (`mac_ver`), keep an HMAC-SHA1 verifier for `mac_ver = 0` rows, and write new
  rows as HMAC-SHA256 (`mac_ver = 1`), upgrading a row on next successful verify+write. The
  HMAC-SHA256 key is a distinct HKDF label (`"silence/identity-mac/v1"`), not the root MAC key.
- **Explicitly scoped legacy (minimal now).** Leave this narrowly-scoped MAC on HMAC-SHA1 and call it
  out as a known exception, deferring it to a dedicated `IdentityDatabase` migration. Acceptable
  because it authenticates a locally-stored public identity key, not confidential plaintext.

Either way this is **separate** from the `MasterCipher` envelope work and must not be bundled into it.

### Phase C — Upgrade passphrase KDF to native Argon2id
- [ ] Add **Argon2id** (memory-hard) as the new passphrase KDF; store its parameters
      (memory, iterations, parallelism, salt) alongside the wrapped secret.
- [ ] Add a stored **`kdf_version`** field: `0` = legacy PBKDF1/SHA-1, `1` = Argon2id.
- [ ] Implement Argon2id by **vendoring the PHC reference C implementation**
      (`P-H-C/phc-winner-argon2`, CC0 / Apache-2.0) into a `cpp/` folder with a thin JNI wrapper,
      built via NDK **`externalNativeBuild`** (CMake).
  - Rationale: pure-Java Argon2 is slow on mobile, which forces **lower memory parameters** within an
      acceptable unlock latency — and memory cost is exactly the GPU/ASIC-resistance property. Native
      speed lets us keep strong parameters. The algorithm is **frozen** (RFC 9106), so vendoring a
      pinned commit carries no real maintenance burden and avoids any third-party Gradle crypto dep.
  - On the ARM ABIs we ship (`armeabi-v7a`, `arm64-v8a`), upstream uses the portable `ref.c` (its SIMD
      `opt.c` path is x86-only); native `ref.c` is still dramatically faster than pure-Java.
  - **Build note (corrected):** the app does **not** currently own any native source — it has no
      `externalNativeBuild`/CMake and ships **no `libcurve25519.so`** (that reference is outdated;
      `curve25519-java` is now **test-only**). The one native library that ships is the Rust
      **`libsignal_jni.so`**, provided *prebuilt* by the `libsignal-android` AAR — the app's NDK
      (`ndkVersion`) is configured only to **strip** that AAR `.so`. Phase C therefore **adds a new
      `externalNativeBuild` (CMake) block from scratch** to compile `libargon2.so`. This is still
      incremental (the NDK toolchain is already present) but is not "already used" as previously
      claimed.
  - **Rejected alternatives:** BouncyCastle `Argon2BytesGenerator` (pure Java → slow-defender /
      lowered-memory problem); `org.signal:argon2` (**archived**); `argon2kt` (extra native dep);
      whyoleg `cryptography-kotlin` (**no Argon2**); libsodium / Lazysodium (larger native blob + JNA).
- [ ] **Availability is not absolute — plan for native failure.** Native loading **can** still fail:
      packaging/APK-split omission, on-device extraction failure, ABI mismatch, storage corruption, or
      a JNI error. The design must include:
  - **Fixed, device-tested memory tiers.** Pin concrete `{memoryKiB, iterations, parallelism}` tiers
      validated on **low-RAM and 32-bit** devices for acceptable unlock latency without OOM; never
      derive memory cost from available RAM at runtime.
  - **Strict upper bounds on stored parameters.** When reading `kdf_version = 1` parameters back,
      clamp/reject values above hard maxima **before** allocating, so a corrupted/hostile parameter
      block cannot trigger a huge allocation.
  - **Allocation-failure handling.** Catch native allocation failure and surface it as a recoverable
      unlock error, not a crash.
  - **Password encoding + zeroization.** Fix a single password byte-encoding (UTF-8, NFC) and
      **zeroize** password and derived-key native buffers after use.
  - **Recovery strategy (replaces the old "no fallback ⇒ can't happen" claim).** If Argon2 is
      unavailable, **do not** silently downgrade to a weaker KDF (that would be a KDF-downgrade
      surface). Instead **retain the verified legacy wrapper**: the user keeps unlocking via the
      existing PBKDF1 path and migration simply does not proceed until the Argon2 path is proven
      usable on that device. No weak forward fallback is introduced; the legacy reader is the
      migration path, not a downgrade.
- [ ] Tests: derive/verify under each `kdf_version`; parameter round-trip **and** out-of-bounds
      rejection; JNI load smoke test; simulated native-load failure keeps the legacy wrapper working;
      Argon2 RFC 9106 known-answer vectors.

### Phase D — Re-wrap the `MasterSecret` under Argon2id on next successful unlock
This is the critical, lockout-sensitive step. Because field keys are **derived** (HKDF, Phase B), the
*root secret bytes never change* here — Phase D only changes **how the root is wrapped** (PBKDF1 →
Argon2id). That removes the old "regenerate 128→256 keys" migration entirely.

- [ ] On unlock, decrypt the `MasterSecret` with the **stored** `kdf_version` (old PBKDF1 path for
      existing users). A wrong passphrase must **never** trigger a rewrite.

**Concrete atomic storage protocol** (fixes the existing non-atomic five-`commit()` hazard in
`MasterSecretUtil`, §0.5):

1. **Write the complete new envelope under one new key.** Serialize the *entire* Argon2 wrapper —
   `kdf_version`, salts, Argon2 params, wrapped secret — into a **single** value stored under a new
   preference key (e.g. `master_secret_v2`). One `commit()`, one self-contained blob (no more
   independently-committed salt/iterations/ciphertext fields that can tear).
2. **Leave the complete legacy envelope untouched.** The existing `encryption_salt` / `mac_salt` /
   `passphrase_iterations` / `master_secret` / `passphrase_initialized` set stays byte-for-byte as-is.
3. **Read-back and decrypt-verify** the new envelope (re-derive with Argon2 and confirm it yields the
   same root secret) **before** it is trusted.
4. **Activate with a single committed pointer.** Flip one committed value — an
   `active_master_secret = v2` pointer (or simply "prefer a verified `master_secret_v2` when present
   and self-consistent"). This single write is the atomic commit point; a crash before it leaves the
   legacy envelope authoritative, a crash after it leaves the verified new envelope authoritative.
   There is no intermediate state that is undecryptable.
5. **Retain the legacy envelope for ≥ 1 rollback release**, then remove in a later release only after
   the new path is proven.
6. **Passphrase change and "password disabled" are part of the same transaction.** Changing the
   passphrase (or switching to `UNENCRYPTED_PASSPHRASE`) writes a new complete envelope + pointer flip
   in the same protocol; never a partial rewrite of individual fields.

- [ ] **Never** remove the legacy KDF / legacy `MasterCipher` reader / legacy identity-MAC verifier
      until strategy confirms all records are migrated (likely several releases).
- [ ] Tests: upgrade-in-place on a populated DB; **process-death injected between every step** above
      resumes to a decryptable state (legacy- or new-authoritative, never broken); wrong passphrase
      never rewrites; passphrase-change atomicity; downgrade/rollback to the retained legacy envelope.

### Phase E — Optional: hardware-backed key wrapping (Android Keystore / StrongBox)
- [ ] Wrap the `MasterSecret` with a non-exportable **Android Keystore** key (StrongBox when present).
- [ ] Gate behind API level and hardware availability; fall back to passphrase-wrapped secret.
      Baseline is now **minSdk 23** (Android Keystore AES is guaranteed); **StrongBox** (API 28) and
      **BiometricPrompt** (API 28) still need a runtime guard + `FEATURE_STRONGBOX_KEYSTORE`
      hardware check (StrongBox hardware is never guaranteed by API level alone).
- [ ] Note: this changes the **threat model and UX** (device-bound key, biometric/lockscreen
      gating). Treat as a distinct design decision, not a silent swap.
- [ ] Tests: keystore-wrapped unlock, fallback path, key-invalidation handling (e.g. lockscreen reset).

### Phase F (DEFERRED — later release) — SQLCipher whole-database encryption
> Deferred because it has the **least incremental benefit** (fields are already encrypted via
> `MasterCipher`) and the **highest risk** (full-DB re-encryption).

- [ ] Evaluate SQLCipher integration against the existing DB open helper.
- [ ] Design a **resumable, backed-up, guarded** whole-DB migration: decrypt each field (existing
      `MasterCipher`) and rewrite into the SQLCipher-encrypted DB.
- [ ] Provide rollback (keep original DB until migration verified).
- [ ] Extensive migration testing across DB sizes and interruption points.
- [ ] Decide whether field-level `MasterCipher` is retained (defense in depth) or retired.

## 3. Backward-compatibility summary

| Phase | User-visible risk if done correctly | Safety mechanism |
|---|---|---|
| A | None (no algorithm change; legacy primitives in the envelope) | Multi-byte magic + legacy length-invariant discriminator; `v0` reader retained |
| B | None | `v0` reader retained; `v2` key **HKDF-derived** from the same root; lazy upgrade-on-write |
| C | None | `kdf_version` field; legacy PBKDF1 reader retained for migration; native Argon2id with legacy-wrapper recovery (no weak fallback) |
| D | None *if* the atomic write-new-verify-then-flip protocol is honored | Re-wrap only after successful decrypt; single committed pointer; never destructive on wrong passphrase |
| Identity MAC (§2A) | None | Own `mac_ver` + dual verifier, or explicitly kept on HMAC-SHA1 |
| E | Threat-model/UX change (opt-in) | API/hardware gating + fallback |
| F (deferred) | Migration-or-bust | Resumable migration + backup + rollback |

**Hard rules:**
- Never swap an algorithm without a version tag and a retained legacy reader.
- Discriminate `v0` vs envelope by **multi-byte magic + legacy length invariant**, never a single
  prefix byte (it can alias a legacy IV byte).
- Derive new field keys from the existing root (HKDF, distinct labels); never regenerate the root or
  add a second independently-wrapped key generation.
- Never overwrite the old wrapped secret before the new one is durably written **and verified**;
  commit the switch with a single atomic pointer.
- Never trigger a destructive rewrite on a failed/incorrect passphrase attempt.
- Never change the identity `getMacFor` algorithm globally; version it or scope it out.
- Keep legacy readers/verifiers for several releases until migration is provably complete.

## 4. Testing strategy
- Unit tests per phase (round-trips, cross-version reads, tamper/MAC, wrong-key/passphrase).
- **Discriminator test:** a legacy blob whose IV happens to begin with a magic byte is still read as
  `v0`; a truncated/tampered envelope header is rejected.
- **Upgrade-in-place integration test:** start from a database written by the *current* app, upgrade,
  verify all messages/drafts/keys/identity rows remain readable.
- **Interrupted-migration test:** kill between *each* Phase-D step, relaunch, verify no data loss and
  a decryptable (legacy- or new-authoritative) state.
- Native Argon2 JNI load test across shipped ABIs (`armeabi-v7a`, `arm64-v8a`); simulated
  native-load-failure keeps the legacy wrapper usable; parameter out-of-bounds rejection; Argon2
  known-answer vectors (RFC 9106); device-matrix check for Keystore/StrongBox behavior.

## 5. Suggested release mapping
- **Release N:** **Phase A only** (versioned envelope, no algorithm change) — ship the format
  machinery on its own so detection/round-tripping is proven in the field before any crypto changes.
- **Release N+1:** Phase B + Phase C + Phase D (AES-256-GCM field crypto + Argon2id re-wrap), the
  cohesive migrating change, plus the identity-MAC decision (§2A).
- **Release N+2:** Phase E (hardware-backed wrapping), if desired.
- **Later release:** Phase F (SQLCipher), the deferred change.

## 6. Open questions
- [x] `v2` field format: **AES-256-GCM** (single authenticated primitive; envelope header as AAD).
      Encrypt-then-MAC with a separate HKDF-derived HMAC-SHA256 key is the fallback if GCM is rejected.
- [x] Argon2 implementation: **vendored PHC reference C** (`P-H-C/phc-winner-argon2`, CC0/Apache-2.0)
      via a **new** NDK `externalNativeBuild` + JNI — native speed, frozen algorithm, no third-party
      crypto dep. Rejected: BouncyCastle (pure-Java → weak params), archived `org.signal:argon2`,
      `argon2kt`, whyoleg `cryptography-kotlin` (no Argon2), libsodium/Lazysodium (heavier + JNA).
- [x] Forward KDF fallback: **no weak-KDF fallback**. If native Argon2 is unavailable, retain the
      **verified legacy wrapper** and defer migration (the legacy PBKDF1 reader is the migration path,
      not a downgrade). minSdk stays **23**.
- [ ] Identity MAC (§2A): own `mac_ver` + dual verifier now, or defer to a separate DB migration?
- [ ] Decide on lazy-only vs lazy + background one-time field migration for Phase B.
- [ ] Fix the exact `MAGIC` bytes and confirm no valid legacy blob can satisfy magic + length
      invariant simultaneously.

> **Build baseline (already applied):** the app now targets **minSdk 23**, **compile/targetSdk 36**,
> **Java 17**, with **core library desugaring** enabled. This guarantees the Android Keystore AES
> APIs (23) and makes `java.time`/`Optional` available for new code. **minSdk stays 23** — native
> Argon2id works at any API level, and AES-256-GCM + HMAC-SHA256 are already native well below 23, so
> no bump (e.g. to 26 for platform `PBKDF2withHmacSHA256`) is needed.
>
> **New build requirement for Phase C:** add a **new `externalNativeBuild`** (CMake) block to compile
> the vendored PHC Argon2 C sources in `cpp/` into `libargon2.so`. The app currently owns **no**
> native source — it ships only the prebuilt Rust **`libsignal_jni.so`** from the `libsignal-android`
> AAR (the NDK is configured only to *strip* that `.so`), so this adds an app-owned native build for
> the first time. The toolchain (NDK) is already present, so it is incremental but not pre-existing.
>
> **Note on Curve25519 (updated):** the transport layer already migrated to the maintained Rust
> **`libsignal-android` (0.72.1)**, whose `libsignal_jni.so` provides Curve25519. The old
> `curve25519-java` dependency is now **test-only** (cross-validation) and **no `libcurve25519.so` is
> shipped**. Earlier references in this plan to a "required `libcurve25519.so`" are obsolete and have
> been corrected above.
