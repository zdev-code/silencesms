# libsignal-client (Rust) — Session v3 Upgrade Plan

> Status: **PLANNING ONLY** — deferred to next major version.
> Goal: replace the vendored, EOL `libsignal-protocol-java` (2.7.1/2.8.1-era, frozen since 2019)
> with Signal's maintained `libsignal-client` (Rust core + JNI bindings), staying on
> **Signal Protocol session version 3** and **without enabling PQXDH**.

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
|---|---|---|
| Language | Pure Java (vendored source) | Rust core + Java/Kotlin JNI bindings (Kotlin stdlib now required) |
| Packaging | `.java` compiled into APK | Prebuilt AAR (`org.signal:libsignal-android`) bundling native `.so` per ABI |
| Namespace | `org.whispersystems.libsignal.*` | `org.signal.libsignal.protocol.*` (every crypto-layer import changes) |
| API surface | `SessionCipher`, `SessionBuilder`, `*Store` interfaces | Same shape + **mandatory `KyberPreKeyStore`** wired in, even for v3 |
| Session record format | `StorageProtos.SessionStructure` (protobuf) | Same protobuf lineage, exposed via `SessionRecord.deserialize(byte[])` |
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
  `SessionStructure` protobuf. `libsignal-client` uses its own record container. Existing records may
  not deserialize natively under the new library.
  - **Mitigation A (preferred):** write a one-time migration that reads each old `SessionRecord`
    protobuf and re-serializes it into the new library's record format (field-for-field; the
    underlying ratchet state is the same v3 math).
  - **Mitigation B (fallback):** if a session can't be migrated, **drop it**. The next inbound/outbound
    message triggers a fresh key exchange (a new PreKey handshake). Cost: a one-time re-key and a new
    safety number for that contact — **not** message loss.
- **Prekey / signed-prekey stores.** Re-load from existing storage into the new store interfaces;
  format is simple (key id + keypair), low risk.

### 3.3 Net user-visible effect
- Best case (Mitigation A works): **invisible** — no re-key, sessions continue.
- Worst case (Mitigation B): some conversations perform a **silent re-handshake** on next message and
  the contact's **safety number changes once**. No stored messages are lost.

## 4. Implementation phases

### Phase 0 — Spike / feasibility
- Add `libsignal-client` (Android AAR with native libs) to a throwaway branch.
- Confirm ABI coverage matches current `ndk { abiFilters 'armeabi-v7a','arm64-v8a' }`.
- Verify APK size delta is acceptable.

### Phase 1 — Store adapters
- Implement the new library's store interfaces (identity / prekey / signed-prekey / session) backed by
  the **existing** SQLCipher-free databases and `MasterCipher`-encrypted columns.
- Keep storage on disk byte-compatible where possible.

### Phase 2 — Cipher call-site migration
- Replace usages of the old `SessionCipher` / `SessionBuilder` in:
  - `src/org/smssecure/smssecure/crypto/SmsCipher.java`
  - `src/org/smssecure/smssecure/crypto/MmsCipher.java`
  - `src/org/smssecure/smssecure/crypto/SessionUtil.java`
  - `src/org/smssecure/smssecure/crypto/storage/*`
- Pin session version to 3; do not pass Kyber prekeys.

### Phase 3 — Session record migration
- One-time `SessionRecord` protobuf → new-format converter (Mitigation A), with Mitigation B fallback.
- Gate behind a stored schema/version flag so it runs once.

### Phase 4 — Remove vendored library
- Delete `libs/org.whispersystems.libsignal/` once all call sites compile against the new library.
- Update `settings.gradle` / `build.gradle` module references.

### Phase 5 — Validation
- Interop tests against an **old build** (send/receive both directions) to prove wire compatibility.
- Migration tests: upgrade-in-place over a populated database; verify sessions migrate or re-key cleanly.
- Verify stored messages remain readable throughout.

## 5. Rollback
- Keep the change behind a feature branch / staged rollout.
- Because the wire format is unchanged, a build reverting to the vendored library can still talk to
  upgraded peers; only locally re-keyed sessions differ.

## 6. Resolved open questions (researched 2026-06)

> Source: `signalapp/libsignal` repository and release history. Findings are accurate as of
> research date; re-confirm in the Phase 0 spike against the exact pinned version.

### 6.1 Which `libsignal-client` version exposes the classic (non-PQ) session API?
**Answer: there is no separate non-PQ version — use the latest stable `org.signal:libsignal-android`
(v0.96.x at time of research), and simply build PreKeyBundles _without_ a Kyber prekey.**

- The library no longer ships under `org.whispersystems`. It is published as the prebuilt AAR
  `org.signal:libsignal-android` (and `org.signal:libsignal-client` for plain JVM), on a rapid
  ~weekly **0.x** release cadence (v0.96.2 was latest when researched). There is **no** "classic"
  vs "PQ" build — **every** modern version supports **both**. Whether a session is classic **v3**
  or PQXDH is determined at runtime by **whether the `PreKeyBundle` carries a Kyber prekey**, not by
  the library version. Since Silence builds its own bundles, omitting the Kyber prekey keeps sessions
  at v3. This matches the Non-Goals in §1.
- **Three API realities to budget for (these enlarge Phase 2):**
  1. **Namespace rename** `org.whispersystems.libsignal.*` → `org.signal.libsignal.protocol.*`.
     Every import in `SmsCipher`, `MmsCipher`, `SessionUtil`, `crypto/storage/*`, and the
     `Curve`/`ECPrivateKey`/`ECPublicKey` references in `MasterCipher`/`MasterSecretUtil` changes.
  2. **`KyberPreKeyStore` is now part of the store surface.** `SessionCipher` /
     `SessionBuilder` / `decrypt(PreKeySignalMessage)` require a `KyberPreKeyStore` even for v3.
     We must implement a **no-op `KyberPreKeyStore`** (or aggregate it into our `SignalProtocolStore`).
  3. **Kotlin stdlib is now a transitive dependency** (the bindings use `kotlin.Pair`). Trivial for
     Gradle, but it is a new dependency on the classpath. The AAR also pulls
     `kotlinx-coroutines-android`.
- **minSdk / toolchain — RESOLVED.** The current `libsignal-android` `build.gradle`
  (`java/android/build.gradle` on `main`) declares **`minSdkVersion 23`**, `compileSdk 34`,
  **`ndkVersion 28.x`**, Java **17** source/target, and **`coreLibraryDesugaringEnabled = true`**.
  The app has been moved to match (**minSdk 23, Java 17, core library desugaring enabled**) — see §7.
  Note the AAR ships **unstripped** `.so` (`doNotStrip '**/*.so'`) and defers stripping to the app.

### 6.2 Can the new version ingest old `SessionStructure` protobufs directly?
**Answer: almost certainly yes — Mitigation A likely reduces to a byte pass-through — but it MUST be
proven empirically before relying on it.**

- Modern `SessionRecord` still exposes `SessionRecord.deserialize(byte[])` and `getSessionVersion()`,
  and the underlying record container is the **same protobuf lineage** (`RecordStructure` wrapping
  `SessionStructure`) carried forward from libsignal-protocol-java into the Rust `rust/protocol` crate.
  v3 sessions still report version 3.
- Protobuf is **field-number based**, so the `org.whispersystems` → `org.signal` Java package rename
  does **not** affect wire deserialization. The existing serialized bytes should load directly via
  `SessionStore.storeSession(... new SessionRecord(oldBytes))`.
- **Therefore the field-for-field converter (Phase 3 / Mitigation A) is probably unnecessary** — a
  straight pass-through of existing serialized records is expected to work.
- **Mandatory verification:** in Phase 0, deserialize a **real on-device session record** written by
  the current app under the new library. **Keep Mitigation B (drop session → re-handshake, no message
  loss) as the guaranteed fallback** if any record fails to load.

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

### 6.4 Residual items to confirm in the Phase 0 spike
- [x] minSdk floor identified: `libsignal-android` requires **minSdk 23** (app updated to 23).
- [ ] Pin an exact `org.signal:libsignal-android` version (record the chosen 0.x release).
- [ ] Empirically deserialize a current-app v3 `SessionRecord` under the new library (confirms §6.2).
- [ ] Measure real per-ABI `.so` size and resulting APK/App-Bundle delta (confirms §6.3).
- [ ] Confirm a no-op `KyberPreKeyStore` keeps sessions at v3 end-to-end (encrypt/decrypt round-trip).
- [ ] Add the **Kotlin Gradle plugin** (required by the AAR's Kotlin/coroutines bindings).

## 7. Build-target prerequisites (status)

These were prerequisites for adopting `libsignal-android` and have been **applied ahead of the swap**
(verified with `assembleDebug` → BUILD SUCCESSFUL):

| Setting | Before | Now | Reason |
|---|---|---|---|
| `minSdkVersion` | 21 | **23** | libsignal-android floor; ~99% device coverage retained |
| `targetSdkVersion` / `compileSdk` | 34 | **36** | Play compliance; AGP 8.13.0 accepts compileSdk 36 |
| Java source/target | 11 | **17** | libsignal requires Java 17 (max Android supports) |
| Core library desugaring | off | **on** (`desugar_jdk_libs:2.1.5`) | required by libsignal; enables `java.time`/`Optional` at minSdk 23 |
| Kotlin Gradle plugin | absent | **pending** | needed when the AAR is actually added (Phase 0) |

**Still outstanding from the targetSdk 36 bump:** an edge-to-edge / window-insets pass. A centralized,
opt-out-able inset handler now lives in `BaseActionBarActivity` (pads content with system-bar +
display-cutout insets; `MediaPreviewActivity` opts out). The deprecated `setStatusBarColor` /
`setNavigationBarColor` calls remain (harmless no-ops on API 35+, still functional below) and per-screen
visual QA on a device is recommended — especially the overlay action bar in `ConversationActivity` and
the bottom input panel.
