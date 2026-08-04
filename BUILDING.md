# Building Silence

Silence uses the checked-in Gradle wrapper. You do not need to install Gradle separately.

## Prerequisites

Install the following tools:

* JDK 17
* Android SDK Platform 36
* Android SDK Build Tools
* Android NDK 28.2.13676358
* CMake 3.22.1

Android Studio can install the Android components through its SDK Manager.

## Command-line build

1. Clone the repository:

   ```console
   git clone https://github.com/zdev-code/silencesms.git
   cd silencesms
   ```

2. If Android Studio has not generated it, create `local.properties` in the repository root and set the Android SDK path:

   ```properties
   sdk.dir=<path-to-android-sdk>
   ```

3. Build the debug APK on Linux or macOS:

   ```console
   ./gradlew assembleDebug
   ```

   On Windows PowerShell, use `./gradlew.bat assembleDebug`.

## Release builds

The repository defines two release variants:

* `assembleRelease` builds the standard release with modern local-crypto writes enabled.
* `assemblePhaseARelease` builds a compatibility release with modern local-crypto writes disabled. It retains modern readers while preserving the legacy write policy for staged deployment or rollback.

Verify the build-policy flags and build both variants on Linux or macOS with:

```console
./gradlew verifyCryptoReleaseStages assemblePhaseARelease assembleRelease
```

On Windows PowerShell, use:

```console
./gradlew.bat verifyCryptoReleaseStages assemblePhaseARelease assembleRelease
```

Both variants retain legacy and modern readers so data remains readable across upgrades and rollbacks. Use the compatibility variant only when that write policy is intentional.

## Setting up a development environment

[Android Studio](https://developer.android.com/studio) is the recommended development environment.

1. Install Android Studio.
2. Install the Android components listed under Prerequisites using **Tools > SDK Manager**.
3. Select JDK 17 as the Gradle JDK in Android Studio settings.
4. Choose **File > New > Project from Version Control** and clone `https://github.com/zdev-code/silencesms.git`, or open an existing clone.
5. Allow Android Studio to synchronize the Gradle project.

The project uses a legacy source layout configured in `build.gradle`; do not move source files into a generated modern layout simply to satisfy IDE suggestions.

## Contributing code

See [CONTRIBUTING.md](CONTRIBUTING.md) for pull request and validation guidance.
