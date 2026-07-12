# Android and Java API Deprecation Removal Plan

## 1. Scope and baseline

This plan covers javac deprecation warnings from Android framework, AndroidX, Java, and third-party APIs. It excludes:

- `android.os.AsyncTask` and `ProgressDialog`, which are owned by `asynctask-deprecation-removal-plan.md`.
- Gradle DSL and task deprecations, including the Gradle 10 blockers.
- The 11 unchecked javac warnings, which are not deprecations.
- Deprecated declarations in generated protobuf sources. Generated code must be regenerated or removed, not hand-edited.

The uncapped 2026-07-12 baseline contains 731 javac deprecation diagnostics. Removing the 422 `AsyncTask` and 53 `ProgressDialog` diagnostics leaves **256 non-Gradle API deprecation diagnostics** in this plan.

Inventory command:

```powershell
.\gradlew.bat :compileDebugJavaWithJavac --rerun-tasks --warning-mode all
```

For inventory runs, temporarily add `-Xmaxwarns 10000` to `JavaCompile.options.compilerArgs`; javac otherwise stops reporting after 100 warnings. Keep `-Xlint:deprecation` enabled.

## 2. Rules for the migration

1. Preserve behavior before pursuing a zero-warning number. API replacements must retain minSdk 23 support, permission behavior, dual-SIM routing, MMS carrier routing, notification actions, and restored fragment state.
2. Prefer AndroidX compatibility APIs when they provide one implementation across supported versions.
3. When no full-range replacement exists, use the modern API on supported versions and isolate the deprecated fallback in one compatibility helper.
4. Suppress deprecation warnings only on the smallest fallback method or statement. Every suppression must state the API range and the condition under which it can be removed.
5. Do not add project-wide `-Xlint:-deprecation`, broad `@SuppressWarnings("deprecation")`, reflection, or a lower compile SDK to hide warnings.
6. Make each phase a separate reviewable change. Re-run the uncapped inventory after every phase and record the warning-count delta.

## 3. Phase A: Mechanical compatibility replacements

Start with replacements that do not change component ownership or asynchronous behavior.

### Typed extras and saved state

Replace deprecated untyped accessors:

- `Intent.getParcelableExtra(String)`
- `Bundle.getParcelable(String)`
- `Bundle.getSerializable(String)`
- `BaseBundle.get(String)` where the expected type is known

Use `IntentCompat`, `BundleCompat`, or API-gated typed framework overloads. Keep type validation explicit and preserve null behavior. Update all producers and consumers together where an extra's declared type is inconsistent.

Validation:

- Exercise conversation/list state restoration, media/share intents, identity scanning, and document selection.
- Add tests for absent extras, wrong-type extras, and process-restored bundles.

### Resources, drawables, and views

- Replace `Resources.getColor(int)` with `ContextCompat.getColor()` or `ResourcesCompat.getColor()` as appropriate.
- Replace `Drawable.setColorFilter(int, Mode)` with `DrawableCompat.setTint()`/`setTintMode()` while preserving drawable mutation behavior.
- Replace deprecated `ViewCompat.getX/getY/setX/setY` calls with direct `View` methods.
- Replace `View.setBackgroundDrawable()` with `View.setBackground()`; minSdk 23 needs no fallback.

Validation: compare selected, unread, archived, and attachment tint states in light and dark themes, including recycled list rows.

### Java and package APIs

- Replace `new Handler()` with `new Handler(Looper.getMainLooper())` or the actual worker looper.
- Replace `URLDecoder.decode(String)` with the charset overload using UTF-8.
- Replace `Class.newInstance()` with `getDeclaredConstructor().newInstance()` and handle the more precise reflection exceptions without hiding constructor failures.
- Replace `PackageInfo.versionCode` with `PackageInfoCompat.getLongVersionCode()`; check narrowing before converting to `int`.
- Replace deprecated `MenuItemCompat` action-view and expansion-listener calls with direct `MenuItem` APIs.

### SMS and phone-number utilities

- Since minSdk is 23, always call `SmsMessage.createFromPdu(byte[], format)`.
- Replace `PhoneNumberUtils.formatNumber(String)` and `compare(Context, String, String)` with the existing libphonenumber-backed utilities only after parity tests cover local, E.164, short-code, and malformed input.

Phase A exit gate:

- The targeted warning families are absent from an uncapped compile.
- Debug and release builds pass.
- Existing unit tests pass, with focused tests added for typed extras and phone-number parity.

## 4. Phase B: Fragment and activity lifecycle APIs

Treat this as lifecycle work, not a syntactic API rename.

### Fragment callbacks and menus

- Move initialization from `Fragment.onActivityCreated()` to `onViewCreated()` or `onCreate()` according to whether it owns views or retained state.
- Replace `onAttach(Activity)` with `onAttach(Context)` and validate listener interfaces before casting.
- Replace `setHasOptionsMenu()` with `MenuHost`/`MenuProvider`, scoped to the correct lifecycle state.
- Replace deprecated `ComponentActivity.onBackPressed()` overrides/calls with `OnBackPressedDispatcher` callbacks. Enable callbacks only while the screen can consume back navigation.

### Activity results and permissions

Replace these together so one screen does not contain two competing result paths:

- `Fragment.startActivityForResult()` and `ComponentActivity.startActivityForResult()`
- Fragment `onActivityResult()`
- Fragment `requestPermissions()` and `onRequestPermissionsResult()`

Use Activity Result contracts registered before the component reaches `STARTED`. Adapt the custom `Permissions` wrapper so callers can migrate without request-code collisions. Create custom contracts where QR scanning, ringtone selection, or document selection needs structured parsing.

### Loaders

- Replace deprecated fragment entry points with `LoaderManager.getInstance(owner)` as the smallest first step.
- Inventory every remaining loader after that conversion. Keep `CursorLoader`/`AsyncTaskLoader` only where lifecycle restart and cursor observation are still required.
- For one-shot queries, use repository/executor work with an explicit owner rather than creating a new loader abstraction.

Primary files include `ConversationFragment`, `ConversationListFragment`, `ConversationListActivity`, `CountrySelectionFragment`, `ShareFragment`, `WelcomeActivity`, and contact/selection screens.

Phase B exit gate:

- Rotation and process restoration do not duplicate callbacks or lose selected state.
- Permission grant, denial, and permanent denial follow one result path.
- Contact selection, document import, QR scan, and ringtone selection work after recreation.
- No deprecated Fragment activity-result, permission, attachment, menu, back, or loader-manager entry-point warnings remain.

## 5. Phase C: QR, ringtone, display, and transition APIs

### ZXing scanning

- Replace deprecated `IntentIntegrator` and `parseActivityResult()` with JourneyApps `ScanContract` and an Activity Result launcher.
- Configure formats, prompt text, camera selection, orientation, and timeout explicitly so the scanner behavior does not change accidentally.
- Keep QR identity payload validation in the activity; the contract should only return scanner data.

Validate successful scans, cancellation, malformed payloads, camera denial, rotation, and returning from a killed scanner activity.

### Ringtone preferences

- Replace the custom platform `android.preference.RingtonePreference` dependency with an AndroidX `Preference` or `DialogPreference` implementation.
- Migrate `onSetInitialValue(boolean, Object)` to the current preference persistence callback.
- Replace deprecated ringtone stream configuration with `AudioAttributes`.
- Review `USAGE_NOTIFICATION_COMMUNICATION_INSTANT`; use the current notification or communication usage that matches actual playback behavior.

Validate default, silent, custom, deleted, and inaccessible ringtone URIs plus playback on Android 6 and the newest supported Android version.

### Display and transitions

- First remove display-size queries where layout constraints can provide the needed dimensions.
- Otherwise use `WindowMetrics` on API 30+ and isolate the API 23-29 `Display` fallback.
- Replace `overridePendingTransition()` with current activity transition APIs where available, retaining a localized old-version fallback only when the animation is user-visible and worth preserving.

Phase C exit gate: scanner, ringtone, display, and transition warning families are either removed or reduced to documented compatibility fallbacks.

## 6. Phase D: System bars and edge-to-edge APIs

Remove per-screen ownership of system-bar state:

- Route status/navigation bar appearance through `BaseActionBarActivity` and the existing decor-view inset/scrim implementation.
- Remove `FLAG_FULLSCREEN`, `SYSTEM_UI_FLAG_FULLSCREEN`, and `setSystemUiVisibility()` from individual screens. Use `WindowInsetsControllerCompat` for transient visibility and icon appearance.
- Remove screen-level `setStatusBarColor()`, `setNavigationBarColor()`, and `getStatusBarColor()` calls where the central edge-to-edge implementation already paints scrims.
- Replace direct reads of the deprecated `statusBarColor` attribute with the app's compatibility theme/color resolution path.
- Preserve `MediaPreviewActivity` as the explicit immersive opt-out and test entry/exit restoration.

Phase D exit gate:

- Status/navigation regions, icon contrast, cutouts, and IME insets are correct on API 23, 29, 35, and 36.
- Conversation, main list, settings, dialogs, and media preview do not overwrite one another's system-bar state.
- Any remaining color setter is confined to the old-platform branch of the central compatibility implementation.

## 7. Phase E: Services and foreground execution

### IntentService

Replace deprecated `IntentService` usage in `MasterSecretIntentService` and dependent services with one of these, selected per behavior:

- An explicit `Service` with a serial executor when work needs service lifetime and in-order intent processing.
- The existing durable job infrastructure when work must survive process death or be retried.
- A short process executor only when losing the work with the process is acceptable.

Preserve intent ordering, redelivery policy, wake/foreground requirements, master-secret availability, and stop behavior. Do not move sensitive plaintext or keys into disk-persisted work payloads.

### Foreground-service shutdown

- Replace `stopForeground(boolean)` with `ServiceCompat.stopForeground()` and explicit remove/detach flags.
- Verify notification removal separately from service termination.
- Confirm Android 12+ background-start restrictions and Android 14+ foreground-service type requirements.

Phase E exit gate: service restart, queued-intent ordering, process death, foreground notification removal, and locked/unlocked secret handling pass device tests.

## 8. Phase F: Connectivity and MMS routing

Split general connectivity from carrier MMS routing; they have different replacement constraints.

### General connectivity

- Replace `getActiveNetworkInfo()`, `NetworkInfo.isConnected()`, and `CONNECTIVITY_ACTION` with `ConnectivityManager` callbacks and `NetworkCapabilities`.
- Model validated internet, transport type, and roaming separately; do not treat network availability as internet reachability.
- Register and unregister callbacks with an application/service owner, not an activity unless the result is purely visual.

### MMS-specific network handling

- Preserve explicit mobile-MMS network acquisition and carrier proxy/APN behavior in `MmsRadio`.
- Use `requestNetwork()` plus `NetworkCapabilities`/`NetworkRequest` for the modern path.
- Before removing `TYPE_MOBILE_MMS`, `getNetworkInfo(int)`, `NetworkInfo.getType()`, `getExtraInfo()`, `getDetailedState()`, `isAvailable()`, or `isRoaming()`, prove equivalent behavior on real carrier MMS routes.
- Keep the deprecated branch localized if API 23-era devices or carrier behavior still require it.

Phase F device matrix:

- Wi-Fi with mobile data available and unavailable.
- Mobile data, roaming, and data disabled.
- Single-SIM and dual-SIM with a non-default MMS subscription.
- Carrier proxy and direct MMSC routes.
- Timeout, handover, retry, and network-loss during send/download.

Exit gate: general connectivity warnings are removed; MMS-only fallbacks are isolated, documented, and covered by device evidence.

## 9. Phase G: Telephony and subscription APIs

### Service-state callbacks

- On API 31+, replace `PhoneStateListener`, `listen()`, `LISTEN_SERVICE_STATE`, and `LISTEN_NONE` with `TelephonyCallback.ServiceStateListener` plus `registerTelephonyCallback()`/`unregisterTelephonyCallback()`.
- Keep one API 23-30 listener implementation behind a compatibility interface.
- Ensure callback registration uses the intended subscription-specific `TelephonyManager`.

### Subscription metadata

- Replace `SubscriptionManager.from(context)` with the typed system service.
- On API 29+, use `SubscriptionInfo.getMccString()` and `getMncString()`; retain numeric API 23-28 fallbacks.
- Review `TelephonyManager.PHONE_TYPE_CDMA` branches against current behavior. Remove obsolete CDMA-specific logic only after identifying what invariant it protected.

### Phone number access

- Prefer the newest subscription-aware phone-number API where available and permitted.
- Retain guarded fallbacks for `TelephonyManager.getLine1Number()` and `SubscriptionInfo.getNumber()` only on API levels where no equivalent exists.
- Treat null, blank, unprovisioned, and permission-denied results as normal.
- Never use a number from the wrong subscription as a fallback in dual-SIM flows.

Phase G exit gate:

- Service-state callbacks register/unregister exactly once across lifecycle changes.
- Dual-SIM subscription selection remains consistent for identity/session lookup and SMS/MMS routing.
- Modern devices have no deprecated telephony calls; old-platform calls are isolated and suppressed locally.

## 10. Phase H: Notifications and Android Auto

- Replace deprecated notification builder/person APIs with current `NotificationCompat.Builder` channel-aware constructors and `Person` objects.
- Replace platform priority constants with compat priority and channel importance as appropriate; remember that channel importance controls behavior on API 26+.
- Preserve content intent, delete intent, grouping, public version, reply, mark-read, and wearable actions.
- Evaluate replacing `CarExtender.UnreadConversation` with `MessagingStyle` plus semantic reply/read actions. There is no one-for-one replacement, so remove the legacy payload only after Android Auto testing proves parity.

Validate one-to-one and group conversations, locked state, multiple unread threads, reply, heard/read, dismissal, wearable display, Android Auto display, and existing notification channels after upgrade.

Phase H exit gate: ordinary notification deprecations are removed; any retained `UnreadConversation` fallback is narrowly suppressed with device evidence and a removal condition.

## 11. Deferred compatibility fallbacks

These warnings may not be completely removable while minSdk remains 23. A localized suppression is an acceptable resolved state only after the modern path and tests exist.

| API family | Why a fallback may remain | Removal condition |
| --- | --- | --- |
| MMS `NetworkInfo`/mobile-MMS constants | No mechanical replacement guarantees legacy carrier APN/proxy behavior | Real-carrier matrix proves `requestNetwork()` parity, or minSdk/carrier support policy drops the legacy path |
| `PhoneStateListener` | `TelephonyCallback` starts at API 31 | minSdk 31 or higher |
| Numeric subscription MCC/MNC | String accessors start at API 29 | minSdk 29 or higher |
| Line/subscription phone number | Replacement availability and permissions vary by API level and carrier | Old API range is dropped or product no longer needs the number |
| Legacy `Display` metrics | `WindowMetrics` starts at API 30 | Query is designed out or minSdk 30 or higher |
| Activity transition fallback | Current override API is not available across the full range | Old transition is removed or minSdk reaches the modern API |
| System-bar color setters | Older Android still uses them while newer Android enforces edge-to-edge | minSdk reaches the edge-to-edge-only implementation range |
| `Locale(String, String)` | `Locale.of()` is unavailable across the full range and builder normalization may change persisted malformed values | Locale persistence is normalized and migration tests prove equivalent restoration |
| Android Auto `UnreadConversation` | No direct replacement with guaranteed legacy head-unit behavior | `MessagingStyle`/actions pass supported-head-unit testing or legacy Auto support is dropped |

Do not count these as unexplained warnings. Maintain a checked-in allowlist containing the exact file, API, supported API range, and removal condition. The deprecation gate should fail for warnings outside that allowlist.

## 12. Final verification

Run after every phase and once from a clean tree at completion:

```powershell
.\gradlew.bat clean :assembleDebug :assembleRelease --warning-mode all
.\gradlew.bat testDebugUnitTest
```

Then perform one uncapped javac inventory and classify every remaining deprecation as:

- Owned by the AsyncTask plan.
- A documented compatibility fallback in the exact allowlisted location.
- Generated source owned by regeneration/removal.
- A regression that fails the gate.

Completion criteria:

- All actionable non-Gradle Android/Java API warnings are removed.
- Every unavoidable warning is localized, suppressed, documented, and tied to a removal condition.
- No global warning suppression has been introduced.
- Debug/release builds and unit tests pass.
- Device matrices for lifecycle, system bars, services, MMS, telephony, notifications, Android Auto, QR, and ringtone behavior pass on the supported API ranges.