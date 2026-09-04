# Frontend Worker Log

## [conversation-host-migration] Verified unsafe migration boundary

- The mandatory popup directly subclasses `ConversationActivity`; conversation UI cannot move without extracting a shared controller.
- Share and SENDTO still transfer plaintext or URI-bearing payloads to the generation-gated Activity owner; no secure app-owned handoff exists.
- Message details cannot move independently because bringing the `singleTask` host forward clears the conversation and its return path.
- The correct outcome was to preserve all dirty generation-bound changes, retain both Activities, and add executable/documented gate evidence.
- Learnings consumed: [(none)]

## [conversation-host-migration] Completed authoritative host cutover

- Extracting `ConversationScreenFragment` allowed normal and popup surfaces to share one controller without changing popup window semantics.
- Strict typed route arguments carry only recipient/thread primitives and an opaque one-shot payload token; sensitive share and SENDTO content remains in the generation-bound store.
- Replacing an already-visible conversation in place preserves the former `singleTask` behavior without stacking duplicate destinations.
- Selector flows must pop before navigating, and normal-host chrome must be restored when leaving the conversation destination.
- `MessageDetailsActivity` remains retained because its secure result flow is independent of normal conversation ownership.
- Learnings consumed: [frontend/conversation-host-boundaries]

## [message-details-host-migration] Completed message-details destination cutover

- `MessageDetailsFragment` now owns generation-gated loading and sensitive row cleanup under the normal Navigation host.
- Strict route arguments contain only message, thread, recipient IDs and an integer transport discriminator.
- Failed-row taps were moved from an Activity launch inside `ConversationItem` to the existing child-to-parent Fragment listener.
- Identity verification remains a one-way Activity launch from `ReceiveKeyDialog`; no Activity result returns to the Fragment.
- Relock dismisses any open conflict dialog and clears bound records and the in-memory secret before host routing.
- The patch deletion backend falsely reported source deletion three times; an explicit path removal plus existence check was required.
- Learnings consumed: [frontend/conversation-host-boundaries]

## [prompt-mms-import-export-host-migration] Completed modal and document-flow cutover

- Both MMS prompt callers now request one zero-argument host-owned DialogFragment; the host alone navigates to MMS preferences.
- Import/export launchers retain only unlock generations and wipeable recovery bytes; secrets resolve inside protected operation invocations.
- Relock clears pending result generations, dialogs, keys, ViewModel work, and late completions before authentication routing.
- The patch deletion backend again retained source paths after repeated delete calls; emptying through apply_patch before filesystem removal worked.
- Learnings consumed: [frontend/conversation-host-boundaries]

## [identity-recipient-settings-host-migration] Completed identity and recipient-settings cutover

- Local identity and normal verification now use Fragment-owned, generation-bound operation access.
- Scan results are one-shot and bound to recipient, subscription, identity bytes, and unlock generation.
- Recipient preference destinations retain only IDs and resolve recipients per render/action.
- `VerifyIdentityActivity` remains a thin bridge for uncommitted `ReceiveKeyDialog` identities.
- `PassphraseChangeActivity` remains because immutable passphrase Strings cross a non-generation-bound async boundary.
- The patch deletion backend falsely reported success until direct path removal verified both legacy files absent.
- Learnings consumed: [frontend/conversation-host-boundaries, frontend/generation-bound-document-results, frontend/message-details-host-boundary]

## [media-host-migration] Completed media destination cutover

- Media overview and normal preview now use strict primitive-only host routes and destination-scoped generation state.
- Draft URIs use a consume-once, generation-bound, 60-second memory handoff and never enter route or saved state.
- `MediaOverviewActivity` was removed; `MediaPreviewActivity` remains only as a private popup-window Fragment bridge.
- External viewers now read through a generation-checked pipe instead of a decrypted temp file; relock terminates streaming and revokes the URI grant.
- Draft validation initially omitted `part_unique_id`; strict route tests exposed and corrected the gap.
- The patch deletion backend concatenated replacement content until direct path removal verified absence before recreation.
- Learnings consumed: [frontend/conversation-host-boundaries, frontend/generation-bound-document-results, frontend/message-details-host-boundary]

## [final-normal-flow-migration] Completed selector and dead-country cutover

- Share and SENDTO now route an opaque generation-bound payload token to the host new-conversation selector; recipient selection atomically rebinds the payload without exposing content or addresses.
- Group contact selection returns only validated positive recipient IDs through a one-shot FragmentResult while the group ViewModel remains on the back stack.
- Relock synchronously clears selector query text, adapters, selections, callbacks, group members, and payload entries; application startup removes prior-process capture blobs.
- Country selection had no active caller, so its complete UI/ViewModel/helper/resource chain and obsolete ContactSelectionActivity base were removed.
- The Windows patch backend falsely reported deletions; exact direct removal plus path verification was required again.
- Learnings consumed: [frontend/conversation-host-boundaries, frontend/generation-bound-document-results, frontend/identity-recipient-host-boundaries]

## [authentication-bootstrap-continuation] Completed typed memory-only bootstrap handoff

- Seven concrete protected Activity targets are explicitly allowlisted and reconstructed from bounded target-specific fields.
- Continuations created while unlocked bind to that generation; locked welcome/passphrase chains remain unbound until the protected target reevaluates policy.
- Database upgrade and SMS import resolve secrets through `UnlockSession` at operation time; no Activity or service Intent carries `MasterSecret`.
- Cold, expired, duplicate, wrong-owner, and stale-generation tokens route to a fresh private inbox policy evaluation.
- Learnings consumed: [frontend/conversation-host-boundaries, frontend/generation-bound-document-results, frontend/identity-recipient-host-boundaries, frontend/media-host-boundary]

## [passphrase-pipeline-hardening] Completed wipeable generation-bound passphrase replacement

- `Editable` values are copied directly into closeable character buffers; mismatch and empty validation never dispatch.
- A controller owns cancellation, replacement, operation arrays, and late-callback suppression while `UnlockSession` guards start and activation boundaries.
- Legacy PBKDF specs/encoded keys and Argon2 UTF-8/derived keys are wiped without changing KDF or envelope parameters.
- Java NFC normalization still requires one method-local immutable normalized `String`; mutable copies around it are wiped.
- The private passphrase Activity remains retained; no secret enters intents, navigation, or saved state.
- Learnings consumed: [frontend/bootstrap-continuation-boundary, frontend/generation-bound-document-results]

## [exported-router-minimization] Completed transparent launcher/share/SENDTO shells

- The launcher now enforces the exact data-free MAIN/LAUNCHER contract and clears its source Intent.
- Share and SENDTO validate bounded external fields, retain no protected UI or secret, issue one opaque payload token, and finish immediately.
- Locked payload entries bind to the first authenticated host generation; external media is encrypted and cleanup ownership transfers atomically inside the private selector.
- Direct-share thread metadata and normalized SENDTO recipient IDs remain memory-only and never enter private-host Intents.
- Removing obsolete share bootstrap restoration prevents plaintext and URI continuation replay through authentication gates.
- Android lint can race release generated Glide sources when lint and all assemblies share one Gradle invocation; standalone runs pass.
- Learnings consumed: [frontend/bootstrap-continuation-boundary, frontend/conversation-host-boundaries, frontend/media-host-boundary, frontend/selector-payload-host-boundary]

## [conflict-identity-host-migration] Removed the conflict identity Activity bridge

- Conflict identities now remain in a 60-second, owner-bound, unlock-generation-bound, consume-once memory store; private-host navigation carries only an opaque token.
- Normal verification retains strict recipient/subscription ID routing, while token and ID argument shapes are mutually exclusive and reject unexpected keys and types.
- Fragment recreation and process death intentionally fail closed rather than persisting identity material; relock clears both live Fragment state and every store entry synchronously.
- `VerifyIdentityActivity`, `IdentityKeyParcelable`, their manifest/bootstrap/retained-policy paths, and all references were removed.
- `BundleCompat` typed reads avoid introducing new raw-`Bundle.get()` deprecation warnings in the strict Fragment argument validator.
- Learnings consumed: [frontend/bootstrap-continuation-boundary, frontend/conversation-host-boundaries, frontend/identity-recipient-host-boundaries, frontend/media-host-boundary]

## [popup-media-preview-host-migration] Removed the popup media Activity bridge

- `ConversationActivity` now owns one local preview back-stack entry, so the retained popup task/window never switches Activities for persisted or draft preview.
- Active-Fragment checks delegate preview menus and prevent popup compose focus while media is visible; Back restores conversation chrome and focus.
- Draft URI/content type remains consume-once and generation-bound in memory; a new store, stale generation, expiry, relock, or duplicate consume fails closed.
- Relock clears preview ViewModel/media/dialog state, draft entries, and conversation state synchronously before authentication routing.
- The Windows patch backend again required emptying the dirty source through `apply_patch` before removing the obsolete file path.
- Learnings consumed: [frontend/conversation-host-boundaries, frontend/generation-bound-document-results, frontend/media-host-boundary]

## [authentication-welcome-host] Migrated WELCOME into the authentication-only host

- `AuthenticationActivity` accepts only WELCOME plus the continuation destination/token and hosts only `WelcomeFragment`; it has no application graph or protected Fragment references.
- Welcome permission arrays, dialogs, first-run flags, status color, transitions, and boot-time permission notification semantics remain unchanged.
- Cold, malformed, expired, duplicate, or missing continuations fall back to private `ConversationListActivity` for fresh bootstrap policy evaluation.
- Passphrase create/prompt and database upgrade/migration remain separate Activities by design.
- The Windows patch backend reported source deletion success twice while retaining the path; direct path removal plus an absence check was required.
- Learnings consumed: [frontend/bootstrap-continuation-boundary]

## [authentication-create-passphrase-host] Migrated CREATE_PASSPHRASE into the authentication-only host

- `AuthenticationActivity` now validates and restores exactly WELCOME or CREATE_PASSPHRASE with only the existing destination and opaque continuation token.
- A Fragment-owned controller preserves the legacy generation sequence while owning cancellation, retry, and callback generations.
- The completion coordinator rechecks owner, Activity, and cache identity before service activation and discards only its exact pending prime on cancellation.
- The legacy create Activity source, manifest declaration, routing, and retained inventory row are removed; prompt, upgrade, migration, and change Activities remain.
- The Windows patch backend again required emptying the obsolete source through `apply_patch`, followed by direct zero-content path removal and verification.
- Learnings consumed: [frontend/authentication-only-host-boundary, frontend/bootstrap-continuation-boundary, frontend/wipeable-generation-bound-passphrase, frontend/generation-bound-document-results]

## [authentication-prompt-passphrase-host] Migrated PROMPT_PASSPHRASE into the authentication-only host

- Surface-qualified in-memory continuation ownership prevents a token issued for WELCOME or CREATE from being replayed by changing only the surface extra.
- Prompt input now moves directly from `Editable` to `WipeablePassphrase`; controller-owned worker arrays are zeroed on every completion, cancellation, replacement, teardown, and stale-callback path.
- Dynamic intro theme/language, delayed progress, automatic unlock, IME/button handling, invalid-passphrase feedback, and locked log submission moved into the Fragment/host boundary.
- `PassphraseActivity` remains for `PassphraseChangeActivity`; only the obsolete prompt subclass and its prompt-only base branch were removed.
- The Windows patch backend again falsely reported source deletion twice; exact path removal and absence verification were required.
- Learnings consumed: [frontend/authentication-only-host-boundary, frontend/authentication-completion-pending-prime, frontend/bootstrap-continuation-boundary, frontend/wipeable-generation-bound-passphrase]

## [authentication-database-upgrade-host] Migrated UPGRADE_DATABASE into the authentication-only host

- A host-independent policy now owns the exact 143/200/216 thresholds and removes Activity dependencies from database, coordinator, and key-cache code.
- The Fragment reattaches to the singleton persisted coordinator, disables progress widget state saving, and rejects queued callbacks after lifecycle/surface replacement.
- Immediate no-upgrade finalization must run from `onResume`; claiming it during Fragment `onStart` precedes the host's STARTED navigation guard and loses the continuation.
- Database migration and passphrase change remain separate; the obsolete upgrade Activity source, manifest entry, and retained inventory row were removed.
- The Windows patch backend again required direct path removal after two successful-but-ineffective `apply_patch` deletion reports.
- Learnings consumed: [frontend/authentication-only-host-boundary, frontend/authentication-surface-qualified-continuations, frontend/bootstrap-continuation-boundary]

## [authentication-database-migration-host] Migrated DATABASE_MIGRATION into the authentication-only host

- `ApplicationMigrationService` remains the durable import and progress owner; the Fragment controller only binds, observes, starts/skips, and claims navigation.
- Nested upgrade-to-migration continuations require a live exact-surface token already owned by `AuthenticationActivity:DATABASE_MIGRATION`.
- Durable imported state closes the lifecycle gap where completion occurs while stopped and the service has already terminated before rebind.
- Handler work, receiver registration, service handlers, and binding references are paired and generation-guarded under Fragment lifecycle.
- The Windows patch backend again required direct path removal after two successful-but-ineffective `apply_patch` deletion reports.
- Learnings consumed: [frontend/authentication-only-host-boundary, frontend/authentication-surface-qualified-continuations, frontend/authentication-upgrade-restartable-coordinator, frontend/bootstrap-continuation-boundary]

## [authentication-change-passphrase-host] Migrated unlocked CHANGE_PASSPHRASE into the authentication-only host

- The exact private app-protection return route is owner-, expiry-, consume-once-, and current-generation-bound; locked creation and process loss fail closed.
- Guarded wrapper activation stays on the old generation, while successful lifecycle-checked cache establishment requires an exact route rebind to the newly cached generation before one-shot return.
- Fragment teardown, Back, cancel, and relock close controller/cache work and wipe fields; storage failure preserves retry behavior and the old wrapper remains authoritative.
- `PassphraseChangeActivity`, its now-unused `PassphraseActivity` base, manifest declaration, and retained-inventory row are removed.
- The Windows patch backend again required direct path removal after two successful-but-ineffective `apply_patch` deletion reports.
- Learnings consumed: [frontend/authentication-only-host-boundary, frontend/authentication-surface-qualified-continuations, frontend/wipeable-generation-bound-passphrase, frontend/authentication-completion-pending-prime, frontend/bootstrap-continuation-boundary]
- Learning written: [frontend/authentication-change-generation-transition]
