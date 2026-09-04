# Media Host Migration

## Summary

Media overview and normal persisted/draft preview are now owned by the authenticated
`ConversationListActivity` Navigation host. Routes contain only validated positive primitive IDs,
mode, date, and size. Draft URIs remain in a consume-once, generation-bound, expiring memory store.

Both obsolete media Activities are removed from source, manifest, bootstrap continuation policy, and
retained inventory. Popup preview remains inside the retained `ConversationPopupActivity` task: the
shared `ConversationActivity` host replaces its conversation Fragment with `MediaPreviewFragment` on
a single local back-stack entry, then restores popup chrome, menu, compose focus, and conversation
state on Back.

## Security Evidence

- Media repository and ViewModel work resolves `ConversationUnlockCapability` at operation time.
- Loads and saves cancel on relock, clear projections synchronously, and suppress stale-generation
  callbacks.
- Destination contracts reject missing/non-positive IDs, wrong primitive types, URI or Bundle
  payloads, unknown keys, and database identifiers in draft routes.
- External viewer grants are destination-owned and revoked on result, teardown, or relock.
- `PartProvider` uses generation-checked pipe streaming, writes no decrypted temp file, and terminates
  an open stream when the captured unlock generation becomes stale.
- Normal Back and Up behavior is Navigation-owned. Popup Back pops the local preview entry;
  unsupported, stale, expired, and cold-process draft payloads use the same fail-closed callback.
- Preview overview explicitly opens the private main host with primitive thread/recipient IDs and
  closes the popup. Forward/save and external viewer grants retain their existing destination-owned
  generation and revocation contracts.

## Popup-Local Gate

- `ConversationPopupActivity` remains retained as a `DISTINCT_WINDOW`; its affinity, dimensions,
  gravity, transitions, notification reply, expand-to-main, and send-completion behavior are unchanged.
- Relock synchronously clears `MediaPreviewFragment`, its ViewModel/media/dialog state, the draft
  store, and `ConversationScreenFragment` before authentication routing.

## Upstream Artifacts Consumed

- none - no dependency artifacts provided

## Evidence Mapping

- none - no dependency artifacts provided

## Test Results

- Command: `./gradlew.bat :app:testDebugUnitTest --tests org.smssecure.smssecure.RetainedActivityContractTest --tests org.smssecure.smssecure.ConversationListDestinationTest --tests org.smssecure.smssecure.domain.media.MediaPreviewDraftStoreTest --tests org.smssecure.smssecure.ui.mediapreview.MediaPreviewViewModelTest`
- Passed: 38
- Failed: 0
- Skipped: 0
- Command: `./gradlew.bat :app:checkModernArchitectureBoundaries :app:checkHostDestinationSecurityPolicy :app:checkRetainedActivityInventory :app:checkNoNewAsyncTaskUsage`
- Result: passed all 4 policy gates
- Command: `./gradlew.bat :app:test`
- Passed: 354
- Failed: 0
- Skipped: 0
- Command: `./gradlew.bat :app:lint`
- Result: passed with 42 warnings and no unbaselined errors; 35 baseline entries were not found and were left unchanged
- Command: `./gradlew.bat :app:verifyCryptoReleaseStages :app:assemblePhaseARelease :app:assembleRelease :app:assembleDebug`
- Result: passed; both staged release artifacts and the debug APK assembled
- Connected tests: not run, as required
