import java.security.MessageDigest
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.compile.JavaCompile

abstract class VerifyArtifactHash : DefaultTask() {
    @get:InputFiles
    abstract val artifacts: ConfigurableFileCollection

    @get:Input
    abstract val coordinate: Property<String>

    @get:Input
    abstract val expectedSha256: Property<String>

    @TaskAction
    fun verify() {
        val artifact = artifacts.files.find { it.name.endsWith(".aar") }
            ?: throw GradleException(
                "verifyLibsignalPin: could not resolve an AAR for ${coordinate.get()}"
            )
        val digest = MessageDigest.getInstance("SHA-256")
        artifact.inputStream().use { input ->
            val buffer = ByteArray(65536)
            var count = input.read(buffer)
            while (count != -1) {
                digest.update(buffer, 0, count)
                count = input.read(buffer)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (actual != expectedSha256.get()) {
            throw GradleException(
                """libsignal-android pin verification FAILED - refusing to build.
                  |  coordinate:       ${coordinate.get()}
                  |  expected SHA-256: ${expectedSha256.get()}
                  |  actual   SHA-256: $actual
                  |  artifact:         $artifact
                """.trimMargin()
            )
        }
        logger.lifecycle("verifyLibsignalPin: ${coordinate.get()} SHA-256 OK")
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.hilt.android) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

subprojects {
    extra["version_number"] = "2.4.0"
    extra["group_info"] = "org.whispersystems"
    extra["curve25519_version"] = "0.5.0"
}

allprojects {
    tasks.withType<JavaCompile>().configureEach {
        options.compilerArgs.removeAll(listOf("-Xlint:-deprecation", "-Xlint:-unchecked"))
        options.compilerArgs.addAll(listOf("-Xlint:deprecation", "-Xlint:unchecked"))
        options.isDeprecation = true
        options.isWarnings = true
    }
}

val libsignalPinnedCoordinate = "org.signal:libsignal-android:0.72.1"
val libsignalPinnedSha256 = "9859acc14aab4f4744abbc9ad150829afce090aa143a58a95af7da1767a6761f"
val libsignalPinConfiguration = configurations.detachedConfiguration(
    dependencies.create(libsignalPinnedCoordinate)
).apply {
    isTransitive = false
}

val verifyLibsignalPin = tasks.register<VerifyArtifactHash>("verifyLibsignalPin") {
    description = "Verifies the pinned libsignal-android AAR matches its known-good SHA-256."
    group = "verification"
    artifacts.from(libsignalPinConfiguration)
    coordinate.set(libsignalPinnedCoordinate)
    expectedSha256.set(libsignalPinnedSha256)
}

project(":app") {
    tasks.matching { it.name == "preBuild" }.configureEach {
        dependsOn(verifyLibsignalPin)
    }
}
