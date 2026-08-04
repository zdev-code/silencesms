# Silence Android agent guide

## Start here

- Use [`BUILDING.md`](BUILDING.md) for SDK setup, normal builds, and the staged crypto release procedure.
- Use [`CONTRIBUTING.md`](CONTRIBUTING.md) for contribution and diagnostic-log conventions.
- Treat [`build.gradle`](build.gradle) and [`settings.gradle`](settings.gradle) as the source of truth when documentation and the current toolchain differ.

## Repository shape

- This is a legacy-layout Android application: app Java is under `src/`, resources under `res/`, JVM tests under `test/unitTest/java/`, and device tests under `test/androidTest/java/`.
- `libs/` contains vendored Gradle modules. Do not casually refactor them as app code; change a vendored fork only when the task explicitly requires it, and validate its module as well as the app.
- Never edit Gradle output under `build/`. Treat `graphify-out/` as generated but committed project knowledge; update it as described below instead of deleting it.
- The app targets Java 17 and Android API 36 with minSdk 23. Production native packaging is ARM64-only; do not silently broaden or narrow ABI support.

## Build and validation

On Windows PowerShell, use the wrapper as `./gradlew.bat`; on Unix-like shells, use `./gradlew`.

- `./gradlew.bat assembleDebug` builds the development APK.
- `./gradlew.bat test` runs app JVM tests; prefer a targeted `--tests` filter while iterating.
- `./gradlew.bat connectedAndroidTest` runs device/emulator tests.
- `./gradlew.bat :java:test` runs the vendored libsignal JVM suite.
- `./gradlew.bat verifyCryptoReleaseStages assemblePhaseARelease assembleRelease` validates and builds both crypto rollout artifacts. Do not substitute the normal release for Phase A.

Run the narrowest relevant test first, then `assembleDebug` for app-source changes. Crypto, storage, backup/restore, or vendored-libsignal changes require their focused tests plus the applicable release-stage check.

## Modernization constraints

- New `android.os.AsyncTask` usage is forbidden by `checkNoNewAsyncTaskUsage`; use the repository's executor, job, or lifecycle-aware patterns.
- Every app-source `@SuppressWarnings("deprecation")` must have a matching four-column entry in [`config/android-deprecation-allowlist.tsv`](config/android-deprecation-allowlist.tsv). Prefer removing the deprecated API; keep the allowlist exact when compatibility requires suppression.
- Jetifier is disabled. New dependencies must be AndroidX-native and must not reintroduce `com.android.support` artifacts.
- Do not remove code merely because it looks legacy. First trace manifest registration, callers, API-level branches, persistence formats, upgrade/rollback behavior, and tests. Compatibility code is removable only when those paths are demonstrably dead or the task explicitly changes the compatibility contract.
- System-bar and cutout handling is centralized in `BaseActionBarActivity`; preserve that ownership when changing activities or fragment content.

## Crypto and persistence

- The app deliberately supports staged crypto writes and backward-compatible reads. Preserve readable legacy data and rollback behavior unless the task explicitly changes that contract.
- Message crypto uses the current Signal client while key-exchange compatibility still involves the vendored `org.whispersystems.libsignal` fork. Keep adapters in app source unless the task genuinely requires changing the fork.
- Treat key material, session stores, preference migration, and backup/restore as atomic migration paths. Validate both success and interruption/rollback cases; never infer safety from a fresh-install test alone.

## graphify

This project has a knowledge graph at graphify-out/ with god nodes, community structure, and cross-file relationships.
Installation, initial generation, semantic-backend options, and validation are documented in
[`README.md`](README.md#graphify-knowledge-graph).

When the user types `/graphify`, use the installed graphify skill or instructions before doing anything else.

Rules:

- For codebase questions, first run `graphify query "<question>"` when graphify-out/graph.json exists. Use `graphify path "<A>" "<B>"` for relationships and `graphify explain "<concept>"` for focused concepts. These return a scoped subgraph, usually much smaller than GRAPH_REPORT.md or raw grep output.
- Dirty graphify-out/ files are expected after hooks or incremental updates; dirty graph files are not a reason to skip graphify. Only skip graphify if the task is about stale or incorrect graph output, or the user explicitly says not to use it.
- If graphify-out/wiki/index.md exists, use it for broad navigation instead of raw source browsing.
- Read graphify-out/GRAPH_REPORT.md only for broad architecture review or when query/path/explain do not surface enough context.
- After modifying code, run `graphify update .` to keep the graph current (AST-only, no API cost).
