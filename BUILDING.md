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
   ./gradlew :app:assembleDebug
   ```

   On Windows PowerShell, use `./gradlew.bat :app:assembleDebug`.

## Release builds

Build the production release with:

On Linux or macOS:

```console
./gradlew :app:assembleRelease
```

On Windows PowerShell, use:

```console
./gradlew.bat :app:assembleRelease
```

The production release writes modern local-crypto formats and retains legacy readers so direct upgrades
from older installations remain supported. The Phase A compatibility artifact has already shipped and
is retained as a signed release artifact rather than rebuilt from current source.

## Setting up a development environment

[Android Studio](https://developer.android.com/studio) is the recommended development environment.

1. Install Android Studio.
2. Install the Android components listed under Prerequisites using **Tools > SDK Manager**.
3. Select JDK 17 as the Gradle JDK in Android Studio settings.
4. Choose **File > New > Project from Version Control** and clone `https://github.com/zdev-code/silencesms.git`, or open an existing clone.
5. Allow Android Studio to synchronize the Gradle project.

The application is the `app` module and uses the standard Android source-set layout under `app/src/`.

## Contributing code

See [CONTRIBUTING.md](CONTRIBUTING.md) for pull request and validation guidance.
