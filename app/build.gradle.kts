import java.nio.file.Paths
import java.util.Properties
import java.util.TreeSet
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.compile.JavaCompile

abstract class VerifyCryptoPolicy : DefaultTask() {
    @get:InputFile
    abstract val debugBuildConfig: RegularFileProperty

    @get:InputFile
    abstract val phaseABuildConfig: RegularFileProperty

    @TaskAction
    fun verify() {
        if (!debugBuildConfig.get().asFile.readText().contains("MODERN_CRYPTO_WRITES = true")) {
            throw GradleException("Debug must enable modern crypto writes")
        }
        if (!phaseABuildConfig.get().asFile.readText().contains("MODERN_CRYPTO_WRITES = false")) {
            throw GradleException("Phase A release must disable modern crypto writes")
        }
    }
}

abstract class CheckNoAsyncTaskUsage : DefaultTask() {
    @get:InputFiles
    abstract val sources: ConfigurableFileCollection

    @get:Input
    abstract val repositoryRoot: Property<String>

    @TaskAction
    fun verify() {
        val root = Paths.get(repositoryRoot.get())
        val actual = TreeSet<String>()
        sources.files.forEach { sourceFile ->
            val source = sourceFile.readText()
            val importsAsyncTask = Regex("""import\s+android\.os\.AsyncTask\s*;""").containsMatchIn(source)
            val extendsAsyncTask = Regex("""extends\s+(?:android\.os\.)?AsyncTask\s*<""").containsMatchIn(source)
            if (importsAsyncTask || extendsAsyncTask) {
                actual.add(root.relativize(sourceFile.toPath()).toString().replace('\\', '/'))
            }
        }
        if (actual.isNotEmpty()) {
            throw GradleException("android.os.AsyncTask usage is forbidden:\n  " + actual.joinToString("\n  "))
        }
    }
}

abstract class CheckAndroidDeprecationAllowlist : DefaultTask() {
    @get:InputFile
    abstract val allowlist: RegularFileProperty

    @get:InputFiles
    abstract val sources: ConfigurableFileCollection

    @get:Input
    abstract val repositoryRoot: Property<String>

    @TaskAction
    fun verify() {
        val expected = TreeSet<String>()
        allowlist.get().asFile.readLines().forEachIndexed { index, line ->
            if (line.isBlank() || line.startsWith("#")) return@forEachIndexed
            val fields = line.split('\t')
            if (fields.size != 4 || fields.any { it.isBlank() }) {
                throw GradleException("Malformed deprecation allowlist row ${index + 1}; expected four non-empty tab-separated fields")
            }
            if (!expected.add(fields[0])) {
                throw GradleException("Duplicate deprecation allowlist path: ${fields[0]}")
            }
        }

        val root = Paths.get(repositoryRoot.get())
        val actual = TreeSet<String>()
        sources.files.forEach { sourceFile ->
            if (sourceFile.readText().contains("@SuppressWarnings(\"deprecation\")")) {
                actual.add(root.relativize(sourceFile.toPath()).toString().replace('\\', '/'))
            }
        }

        val undocumented = TreeSet(actual).apply { removeAll(expected) }
        val stale = TreeSet(expected).apply { removeAll(actual) }
        if (undocumented.isNotEmpty() || stale.isNotEmpty()) {
            throw GradleException(buildString {
                append("Android deprecation allowlist mismatch")
                if (undocumented.isNotEmpty()) append("\nUndocumented suppressions:\n  ${undocumented.joinToString("\n  ")}")
                if (stale.isNotEmpty()) append("\nStale allowlist entries:\n  ${stale.joinToString("\n  ")}")
            })
        }
    }
}

abstract class CheckConversationListArchitecture : DefaultTask() {
    @get:InputFiles
    abstract val sources: ConfigurableFileCollection

    @TaskAction
    fun verify() {
        val forbidden = linkedMapOf(
            "DatabaseFactory" to "direct database access",
            "android.database.Cursor" to "cursor ownership",
            "androidx.loader" to "Loader ownership",
            "org.greenrobot.eventbus" to "EventBus coupling",
            "AppTaskExecutor.getInstance" to "direct task execution",
            "MessageSender." to "direct message operation",
            "SaveAttachmentTask.save" to "direct attachment-save operation"
        )
        val violations = mutableListOf<String>()
        sources.files.sortedBy { it.path }.forEach { sourceFile ->
            val source = sourceFile.readText()
            forbidden.forEach { (token, description) ->
                if (source.contains(token)) violations.add("${sourceFile.name}: $description ($token)")
            }
            if (source.contains("SavedStateHandle") &&
                sourceFile.name != "ConversationListStateStore.java" &&
                sourceFile.name != "ConversationListViewModelFactory.java") {
                violations.add("${sourceFile.name}: direct SavedStateHandle ownership")
            }
        }
        if (violations.isNotEmpty()) {
            throw GradleException(
                "Conversation-list UI architecture boundary violations:\n  " + violations.joinToString("\n  ")
            )
        }
    }
}

abstract class CheckEventBusAllowlist : DefaultTask() {
    @get:InputFile
    abstract val allowlist: RegularFileProperty

    @get:InputFiles
    abstract val sources: ConfigurableFileCollection

    @get:Input
    abstract val repositoryRoot: Property<String>

    @TaskAction
    fun verify() {
        val expected = TreeSet<String>()
        allowlist.get().asFile.readLines().forEachIndexed { index, line ->
            if (line.isBlank() || line.startsWith("#")) return@forEachIndexed
            val fields = line.split('\t')
            if (fields.size != 3 || fields.any { it.isBlank() }) {
                throw GradleException("Malformed EventBus allowlist row ${index + 1}; expected three non-empty tab-separated fields")
            }
            if (!expected.add(fields[0])) throw GradleException("Duplicate EventBus allowlist path: ${fields[0]}")
        }

        val root = Paths.get(repositoryRoot.get())
        val actual = TreeSet<String>()
        sources.files.forEach { sourceFile ->
            if (sourceFile.readText().contains("org.greenrobot.eventbus")) {
                actual.add(root.relativize(sourceFile.toPath()).toString().replace('\\', '/'))
            }
        }
        val undocumented = TreeSet(actual).apply { removeAll(expected) }
        val stale = TreeSet(expected).apply { removeAll(actual) }
        if (undocumented.isNotEmpty() || stale.isNotEmpty()) {
            throw GradleException(buildString {
                append("EventBus allowlist mismatch")
                if (undocumented.isNotEmpty()) append("\nUndocumented usage:\n  ${undocumented.joinToString("\n  ")}")
                if (stale.isNotEmpty()) append("\nStale entries:\n  ${stale.joinToString("\n  ")}")
            })
        }
    }
}

abstract class CheckConversationThreadArchitecture : DefaultTask() {
    @get:InputFiles
    abstract val sources: ConfigurableFileCollection

    @TaskAction
    fun verify() {
        val forbidden = linkedMapOf(
            "DatabaseFactory" to "direct database access",
            "android.database.Cursor" to "cursor ownership",
            "androidx.loader" to "Loader ownership",
            "org.greenrobot.eventbus" to "EventBus coupling"
        )
        val violations = mutableListOf<String>()
        sources.files.sortedBy { it.path }.forEach { sourceFile ->
            val source = sourceFile.readText()
            forbidden.forEach { (token, description) ->
                if (source.contains(token)) violations.add("${sourceFile.name}: $description ($token)")
            }
            if (source.contains("SavedStateHandle") &&
                sourceFile.name != "ConversationThreadStateStore.java" &&
                sourceFile.name != "ConversationThreadViewModelFactory.java") {
                violations.add("${sourceFile.name}: direct SavedStateHandle ownership")
            }
        }
        if (violations.isNotEmpty()) {
            throw GradleException(
                "Conversation-thread UI architecture boundary violations:\n  " + violations.joinToString("\n  ")
            )
        }
    }
}

abstract class CheckConversationScreenArchitecture : DefaultTask() {
    @get:InputFiles
    abstract val stateSources: ConfigurableFileCollection

    @get:InputFile
    abstract val activitySource: RegularFileProperty

    @TaskAction
    fun verify() {
        val violations = mutableListOf<String>()
        val activity = activitySource.get().asFile.readText()
         listOf("DatabaseFactory", "android.database.Cursor", "androidx.loader", "org.greenrobot.eventbus",
             "AppTaskExecutor.getInstance", "MessageSender.send")
            .filter(activity::contains)
            .forEach { violations.add("ConversationActivity.java: forbidden ownership ($it)") }
        stateSources.files.sortedBy { it.path }.forEach { sourceFile ->
            val source = sourceFile.readText()
            if (source.contains("MasterSecret")) violations.add("${sourceFile.name}: secret in screen state")
            if (source.contains("SavedStateHandle") &&
                sourceFile.name != "ConversationScreenStateStore.java" &&
                sourceFile.name != "ConversationScreenViewModelFactory.java") {
                violations.add("${sourceFile.name}: direct SavedStateHandle ownership")
            }
        }
        if (violations.isNotEmpty()) {
            throw GradleException(
                "Conversation-screen architecture boundary violations:\n  " + violations.joinToString("\n  ")
            )
        }
    }
}

abstract class CheckModernArchitectureBoundaries : DefaultTask() {
    @get:InputFiles
    abstract val sources: ConfigurableFileCollection

    @get:InputFile
    abstract val passphraseActivity: RegularFileProperty

    @get:InputFile
    abstract val manifest: RegularFileProperty

    @get:InputFile
    abstract val conversationListGraph: RegularFileProperty

    @TaskAction
    fun verify() {
        val violations = mutableListOf<String>()
        sources.files.sortedBy { it.path }.forEach { sourceFile ->
            val source = sourceFile.readText()
            if (source.contains("AppDependencies") || source.contains("DefaultAppDependencies")) {
                violations.add("${sourceFile.name}: removed application service locator")
            }
            listOf("androidx.loader", "AsyncTaskLoader", "CursorLoader", "LoaderManager")
                .filter(source::contains)
                .forEach { violations.add("${sourceFile.name}: removed Loader API ($it)") }
            if (sourceFile.name.contains("UiState") &&
                listOf("MasterSecret", "ConversationUnlockCapability", "recoveryKey", "passphrase")
                    .any(source::contains)) {
                violations.add("${sourceFile.name}: secret-bearing immutable UI state")
            }
            if (sourceFile.name.contains("Destination") && source.contains("MasterSecret")) {
                violations.add("${sourceFile.name}: secret-bearing navigation argument")
            }
            if (Regex("""putParcelable\(\s*"master_secret"""").containsMatchIn(source) ||
                Regex("""getParcelable\(\s*getArguments\(\)\s*,\s*"master_secret"""").containsMatchIn(source)) {
                violations.add("${sourceFile.name}: MasterSecret must not enter Fragment saved arguments")
            }
            if (sourceFile.name == "RoutingActivity.java" &&
                listOf("DatabaseFactory", "Repository", "MasterSecret", "KeyCachingService")
                    .any(source::contains)) {
                violations.add("RoutingActivity.java: launcher router owns data or unlock state")
            }
            if (sourceFile.name == "PassphraseChangeActivity.java") {
                if (source.contains("getText().toString()") ||
                    source.contains("AppTaskExecutor.getInstance()")) {
                    violations.add("PassphraseChangeActivity.java: passphrase input or task ownership bypasses wipeable controller")
                }
                listOf("WipeablePassphrase", "PassphraseChangeController", "UnlockSession.capture()")
                    .filterNot(source::contains)
                    .forEach { violations.add("PassphraseChangeActivity.java: missing hardened passphrase contract $it") }
            }
            if (sourceFile.name in setOf("ConversationListActivity.java", "ConversationListFragment.java",
                                         "ConversationListModelAdapter.java", "ConversationListEntryMapper.java") &&
                Regex("""private\s+(?:final\s+)?MasterSecret\b""").containsMatchIn(source)) {
                violations.add("${sourceFile.name}: application host retains MasterSecret")
            }
        }
        val passphraseSource = passphraseActivity.get().asFile.readText()
        listOf("onPreCreate();", "routeApplicationState(masterSecret);", "super.onCreate(savedInstanceState);")
            .filterNot(passphraseSource::contains)
            .forEach { violations.add("PassphraseRequiredActionBarActivity.java: missing gate step $it") }
        val manifestSource = manifest.get().asFile.readText()
        val hostDeclaration = Regex("""<activity android:name="\.ConversationListActivity"[\s\S]*?/>""")
            .find(manifestSource)?.value.orEmpty()
        if (!hostDeclaration.contains("android:exported=\"false\"")) {
            violations.add("AndroidManifest.xml: application NavHost must be non-exported")
        }
        val routingDeclaration = Regex("""<activity android:name="\.RoutingActivity"[\s\S]*?</activity>""")
            .find(manifestSource)?.value.orEmpty()
        if (!routingDeclaration.contains("android:exported=\"true\"") ||
            !routingDeclaration.contains("android.intent.category.LAUNCHER")) {
            violations.add("AndroidManifest.xml: exported launcher router contract missing")
        }
        listOf("ConversationListArchiveActivity", "GroupCreateActivity")
            .filter(manifestSource::contains)
            .forEach { violations.add("AndroidManifest.xml: migrated Activity must remain a NavHost destination ($it)") }
        val graphSource = conversationListGraph.get().asFile.readText()
        listOf("conversation_list_inbox", "conversation_list_archive", "group_create", "new_conversation")
            .filterNot(graphSource::contains)
            .forEach { violations.add("conversation_list_navigation.xml: missing destination $it") }
        if (violations.isNotEmpty()) {
            throw GradleException("Modern architecture boundary violations:\n  " + violations.joinToString("\n  "))
        }
    }
}

abstract class CheckUiDataAccessAllowlist : DefaultTask() {
    @get:InputFile
    abstract val allowlist: RegularFileProperty

    @get:InputFiles
    abstract val sources: ConfigurableFileCollection

    @get:Input
    abstract val repositoryRoot: Property<String>

    @TaskAction
    fun verify() {
        val expected = TreeSet<String>()
        allowlist.get().asFile.readLines().forEachIndexed { index, line ->
            if (line.isBlank() || line.startsWith("#")) return@forEachIndexed
            val fields = line.split('\t')
            if (fields.size != 3 || fields.any { it.isBlank() }) {
                throw GradleException("Malformed UI data-access allowlist row ${index + 1}")
            }
            if (!expected.add(fields[0])) throw GradleException("Duplicate UI data-access path: ${fields[0]}")
        }

        val root = Paths.get(repositoryRoot.get())
        val forbidden = listOf("DatabaseFactory", "androidx.loader", "SharedPreferences", "PreferenceManager")
        val actual = TreeSet<String>()
        sources.files.forEach { sourceFile ->
            if (forbidden.any(sourceFile.readText()::contains)) {
                actual.add(root.relativize(sourceFile.toPath()).toString().replace('\\', '/'))
            }
        }
        val undocumented = TreeSet(actual).apply { removeAll(expected) }
        val stale = TreeSet(expected).apply { removeAll(actual) }
        if (undocumented.isNotEmpty() || stale.isNotEmpty()) {
            throw GradleException(buildString {
                append("UI data-access allowlist mismatch")
                if (undocumented.isNotEmpty()) append("\nUndocumented usage:\n  ${undocumented.joinToString("\n  ")}")
                if (stale.isNotEmpty()) append("\nStale entries:\n  ${stale.joinToString("\n  ")}")
            })
        }
    }
}

abstract class CheckRetainedActivityInventory : DefaultTask() {
    @get:InputFile
    abstract val inventory: RegularFileProperty

    @get:InputFile
    abstract val manifest: RegularFileProperty

    @get:InputFiles
    abstract val tests: ConfigurableFileCollection

    @get:Input
    abstract val knownTaskGates: Property<String>

    @TaskAction
    fun verify() {
        val expected = linkedMapOf<String, List<String>>()
        inventory.get().asFile.readLines().forEachIndexed { index, line ->
            if (line.isBlank() || line.startsWith("#")) return@forEachIndexed
            val fields = line.split('\t')
            if (fields.size != 4 || fields.any { it.isBlank() }) {
                throw GradleException("Malformed retained Activity row ${index + 1}; expected four non-empty tab-separated fields")
            }
            if (expected.put(fields[0], fields) != null) {
                throw GradleException("Duplicate retained Activity component: ${fields[0]}")
            }
        }

        val manifestSource = manifest.get().asFile.readText()
        val actual = Regex("""<activity\s+[\s\S]*?android:name="([^"]+)"[\s\S]*?(?:/>|</activity>)""")
            .findAll(manifestSource)
            .map { it.groupValues[1] }
            .filter { it.startsWith(".") && it != ".ConversationListActivity" }
            .toCollection(TreeSet())
        val expectedNames = TreeSet(expected.keys)
        val undocumented = TreeSet(actual).apply { removeAll(expectedNames) }
        val stale = TreeSet(expectedNames).apply { removeAll(actual) }
        if (undocumented.isNotEmpty() || stale.isNotEmpty()) {
            throw GradleException(buildString {
                append("Retained Activity inventory mismatch")
                if (undocumented.isNotEmpty()) append("\nUndocumented Activities:\n  ${undocumented.joinToString("\n  ")}")
                if (stale.isNotEmpty()) append("\nStale inventory entries:\n  ${stale.joinToString("\n  ")}")
            })
        }

        val allowedCategories = setOf("ROUTER", "EXTERNAL_ENTRY", "DISTINCT_WINDOW", "AUTH_GATE",
                                      "SECURITY_FLOW", "SECURE_RESULT_FLOW", "EXTERNAL_BRIDGE",
                                      "PLATFORM_RESULT", "PLATFORM_FLOW")
        expected.values.filter { it[1] !in allowedCategories }
            .forEach { throw GradleException("Unknown retained Activity category ${it[1]} for ${it[0]}") }

        val testNames = tests.files.map { it.nameWithoutExtension }.toSet()
        val taskNames = knownTaskGates.get().split(',').toSet()
        expected.values.filter { it[3] !in testNames && it[3] !in taskNames }
            .forEach { throw GradleException("Unknown executable gate ${it[3]} for ${it[0]}") }
    }
}

abstract class CheckHostDestinationSecurityPolicy : DefaultTask() {
    @get:InputFile
    abstract val policy: RegularFileProperty

    @get:InputFile
    abstract val navigationGraph: RegularFileProperty

    @get:InputFile
    abstract val manifest: RegularFileProperty

    @get:InputFiles
    abstract val stateAndDestinationSources: ConfigurableFileCollection

    @TaskAction
    fun verify() {
        val rows = linkedMapOf<String, List<String>>()
        policy.get().asFile.readLines().forEachIndexed { index, line ->
            if (line.isBlank() || line.startsWith("#")) return@forEachIndexed
            val fields = line.split('\t')
            if (fields.size != 11 || fields.any { it.isBlank() }) {
                throw GradleException("Malformed host destination row ${index + 1}; expected eleven non-empty tab-separated fields")
            }
            if (rows.put(fields[0], fields) != null) {
                throw GradleException("Duplicate host destination policy: ${fields[0]}")
            }
            if (fields[2] != "REQUIRED") {
                throw GradleException("Protected host destination must require unlock: ${fields[0]}")
            }
            if (fields[5] !in setOf("USER_PREFERENCE", "ALWAYS_SECURE")) {
                throw GradleException("Unknown screen-capture policy ${fields[5]} for ${fields[0]}")
            }
            if (fields[8] != "AUTHENTICATE_THEN_RELOAD" || fields[9] != "RESET_GRAPH") {
                throw GradleException("Host destination must fail closed on restore and relock: ${fields[0]}")
            }
        }

        val graphSource = navigationGraph.get().asFile.readText()
        val graphDestinations = Regex("""<fragment[\s\S]*?android:id="@\+id/([^"]+)"""")
            .findAll(graphSource).map { it.groupValues[1] }.toCollection(TreeSet())
        val policyDestinations = TreeSet(rows.keys)
        if (graphDestinations != policyDestinations) {
            val missing = TreeSet(graphDestinations).apply { removeAll(policyDestinations) }
            val stale = TreeSet(policyDestinations).apply { removeAll(graphDestinations) }
            throw GradleException(buildString {
                append("Host destination security policy mismatch")
                if (missing.isNotEmpty()) append("\nMissing policy: ${missing.joinToString()}")
                if (stale.isNotEmpty()) append("\nStale policy: ${stale.joinToString()}")
            })
        }

        val manifestSource = manifest.get().asFile.readText()
        val hostDeclaration = Regex("""<activity android:name="\.ConversationListActivity"[\s\S]*?/>""")
            .find(manifestSource)?.value.orEmpty()
        if (!hostDeclaration.contains("android:exported=\"false\"")) {
            throw GradleException("Application NavHost must remain non-exported")
        }

        val forbiddenTypes = listOf("MasterSecret", "RecoveryKey", "IdentityKey", "Attachment")
        val forbiddenKey = Regex("""KEY_[A-Z0-9_]*(?:BODY|PLAINTEXT|PASSPHRASE|SECRET|RECOVERY|FINGERPRINT|ADDRESS|URI)[A-Z0-9_]*""")
        val violations = mutableListOf<String>()
        stateAndDestinationSources.files.sortedBy { it.path }.forEach { sourceFile ->
            val source = sourceFile.readText()
            forbiddenTypes.filter(source::contains)
                .forEach { violations.add("${sourceFile.name}: secret-bearing route/state type ($it)") }
            forbiddenKey.findAll(source)
                .forEach { violations.add("${sourceFile.name}: sensitive saved-state key (${it.value})") }
        }
        if (violations.isNotEmpty()) {
            throw GradleException("Host destination security violations:\n  " + violations.joinToString("\n  "))
        }
    }
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.hilt.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.compose)
}

dependencies {
    implementation(project(":core-models"))
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.recyclerview)
    implementation(libs.material)
    implementation(libs.androidx.core)
    implementation(libs.androidx.cardview)
    implementation(libs.androidx.preference)
    implementation(libs.androidx.gridlayout)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.viewpager)
    implementation(libs.androidx.interpolator)
    implementation(libs.androidx.cursoradapter)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.lifecycle.viewmodel.savedstate)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.navigation.fragment)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.documentfile)

    implementation(libs.okhttp)
    implementation(libs.photoview) { exclude(group = "com.android.support") }
    implementation(libs.glide)
    implementation(libs.eventbus)
    implementation(libs.zxing.embedded)
    implementation(libs.zxing.core)
    implementation(libs.subsampling.image)

    implementation(project(":org.whispersystems.jobmanager"))
    implementation(project(":org.whispersystems.libsignal"))
    implementation(libs.libsignal.android)

    implementation(libs.jackson.annotations)
    implementation(libs.jackson.core)
    implementation(libs.jackson.databind)
    implementation(libs.libphonenumber)
    implementation(libs.android.smsmms)

    annotationProcessor(libs.glide.compiler)
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    testImplementation(libs.junit4)
    testImplementation(libs.assertj.core)
    testImplementation(libs.mockito.core)
    testImplementation(libs.mockito.inline)
    testImplementation(libs.androidx.arch.core.testing)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.androidx.multidex)
    androidTestImplementation(libs.androidx.multidex.instrumentation)
    androidTestImplementation(libs.dexmaker)
    androidTestImplementation(libs.dexmaker.mockito)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.assertj.core) {
        exclude(group = "org.hamcrest", module = "hamcrest-core")
    }
    androidTestImplementation(libs.assertj.android) {
        exclude(group = "org.hamcrest", module = "hamcrest-core")
        exclude(group = "com.android.support", module = "support-annotations")
    }
}

hilt {
    enableAggregatingTask = true
}

android {
    namespace = "org.smssecure.smssecure"
    compileSdk = 36
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "org.smssecure.smssecure"
        versionCode = 216
        versionName = "0.16.14-unstable"
        buildConfigField("boolean", "MODERN_CRYPTO_WRITES", "true")
        minSdk = 23
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
        ndk { abiFilters.add("arm64-v8a") }
        externalNativeBuild { cmake { arguments.add("-DANDROID_STL=none") } }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }
    bundle { language { enableSplit = false } }

    lint {
        baseline = file("lint-baseline.xml")
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                it.jvmArgs(
                    "--add-opens=java.base/java.lang=ALL-UNNAMED",
                    "--add-opens=java.base/java.lang.reflect=ALL-UNNAMED",
                    "--add-opens=java.base/java.io=ALL-UNNAMED",
                    "--add-opens=java.base/java.util=ALL-UNNAMED",
                    "--add-opens=java.base/java.text=ALL-UNNAMED",
                    "--add-opens=java.base/java.security=ALL-UNNAMED",
                    "--add-opens=java.base/javax.crypto=ALL-UNNAMED",
                    "--add-opens=java.base/javax.crypto.spec=ALL-UNNAMED",
                    "--add-opens=java.base/java.lang.invoke=ALL-UNNAMED",
                    "--add-opens=java.base/jdk.internal.access=ALL-UNNAMED",
                    "--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED",
                    "--add-opens=java.base/jdk.internal.reflect=ALL-UNNAMED",
                    "--add-opens=java.base/java.util.concurrent=ALL-UNNAMED",
                    "--add-opens=java.base/java.lang.module=ALL-UNNAMED",
                    "-Dnet.bytebuddy.experimental=true"
                )
            }
        }
    }

    packaging {
        resources {
            excludes += listOf(
                "LICENSE.txt", "LICENSE", "NOTICE", "asm-license.txt", "META-INF/LICENSE", "META-INF/NOTICE",
                "**/*.dll", "**/*.dylib", "**/*_amd64.so", "**/*_aarch64.so"
            )
        }
        jniLibs { excludes += "**/libsignal_jni_testing.so" }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            testProguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            testProguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        create("phaseARelease") {
            initWith(getByName("release"))
            buildConfigField("boolean", "MODERN_CRYPTO_WRITES", "false")
            versionNameSuffix = "-phase-a"
            matchingFallbacks += "release"
        }
    }
}

val generatedBuildConfig = layout.buildDirectory.dir("generated/source/buildConfig")
val verifyCryptoReleaseStages = tasks.register<VerifyCryptoPolicy>("verifyCryptoReleaseStages") {
    group = "verification"
    description = "Verifies normal builds enable modern crypto writes and Phase A builds disable them."
    dependsOn("generateDebugBuildConfig", "generatePhaseAReleaseBuildConfig")
    debugBuildConfig.set(generatedBuildConfig.map { it.file("debug/org/smssecure/smssecure/BuildConfig.java") })
    phaseABuildConfig.set(generatedBuildConfig.map { it.file("phaseARelease/org/smssecure/smssecure/BuildConfig.java") })
}

val appJavaSources = fileTree("src/main/java") { include("**/*.java") }
val appProductionSources = fileTree("src/main/java") { include("**/*.java", "**/*.kt") }
val repositoryRootPath = rootDir.absolutePath
val checkNoNewAsyncTaskUsage = tasks.register<CheckNoAsyncTaskUsage>("checkNoNewAsyncTaskUsage") {
    group = "verification"
    description = "Fails when app source uses the removed android.os.AsyncTask API."
    sources.from(appJavaSources)
    repositoryRoot.set(repositoryRootPath)
}

val checkAndroidDeprecationAllowlist = tasks.register<CheckAndroidDeprecationAllowlist>("checkAndroidDeprecationAllowlist") {
    group = "verification"
    description = "Verifies localized Android deprecation suppressions match the documented allowlist."
    allowlist.set(layout.projectDirectory.file("config/android-deprecation-allowlist.tsv"))
    sources.from(appJavaSources)
    repositoryRoot.set(repositoryRootPath)
}

val checkConversationListArchitecture = tasks.register<CheckConversationListArchitecture>("checkConversationListArchitecture") {
    group = "verification"
    description = "Prevents direct data-source and legacy observation coupling in migrated conversation-list UI code."
    sources.from(fileTree("src/main/java/org/smssecure/smssecure/ui/conversationlist") { include("**/*.java") })
    sources.from(file("src/main/java/org/smssecure/smssecure/ConversationListFragment.java"))
    sources.from(file("src/main/java/org/smssecure/smssecure/ConversationListModelAdapter.java"))
    sources.from(file("src/main/java/org/smssecure/smssecure/ConversationListEntryMapper.java"))
}

val checkEventBusAllowlist = tasks.register<CheckEventBusAllowlist>("checkEventBusAllowlist") {
    group = "verification"
    description = "Prevents new EventBus coupling and verifies documented compatibility consumers."
    allowlist.set(layout.projectDirectory.file("config/eventbus-allowlist.tsv"))
    sources.from(appJavaSources)
    repositoryRoot.set(repositoryRootPath)
}

val checkConversationThreadArchitecture = tasks.register<CheckConversationThreadArchitecture>("checkConversationThreadArchitecture") {
    group = "verification"
    description = "Prevents direct data-source and legacy observation coupling in migrated conversation-thread UI code."
    sources.from(fileTree("src/main/java/org/smssecure/smssecure/ui/conversationthread") { include("**/*.java") })
    sources.from(file("src/main/java/org/smssecure/smssecure/ConversationFragment.java"))
    sources.from(file("src/main/java/org/smssecure/smssecure/ConversationModelAdapter.java"))
}

val checkConversationScreenArchitecture = tasks.register<CheckConversationScreenArchitecture>("checkConversationScreenArchitecture") {
    group = "verification"
    description = "Prevents direct data-source ownership and secret-bearing state in migrated conversation screen code."
    stateSources.from(fileTree("src/main/java/org/smssecure/smssecure/ui/conversationscreen") { include("**/*.java") })
    activitySource.set(layout.projectDirectory.file("src/main/java/org/smssecure/smssecure/ConversationActivity.java"))
}

val checkModernArchitectureBoundaries = tasks.register<CheckModernArchitectureBoundaries>("checkModernArchitectureBoundaries") {
    group = "verification"
    description = "Prevents service-locator restoration, replaying secret state, and passphrase-gate overrides."
    sources.from(appProductionSources)
    passphraseActivity.set(layout.projectDirectory.file(
        "src/main/java/org/smssecure/smssecure/PassphraseRequiredActionBarActivity.java"))
    manifest.set(layout.projectDirectory.file("src/main/AndroidManifest.xml"))
    conversationListGraph.set(layout.projectDirectory.file(
        "src/main/res/navigation/conversation_list_navigation.xml"))
}

val checkUiDataAccessAllowlist = tasks.register<CheckUiDataAccessAllowlist>("checkUiDataAccessAllowlist") {
    group = "verification"
    description = "Rejects new direct database, Loader, or preference ownership in UI components."
    allowlist.set(layout.projectDirectory.file("config/ui-data-access-allowlist.tsv"))
    sources.from(appProductionSources.matching {
        include("**/*Activity.java", "**/*Activity.kt", "**/*Fragment.java", "**/*Fragment.kt")
    })
    repositoryRoot.set(repositoryRootPath)
}

val checkRetainedActivityInventory = tasks.register<CheckRetainedActivityInventory>("checkRetainedActivityInventory") {
    group = "verification"
    description = "Requires every Activity outside the application NavHost to have an exact platform or security contract."
    inventory.set(layout.projectDirectory.file("config/retained-activities.tsv"))
    manifest.set(layout.projectDirectory.file("src/main/AndroidManifest.xml"))
    tests.from(fileTree("src/test") { include("**/*Test.java", "**/*Test.kt") })
    knownTaskGates.set("checkRetainedActivityInventory,checkModernArchitectureBoundaries")
}

val checkHostDestinationSecurityPolicy = tasks.register<CheckHostDestinationSecurityPolicy>("checkHostDestinationSecurityPolicy") {
    group = "verification"
    description = "Requires every host destination to declare and preserve its security contract."
    policy.set(layout.projectDirectory.file("config/host-destinations.tsv"))
    navigationGraph.set(layout.projectDirectory.file("src/main/res/navigation/conversation_list_navigation.xml"))
    manifest.set(layout.projectDirectory.file("src/main/AndroidManifest.xml"))
    stateAndDestinationSources.from(fileTree("src/main/java") {
        include("**/*Destination.java", "**/*Destination.kt", "**/*StateStore.java", "**/*StateStore.kt")
    })
}

tasks.withType<JavaCompile>().configureEach {
    dependsOn(checkNoNewAsyncTaskUsage, checkAndroidDeprecationAllowlist,
              checkConversationListArchitecture, checkConversationThreadArchitecture,
              checkConversationScreenArchitecture, checkEventBusAllowlist,
              checkModernArchitectureBoundaries, checkUiDataAccessAllowlist,
              checkRetainedActivityInventory, checkHostDestinationSecurityPolicy)
}

val signingProperties = Properties()
val signingPropertiesFile = rootProject.file("signing.properties")
if (signingPropertiesFile.canRead()) {
    signingPropertiesFile.inputStream().use(signingProperties::load)
}

fun credentialFor(key: String): String? =
    signingProperties.getProperty(key)?.trim()?.takeIf { it.isNotEmpty() }
        ?: System.getenv("SILENCE_$key")?.trim()?.takeIf { it.isNotEmpty() }

val storeFileValue = credentialFor("STORE_FILE")
val storePasswordValue = credentialFor("STORE_PASSWORD")
val keyAliasValue = credentialFor("KEY_ALIAS")
val keyPasswordValue = credentialFor("KEY_PASSWORD")

if (storeFileValue != null && storePasswordValue != null && keyAliasValue != null && keyPasswordValue != null) {
    val releaseSigning = android.signingConfigs.create("release") {
        storeFile = rootProject.file(storeFileValue)
        storePassword = storePasswordValue
        keyAlias = keyAliasValue
        keyPassword = keyPasswordValue
    }
    android.buildTypes.getByName("release").signingConfig = releaseSigning
} else if (signingPropertiesFile.canRead()) {
    println("signing.properties found but some entries are missing; checked environment variables as SILENCE_* as well.")
} else {
    println("signing credentials missing: define SILENCE_* environment variables or signing.properties entries.")
}
