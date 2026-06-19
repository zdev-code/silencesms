# Local At-Rest Crypto Modernization — Implementation Plan

> Status: **PLANNING ONLY** — no code changes yet.
> Scope: modernize the **local (on-device) encryption** of the key store and database fields.
> This is independent of the transport (Signal Protocol) layer and does **not** affect peer interop.

## 0. Guiding constraint: do not lock out or wipe existing users

Two structural facts make every change here dangerous if done naively:

1. **Single root secret.** A `MasterSecret` (AES encryption key + HMAC key) is generated once and
   wrapped by a passphrase-derived KDF in
   [`MasterSecretUtil`](../src/org/smssecure/smssecure/crypto/MasterSecretUtil.java). Everything else
   derives from it.
2. **No version byte in the stored format.** `MasterCipher` writes raw
   `IV ‖ AES-128-CBC(plaintext) ‖ HMAC-SHA1` with **no algorithm/version tag**
   ([`MasterCipher`](../src/org/smssecure/smssecure/crypto/MasterCipher.java)). At read time there is
   no way to tell which algorithm produced a blob. `MasterCipher` encrypts message bodies, drafts,
   conversation-list data, and encrypted private keys across the whole database.

**Therefore: no algorithm may be swapped in place.** Every change must be *additive and versioned*,
keeping old readers alive and migrating lazily or via a one-time guarded pass.

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

### Phase A — Foundation: versioned `MasterCipher` format (PREREQUISITE)
No algorithm change yet — this phase only makes future changes safe.

- [ ] Introduce a 1-byte **format version prefix** on `MasterCipher` output.
  - `v0` (implicit, legacy): existing blobs with **no** prefix → read as AES-128-CBC + HMAC-SHA1.
  - `v1` (new): prefixed blobs → new primitives (Phases B/C).
- [ ] Reader auto-detects: if the leading byte is a known version tag → new path; otherwise treat as
      legacy `v0`. (Care: ensure the tag byte can't be confused with a legacy IV byte — use a length/
      sentinel scheme or a dedicated prefix.)
- [ ] All existing call sites continue to work unchanged (they go through `MasterCipher`).
- [ ] Unit tests: legacy blob round-trips as `v0`; new blob round-trips as `v1`; cross-version reads
      behave correctly.

### Phase B — Upgrade field crypto to AES-256 + HMAC-SHA256 (writes as `v1`)
- [ ] Generate **AES-256** encryption key + **HMAC-SHA256** mac key for *new* master secrets.
- [ ] For existing users: keep the existing 128-bit/SHA-1 keys usable for `v0` reads; introduce the
      stronger keys for `v1` writes (see Phase D for how the secret itself is migrated).
- [ ] `MasterCipher` `v1` path: `AES-256-CBC` (or AES-256-GCM, see note) + `HMAC-SHA256`.
- [ ] **Lazy re-encryption:** when a `v0` record is decrypted and rewritten, persist it as `v1`.
      Optional one-time background pass to migrate all rows.
- [ ] Tests: tamper detection (MAC), wrong-key rejection, v0→v1 upgrade-on-write.

> Note: prefer **AES-256-GCM** for `v1` to get authenticated encryption in one primitive. If staying
> with encrypt-then-MAC for minimal change, use AES-256-CBC + HMAC-SHA256. Decide in Phase B design.

### Phase C — Upgrade passphrase KDF to native Argon2id (no forward fallback)
- [ ] Add **Argon2id** (memory-hard) as the new passphrase KDF; store its parameters
      (memory, iterations, parallelism, salt) alongside the wrapped secret.
- [ ] Add a stored **`kdf_version`** field: `0` = legacy PBKDF1/SHA-1, `1` = Argon2id.
- [ ] Implement Argon2id by **vendoring the PHC reference C implementation**
      (`P-H-C/phc-winner-argon2`, CC0 / Apache-2.0) into a `cpp/` folder with a thin JNI wrapper,
      built via NDK **`externalNativeBuild`** (CMake). The app already ships native libraries
      (`libcurve25519.so`) under the same `abiFilters` (`armeabi-v7a`, `arm64-v8a`), so this adds a
      tiny (~tens of KB) `.so` and **excludes no additional devices**.
  - Rationale: pure-Java Argon2 is slow on mobile, which forces **lower memory parameters** within an
      acceptable unlock latency — and memory cost is exactly the GPU/ASIC-resistance property. Native
      speed lets us keep strong parameters. The algorithm is **frozen** (RFC 9106), so vendoring a
      pinned commit carries no real maintenance burden and avoids any third-party Gradle crypto dep.
  - On the ARM ABIs we ship, upstream uses the portable `ref.c` (its SIMD `opt.c` path is x86-only);
      native `ref.c` is still dramatically faster than pure-Java and fully realizes memory-hardness.
  - **Rejected alternatives:** BouncyCastle `Argon2BytesGenerator` (pure Java → the slow-defender /
      lowered-memory problem above); `org.signal:argon2` (**archived**); `argon2kt` (extra third-party
      native dep); whyoleg `cryptography-kotlin` (**no Argon2** — only PBKDF2/HKDF); libsodium /
      Lazysodium (larger native blob + JNA, a heavier third-party dependency).
- [ ] **No forward fallback.** Because the native `.so` is present on every device that can run the
      app (same constraint as the required `libcurve25519.so`), there is no API level or device at
      which Argon2id is "unavailable." Adding a weaker PBKDF2 fallback would only create an untested
      code path and a **KDF-downgrade surface**, so it is deliberately omitted. The versioned format
      (Phase A) keeps the option to add one later without breaking anything.
  - Note: the legacy **PBKDF1/SHA-1 reader** (`kdf_version = 0`) is **not** a fallback — it is the
      migration path for existing users (Phase D) and is retained until migration is provably
      complete.
- [ ] Tests: derive/verify under each `kdf_version`; parameter round-trip; JNI load smoke test.

### Phase D — Migrate the wrapped `MasterSecret` on next successful unlock
This is the critical, lockout-sensitive step.

- [ ] On unlock, decrypt the `MasterSecret` with the **stored** `kdf_version` (old PBKDF1 path for
      existing users).
- [ ] If `kdf_version == 0`: after a **successful** decrypt, re-wrap the secret with **Argon2id**
      (Phase C) and write `kdf_version = 1`. Only delete/overwrite the old blob **after** the new blob
      is confirmed written (write-new-then-swap, never swap-then-write).
- [ ] If migrating key sizes (128→256, Phase B): generate fresh stronger keys, re-encrypt existing
      field data lazily under `v1`, and keep `v0` readers until migration completes.
- [ ] **Never** remove the legacy KDF / legacy `MasterCipher` reader until telemetry/strategy confirms
      all records are migrated (likely several releases).
- [ ] Tests: simulate upgrade-in-place on a populated DB; interrupted-migration resumes safely; wrong
      passphrase never triggers destructive rewrite.

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
| A | None (no algorithm change) | Version byte + legacy reader |
| B | None | `v0` reader retained, lazy `v1` upgrade-on-write |
| C | None | `kdf_version` field, legacy PBKDF1 reader retained for migration; native Argon2id, no fallback |
| D | None *if* write-new-then-swap is honored | Migrate only after successful decrypt; never destructive on wrong passphrase |
| E | Threat-model/UX change (opt-in) | API/hardware gating + fallback |
| F (deferred) | Migration-or-bust | Resumable migration + backup + rollback |

**Hard rules:**
- Never swap an algorithm without a version tag and a retained legacy reader.
- Never overwrite the old wrapped secret before the new one is durably written.
- Never trigger a destructive rewrite on a failed/incorrect passphrase attempt.
- Keep legacy readers for several releases until migration is provably complete.

## 4. Testing strategy
- Unit tests per phase (round-trips, cross-version reads, tamper/MAC, wrong-key/passphrase).
- **Upgrade-in-place integration test:** start from a database written by the *current* app, upgrade,
  verify all messages/drafts/keys remain readable.
- **Interrupted-migration test:** kill mid-migration, relaunch, verify no data loss and resume.
- Native Argon2 JNI load test across shipped ABIs (`armeabi-v7a`, `arm64-v8a`); Argon2 known-answer
  vectors (RFC 9106); device-matrix check for Keystore/StrongBox behavior.

## 5. Suggested release mapping
- **Release N (current target):** Phase A + Phase B + Phase C + Phase D (the four key-store
  recommendations, shipped as one cohesive, migrating change).
- **Release N+1:** Phase E (hardware-backed wrapping), if desired.
- **Later release:** Phase F (SQLCipher), the deferred change.

## 6. Open questions
- [ ] Choose AES-256-**GCM** vs AES-256-CBC+HMAC-SHA256 for the `v1` field format.
- [x] Argon2 implementation: **vendored PHC reference C** (`P-H-C/phc-winner-argon2`, CC0/Apache-2.0)
      via NDK `externalNativeBuild` + JNI — native speed, frozen algorithm, no third-party crypto dep.
      Rejected: BouncyCastle (pure-Java → forces weak memory params), archived `org.signal:argon2`,
      `argon2kt`, whyoleg `cryptography-kotlin` (no Argon2), libsodium/Lazysodium (heavier + JNA).
- [x] Forward KDF fallback: **none** (native Argon2 is always present; avoids a downgrade surface).
      Legacy PBKDF1 reader retained for migration only. minSdk therefore stays **23** (no need for
      API-26 platform `PBKDF2withHmacSHA256`).
- [ ] Decide on lazy-only vs lazy + background one-time field migration for Phase B.
- [ ] Define the exact version-tagging scheme so a `v1` prefix can never alias a legacy IV byte.

> **Build baseline (already applied):** the app now targets **minSdk 23**, **compile/targetSdk 36**,
> **Java 17**, with **core library desugaring** enabled. This guarantees the Android Keystore AES
> APIs (23) and makes `java.time`/`Optional` available for new code. **minSdk stays 23** — native
> Argon2id works at any API level, and AES-256-GCM + HMAC-SHA256 are already native well below 23, so
> no bump (e.g. to 26 for platform `PBKDF2withHmacSHA256`) is needed.
>
> **New build requirement for Phase C:** add an **`externalNativeBuild`** (CMake) block to compile the
> vendored PHC Argon2 C sources in `cpp/` into `libargon2.so`. The app already uses the NDK
> (`abiFilters`, `jniLibs` for `libcurve25519.so`), so this is incremental — no new toolchain.
>
> **Note on `libcurve25519`:** the vendored transport library pins `curve25519-java 0.5.0` (May 2018);
> upstream `signalapp/curve25519-java` was **archived 2024-04-18** with no newer release. There is no
> update to adopt — a maintained Curve25519 only comes via the separate Rust-libsignal migration.
