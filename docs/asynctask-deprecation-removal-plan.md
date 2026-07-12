# AsyncTask and Build Deprecation Removal Plan

## 1. Goal and baseline

Remove the `android.os.AsyncTask` warnings without changing task ordering, UI-thread delivery, cancellation behavior, or component lifetime semantics. Address the other actionable deprecations in a separate workstream and document APIs that must remain as guarded compatibility fallbacks.

Baseline command:

```powershell
.\gradlew.bat :compileDebugJavaWithJavac --rerun-tasks --warning-mode all
```

For inventory runs, add `-Xmaxwarns 10000` to `JavaCompile.options.compilerArgs`; the normal javac limit hides warnings after the first 100.

The 2026-07-12 uncapped baseline is:

- Build succeeds with 742 warnings: 731 deprecation and 11 unchecked warnings.
- `AsyncTask` accounts for 422 direct diagnostics. There are 53 `doInBackground()` implementations and 31 source files importing `android.os.AsyncTask`.
- `ProgressDialog`, which is coupled to several of those tasks, accounts for 53 additional diagnostics.
- Gradle reports one deprecated project dependency notation and three execution-time `Task.project` accesses. Both become errors in Gradle 10.
- `androidx.loader.content.AsyncTaskLoader` is a separate AndroidX type. Do not include `CountryListLoader` or `AbstractCursorLoader` in the `android.os.AsyncTask` removal merely because their names contain `AsyncTask`.

## 2. Replacement contract

Add a small app-owned concurrency utility instead of reproducing `AsyncTask` at every call site:

- A single-thread executor for calls currently using `execute()`. This preserves `AsyncTask`'s serial execution semantics and ordering.
- A bounded parallel executor for calls currently using `executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, ...)`.
- A main-thread `Handler` constructed with `Looper.getMainLooper()` for UI completion and progress callbacks.
- A returned `Future<?>` (or a small task handle wrapping it) so owners can cancel queued/running work and suppress callbacks after destruction.
- A result callback and an error callback. Background exceptions must be logged or surfaced; the helper must not silently lose them.
- Executors must be process-scoped and named. Do not create an executor per click, row, receiver, or task invocation.

Before converting each call site, record whether it currently uses serial or parallel execution, whether it publishes progress, and whether its completion mutates UI. Preserve those choices deliberately.

Do not build a generic class with `onPreExecute()`, `doInBackground()`, and `onPostExecute()` methods. That would retain the lifecycle problems of `AsyncTask` under a different name. Keep background work as a `Callable`/`Runnable`, and keep UI setup and result handling in the owning component.

## 3. AsyncTask migration phases

### Phase A: Infrastructure and focused tests

1. Remove the unused `AsyncTask` imports from `ContactSelectionActivity`, `ContactSelectionListFragment`, and `providers/SingleUseBlobProvider`; these files have no task to migrate.
2. Add the serial executor, parallel executor, main-thread dispatcher, and cancellable task handle under `util` or `util.concurrent`.
3. Unit-test serial ordering, parallel dispatch, success delivery on the main dispatcher, exception delivery, cancellation, and suppression of late callbacks.
4. Add a build check that fails when `import android.os.AsyncTask` or `extends AsyncTask` is added under `src/`.
5. Keep the existing `-Xlint:deprecation` build configuration enabled throughout the migration.

Exit gate: utility tests pass and one low-risk call site proves the API without introducing a new lifecycle reference.

### Phase B: BroadcastReceiver and service work

Migrate these first because their required lifetime behavior is explicit:

- `notifications/AndroidAutoHeardReceiver`
- `notifications/AndroidAutoReplyReceiver`
- `notifications/DeleteNotificationReceiver`
- `notifications/MarkReadReceiver`
- `notifications/RemoteReplyReceiver`
- `service/KeyCachingService`

For every receiver, call `goAsync()`, submit work with the application context, and invoke `PendingResult.finish()` in a `finally` block. A bare executor submission from `onReceive()` is not sufficient because Android may reclaim the process after `onReceive()` returns. Keep receiver work short; move work that can exceed the broadcast allowance to an existing durable job/service mechanism.

For `KeyCachingService`, use a service-owned executor, stop or detach it in the correct lifecycle callback, and retain the existing handling for background service start restrictions.

Exit gate: notification read/delete/reply/heard flows work on device with the app foregrounded and backgrounded; no receiver holds an `Activity` context.

### Phase C: Simple UI and model tasks

Convert tasks that return one result or perform a short database mutation:

- `ConversationActivity`
- `ConversationFragment`
- `ConversationItem`
- `ConversationListActivity`
- `ConversationListFragment`
- `MessageDetailsActivity`
- `MessageRecipientListItem`
- `mms/AttachmentManager`
- `preferences/MmsPreferencesFragment`
- `preferences/NotificationsPreferenceFragment`
- `ReceiveKeyDialog`
- `RecipientPreferenceActivity`
- `ShareActivity`
- `preferences/widgets/RingtonePreferenceDialogFragmentCompat`

At each completion callback, verify that the owner is still valid before touching views, dialogs, fragment state, or an activity. Capture immutable inputs and application contexts in background lambdas; do not capture an activity, fragment, adapter row, or mutable field unless the task is cancelled when that owner stops. For recycled rows and repeated queries, retain an input token and ignore stale results.

Split `ConversationActivity` into small named methods while migrating its 14 task bodies. Convert and test one behavior group at a time: drafts/preferences, message/database mutations, send flows, then UI refreshes. This keeps regressions attributable.

Exit gate: conversation send, resend, delete, draft, attachment, recipient preference, share, and ringtone installation flows pass targeted manual checks; rotation or navigation during work does not crash or update a dead view.

### Phase D: Shared UI helpers and progress

Remove the inheritance-based helpers:

- Replace `util/task/ProgressDialogAsyncTask` with composition: the owner shows progress, submits work, and dismisses progress only while still attached.
- Replace `util/task/SnackbarAsyncTask` with an operation object containing forward and reverse actions. Both actions run on the executor; snackbar display remains on the main thread.
- Convert `SaveAttachmentTask` to a plain attachment-saving operation with an explicit result callback.
- Convert `Trimmer.TrimmingProgressTask`; dispatch `ThreadDatabase.ProgressListener` updates to the main thread and guard division by zero.
- Update callers in `ConversationFragment`, `ConversationListFragment`, `MediaOverviewActivity`, `MediaPreviewActivity`, and `ApplicationPreferencesActivity`.

Replace `ProgressDialog` with an `AlertDialog` containing an indeterminate or determinate progress view, following the already-modernized pattern in `libpastelog/SubmitLogFragment`. Dialog ownership belongs to the activity/fragment, not the background operation.

Exit gate: save attachment, delete/undo, archive/undo, and trim progress behave as before; all `ProgressDialog` and shared task-helper warnings are gone.

### Phase E: Sensitive and long-running operations

Migrate these only after the simpler patterns are proven:

- `DatabaseUpgradeActivity`
- `ImportExportFragment`
- `PassphraseChangeActivity`
- `PassphraseCreateActivity`

Use a retained operation controller or service-level owner that survives configuration changes and exposes state/progress to the current screen. Do not retain an activity or fragment in the operation. Reattach the new screen to in-flight state and ensure exactly one completion action is delivered.

Database upgrades and import/export must define interruption behavior before implementation: which writes are transactional, whether retry is safe, and what the UI shows after process death. If an operation can exceed a normal foreground component lifetime, run it through a foreground service or the existing durable job infrastructure rather than a process-only executor. Passphrase operations must not persist plaintext passphrases in work requests, bundles, saved state, or disk-backed queues; clear temporary character/byte buffers where practical.

Exit gate: rotation, back navigation, cancellation, process recreation, success, and I/O failure are exercised for each flow. Upgrade and backup operations cannot run twice from one user action.

### Phase F: Cleanup and complete verification

1. Remove all `android.os.AsyncTask` imports, subclasses, `THREAD_POOL_EXECUTOR` references, and obsolete lint suppressions.
2. Remove `ProgressDialogAsyncTask`, `SnackbarAsyncTask`, and `ProgressDialog` after their final callers are migrated.
3. Run:

```powershell
.\gradlew.bat clean :assembleDebug :assembleRelease --warning-mode all
.\gradlew.bat testDebugUnitTest
```

4. Repeat an uncapped warning inventory and compare it with the baseline.
5. Run device checks for conversations, notification actions, attachments, backup/restore, database upgrade, passphrase create/change, trimming, and configuration changes during each operation.

AsyncTask exit criteria:

- No `android.os.AsyncTask` references under `src/`.
- No `ProgressDialog` references under `src/`.
- No AsyncTask or ProgressDialog javac deprecation warnings.
- Serial call sites remain serial; explicitly parallel call sites remain bounded and parallel.
- No late callback updates a stopped/destroyed component.

## 4. Other actionable deprecations

Implement these as separate, reviewable changes after the AsyncTask infrastructure is stable. Re-run the uncapped inventory after every group because removing high-volume warnings exposes the remaining list more clearly.

The detailed non-Gradle Android/Java API work is maintained in `android-api-deprecation-removal-plan.md`. The summary below remains as the ordering overview; use the standalone plan for implementation and acceptance gates.

### Group 1: Gradle 10 blockers

- Change root dependencies from `implementation project(':module')` to `implementation(project(':module'))`.
- In `verifyLibsignalPin`, capture the coordinate, checksum, dependency handler/configuration objects, and logger during configuration. Do not access `project`, `dependencies`, or `configurations` through the task inside `doLast`.
- Validate with `help` and `assembleDebug` under `--warning-mode all`, then test configuration-cache compatibility.

These are small and should be fixed before a Gradle 10 upgrade.

### Group 2: Direct compatibility replacements

- Use `IntentCompat`/`BundleCompat` typed parcelable accessors and typed serializable accessors.
- Use `ContextCompat.getColor`, direct `MenuItem` APIs, `PackageInfoCompat.getLongVersionCode`, `DrawableCompat` tint APIs, explicit `Handler(Looper.getMainLooper())`, `URLDecoder.decode(value, UTF_8)`, and `getDeclaredConstructor().newInstance()`.
- Replace deprecated `ViewCompat` X/Y wrappers with direct `View` methods.
- Since minSdk is 23, always call `SmsMessage.createFromPdu(byte[], format)` rather than the pre-23 overload.
- Replace old notification priority/person APIs with current `NotificationCompat` APIs where behavior is equivalent.

These changes are low risk but still need focused tests around parcelable/serializable type mismatches and notification rendering.

### Group 3: Fragment, activity-result, and loader lifecycle

- Move `onActivityCreated()` initialization to `onViewCreated()` and replace `onAttach(Activity)` with `onAttach(Context)`.
- Replace `startActivityForResult`, fragment `onActivityResult`, and permission callbacks with Activity Result contracts.
- Replace deprecated fragment loader-manager entry points with `LoaderManager.getInstance(owner)` as an interim step. Then evaluate replacing loaders with repository/executor queries so loader deprecations are not merely moved.
- Replace `setHasOptionsMenu()` with `MenuProvider`/`MenuHost` where the screen uses fragment menus.
- Replace `onBackPressed()` overrides with `OnBackPressedDispatcher` callbacks.

Test fragment recreation, permission denial/grant, QR scanning, contact/document selection, and restored process state.

### Group 4: Platform UI and media APIs

- Route status/navigation bar appearance through the existing edge-to-edge/insets implementation rather than calling deprecated window color and fullscreen flags from individual screens.
- Replace display width/height/metrics calls with `WindowMetrics` on API 30+ and a localized, suppressed compatibility fallback on API 23-29.
- Migrate the custom platform `RingtonePreference` implementation to AndroidX `Preference`/`DialogPreference`; use current ringtone audio attributes instead of deprecated stream APIs.
- Replace `ProgressDialog` as part of AsyncTask Phase D.
- Migrate ZXing's deprecated `IntentIntegrator` flow to `ScanContract` and the Activity Result API.
- Replace deprecated activity transition calls with `ActivityOptionsCompat` or current override APIs where the same animation can be preserved.

### Group 5: Services, connectivity, and telephony

- Replace `IntentService` subclasses with explicit executor-backed services or the existing durable job system; preserve master-secret availability and redelivery semantics.
- Replace `stopForeground(boolean)` with `ServiceCompat.stopForeground` and explicit flags.
- Replace general connectivity checks with `ConnectivityManager` callbacks and `NetworkCapabilities`.
- On API 31+, replace `PhoneStateListener` with `TelephonyCallback`; retain one localized API 23-30 fallback with a documented suppression.
- On API 29+, use `SubscriptionInfo.getMccString()`/`getMncString()` and retain guarded numeric fallbacks for API 23-28.
- Replace deprecated platform phone-number formatting/comparison with the already-included libphonenumber utilities where parity tests pass.

Test no-network, Wi-Fi, mobile data, roaming, dual-SIM, SIM changes, MMS transport, and service restart behavior on the oldest and newest supported Android versions.

## 5. Notes for deprecations that cannot yet be fully removed

The following should not be replaced mechanically. Keep each fallback in one compatibility helper, annotate only the smallest method or statement with `@SuppressWarnings("deprecation")`, and include a comment naming the supported API range and removal condition.

- MMS network routing: `TYPE_MOBILE_MMS`, `getNetworkInfo(int)`, and parts of `NetworkInfo` are tied to the legacy carrier-MMSC route in `MmsRadio`. `NetworkCapabilities` should replace general connectivity checks, but it does not provide a drop-in replacement for every legacy MMS APN/network detail. Remove these only after device tests prove explicit MMS-network acquisition and proxy routing across supported carriers.
- Line/phone number access: `TelephonyManager.getLine1Number()`, `SubscriptionInfo.getNumber()`, and newer phone-number APIs vary by API level, carrier provisioning, role, and permission. Use the newest available API where possible, retain a guarded fallback, and treat a missing number as normal rather than inventing one.
- Telephony callbacks and subscription MCC/MNC: the modern APIs do not cover API 23-30/28 respectively. Guard the modern path and retain localized deprecated fallbacks until minSdk rises above those ranges.
- Window metrics: API 23-29 requires a deprecated display fallback unless the affected layout can be rewritten to avoid querying display dimensions. Prefer eliminating the query; otherwise localize and suppress it.
- `Locale(String, String)`: `Locale.of()` is not available across the full Android API range, and `Locale.Builder` can normalize malformed legacy values differently. Keep the constructor in `DynamicLanguage` until language persistence is normalized and migration tests prove equivalent locale restoration.
- Android Auto `CarExtender.UnreadConversation`: there is no direct one-for-one replacement for the legacy unread-conversation payload. First verify current Android Auto behavior with `MessagingStyle` and actions; remove the legacy extender only after reply/read behavior is preserved on device.
- Status/navigation bar color setters: these are ignored or constrained by enforced edge-to-edge on newer targets but remain meaningful on older supported Android versions. Centralize them in the existing compatibility layer and suppress only the old-API branch until the minimum supported version makes it removable.

Generated protobuf `PARSER` fields under the vendored libsignal module are deprecated generated API, not app call-site warnings. Do not hand-edit generated files. They disappear only when the protobuf sources/toolchain are regenerated or the vendored module is removed under the existing libsignal migration plan.

The 11 unchecked warnings are not deprecations and are outside this plan. Track them separately after the deprecation inventory is under control; do not hide them with a project-wide suppression.