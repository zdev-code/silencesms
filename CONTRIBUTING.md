# Contributing to Silence

## Translations

Submit translation fixes through GitHub pull requests against `main`.

## Submitting bug reports

1. Search [Silence issues](https://github.com/zdev-code/silencesms/issues) first to make sure the problem has not already been reported.
2. If the behavior may originate upstream, optionally search [Signal Android issues](https://github.com/signalapp/Signal-Android/issues).
3. Open a GitHub issue and include reproduction steps, the actual and expected behavior, the device model, Android version, and Silence version.

If you cannot get a debug log from Settings, launch Silence and capture logs for its process. On Linux or macOS:

```console
silence_pid="$(adb shell pidof -s org.smssecure.smssecure)"
adb logcat --pid="$silence_pid"
```

On Windows PowerShell:

```powershell
$silencePid = (adb shell pidof -s org.smssecure.smssecure).Trim()
adb logcat --pid=$silencePid
```

Available `logcat` options depend on the Android version running on the device. If `--pid` is unavailable, use Android Studio's Logcat window and filter by the `org.smssecure.smssecure` package. Remove private message content and unrelated personal information before sharing a log.

## Submitting pull requests

Open pull requests from a feature branch against `main`. Describe the behavior change and the validation you performed.

Run the narrowest relevant tests while iterating. For app-source changes, run the JVM tests and build the debug APK before submitting:

```console
./gradlew test assembleDebug
```

On Windows PowerShell, use `./gradlew.bat test assembleDebug`. Changes to crypto, persistence, backup/restore, or the vendored libsignal module require their focused tests and the applicable release-stage checks described in [BUILDING.md](BUILDING.md).
