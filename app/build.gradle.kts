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
            "org.greenrobot.eventbus" to "EventBus coupling"
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
        listOf("DatabaseFactory", "android.database.Cursor", "androidx.loader", "org.greenrobot.eventbus")
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

plugins {
    alias(libs.plugins.android.application)
}

dependencies {
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
    implementation(libs.androidx.loader)
    implementation(libs.androidx.cursoradapter)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.lifecycle.livedata)
    implementation(libs.androidx.lifecycle.viewmodel.savedstate)
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

    buildFeatures { buildConfig = true }
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

tasks.withType<JavaCompile>().configureEach {
    dependsOn(checkNoNewAsyncTaskUsage, checkAndroidDeprecationAllowlist,
              checkConversationListArchitecture, checkConversationThreadArchitecture,
              checkConversationScreenArchitecture, checkEventBusAllowlist)
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
