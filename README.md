# Music Player

A simple offline music player for Android phones: it plays the MP3 files stored on the phone, on the phone and in the car through Android Auto. No streaming, no account, no ads. Music never leaves the phone; the internet is used only if you turn on "Fill in missing details", which sends a short fingerprint and the length of a song that lacks its title, artist or album to the free AcoustID and MusicBrainz services.

## What it will do

Play the MP3 files stored on the phone, with search, playlists, a queue and resume, controlled from the notification, a widget, headset buttons and Android Auto. A long MP3 that holds a whole album can be split into one MP3 per song from its menu ("Split into songs"); the original is only deleted if you say yes. The full list is in the [requirements](docs/Requirements.md); what is built so far is in the [stories document](docs/Stories%20and%20Test%20Plan.md).

## Quick start

**Requirements:** Android Studio (stable) with the Android SDK Platform 37 (Studio offers to install it, and anything else the build needs, when it first syncs the project). A JDK 17 or newer: the one bundled with Android Studio works. Android 13 or newer on the phone or virtual phone.

1. Open the project folder in Android Studio and let the Gradle sync finish.
2. In the toolbar, pick the run configuration **app** and a device (see "Virtual phone" below, or "Install on a phone"), then press the green **Run** button (Shift+F10). If the configuration drop-down says "Add Configuration..." or has no **app** entry, run **File > Sync Project with Gradle Files** first; if it is still missing, create it with **Run > Edit Configurations... > + > Android App**, name `app`, module `song-player.app.main`, launch **Specified Activity** `app.songplayer.MainActivity` (a debug build has a second launcher icon, "Library report", which a default launch may open instead). If you cannot see the toolbar, turn it on at **View > Appearance > Toolbar**.
3. The first screen asks for access to the music on the device; allow it and the Library appears (an empty virtual phone shows "No MP3 files found").

On Windows, keep the project in a normal Windows folder such as `C:\dev\song_editor`, not on a `\\wsl.localhost\...` path.

## Install on a phone

1. On the phone, open **Settings > About phone > Software information** and tap **Build number** 7 times. Then open **Settings > Developer options** and turn on **USB debugging**.
2. Connect the phone by USB and accept "Allow USB debugging?". It appears in Android Studio's device list.
3. Select the phone in the toolbar's device drop-down and press the green **Run** button (Shift+F10). This installs the debug build and starts it. A debug build runs slower than the release build the store will get.
4. Copy MP3 files into the phone's **Music** folder with a file manager or over USB. The app finds them when it opens and whenever it returns to the front.

**Without a cable (Android 11 or newer):** put the phone and the computer on the same Wi-Fi network, then pair once.

1. On the phone, open **Settings > Developer options > Wireless debugging**, turn it on and accept the Wi-Fi prompt.
2. In Android Studio, open **Device Manager** and choose **Pair Devices Using Wi-Fi** (or open the device drop-down in the toolbar and choose it there).
3. On the phone, tap **Pair device with QR code** and scan the code Android Studio shows. If scanning does not work, tap **Pair device with pairing code** instead, and in Android Studio choose **Pair using pairing code** and enter the six digits.
4. The phone appears in the device list, and **Run** installs over Wi-Fi as it does over a cable.

Pairing is remembered. After the phone restarts or changes Wi-Fi network, turn Wireless debugging on again and the phone reconnects by itself; if it does not, run `adb connect <address and port shown on the Wireless debugging screen>`. Without Android Studio, the same pairing is `adb pair <address:port from the pairing screen>` followed by `adb connect <address:port from the Wireless debugging screen>`.

**Try it in the car (Android Auto).** A build that is not installed from Google Play is hidden by Android Auto until you allow it. On the phone open **Settings > Apps > See all apps > Android Auto > Advanced > Additional settings in the app**, scroll to **About**, tap **Version and permission info** 10 times and accept **Allow development settings?**. Then open the overflow menu (three dots), **Developer settings**, and turn on **Unknown sources**. Reconnect the phone to the car; Music Player appears among the media apps. Details: [Android Auto's testing guide](https://developer.android.com/training/cars/testing).

**Start from scratch** (to see the first-start screens again): uninstall the app with `%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe uninstall app.songplayer`, or remove it from the phone's app list, then Run again.

To measure the performance budgets on the phone (cold start, scrolling, memory, battery), run `powershell -ExecutionPolicy Bypass -File scripts\measure-budgets.ps1` with the phone connected; it asks you to scroll and play at the right moments and writes `.check-results\budgets.md`.

To test that the playlists survive a backup and a reinstall, run `powershell -ExecutionPolicy Bypass -File scripts\test-backup.ps1` with the phone connected (it uninstalls the app on the phone and restores it from a backup; read its header first).

## Run the checks

All commands run in the project folder. In a terminal, Gradle needs `JAVA_HOME` (see Configuration); in Android Studio the Gradle panel (View > Tool Windows > Gradle) needs nothing. Use `.\gradlew.bat` on Windows and `./gradlew` elsewhere.

**Everything in one go (start the emulator first):**

```text
.\gradlew.bat checkAll --continue
```

Runs the `core` tests, the `app` unit tests (the manifest and source rules among them), Android Lint and, with `-Emulator`, the emulator tests. **The emulator tests are skipped by default since 2026-11-02**, because the emulators keep getting stuck; the script then also compiles the emulator test sources, so a mistake in them still shows, and it does not record the emulator packages as passed. The script decides what to run: it remembers which commit each part last passed at (`.check-results\green.txt`) and runs only what the changes since then can affect. Documents and scripts need no tests; a change to tests only runs the JVM tests; other code runs the JVM tests and lint; the emulator tests run for the package whose code or tests changed (`playback`, `ui`, `library`, `text`, `playlist`, `widget`; library code also runs `playback`, text code also runs `library`); code in `core` runs the packages that use it; the shared parts (logging, errors, test fixtures, the manifest, build files, other resources, the wiring; the words file `strings.xml` runs the JVM and `ui` checks only; `MainActivityTest` runs only in a full run: `-Full`, a shared-code change, or a first run) runs everything; a story commit is not special and runs what its changes affect; `-Full` runs everything, and a full run is expected before a release. After a run in which only emulator tests failed, the next run repeats just those test classes when nothing but their files changed meanwhile (the rest passed on the same code); if they pass, the failed run's scopes count as passed. `latest.md` says what was run and what was skipped and why. `checkAll` itself, from Gradle, always runs everything. Speed limits in the emulator tests are checked only on request, because emulator timings vary too much to mean anything (the real check is on the phone): add `-Pandroid.testInstrumentationRunnerArguments.songplayer.perf=1` to the Gradle command to make them strict. To sync with origin first and record the results in `.check-results\latest.md`, run `powershell -ExecutionPolicy Bypass -File scripts\check-all.ps1` (details at the top of the script). The script runs the emulator tests on an emulator only; with just a phone connected it stops, and `-Phone` (or `-Device <serial>`) runs them on the phone on purpose. `--continue` reports every failure instead of stopping at the first. In Android Studio it is in the Gradle panel under **song-player > Tasks > verification > checkAll**.

**Unit tests (JVM, no phone; the first run downloads Gradle and the dependencies):**

```text
.\gradlew.bat :core:test :app:testDebugUnitTest
```

Expected: `BUILD SUCCESSFUL`. Reports: `core/build/reports/tests/test/index.html` and `app/build/reports/tests/testDebugUnitTest/index.html`. The `app` tests build the release manifest first, automatically.

On a machine without the Android SDK (for example plain Linux or WSL), only the `core` tests run:

```text
./gradlew --configure-on-demand :core:test
```

**Lint:**

```text
.\gradlew.bat :app:lintDebug
```

Expected: `BUILD SUCCESSFUL`. Report: `app/build/reports/lint-results-debug.html`. In Android Studio, Run Anything (press Ctrl twice) with `gradle :app:lintDebug` also works.

**Virtual phone verification:**

1. Open **Device Manager** (the phone icon in the right sidebar). Click **+**, then **Create Virtual Device**.
2. Choose a **Phone** such as Pixel 8, then a system image **API 35** (x86_64); click **Download** next to it if needed. Finish.
3. Click the **▶** next to the device and wait for the home screen. If it fails with a virtualization error, turn on virtualization in the BIOS and enable the Windows features "Windows Hypervisor Platform" and "Virtual Machine Platform".
4. Run the automated test: right-click `MainActivityTest` (in `app/src/androidTest`) and choose **Run**, or run
   ```text
   .\gradlew.bat :app:connectedDebugAndroidTest
   ```
   Expected: the emulator tests pass. Report: `app/build/reports/androidTests/connected/debug/index.html`.
5. Check by eye: select the virtual device in the toolbar, press **Run**, and confirm the app starts and asks for access to music.

Tests and checks that need a real phone, the user's music files or a car are opt-in and are described in the stories document.

## Build a release

Run on the Windows PC (the release build is not done in WSL). The bundle is the file you upload to Google Play.

1. Once, make the upload key **outside the repository** and back it up (Play can reset a lost key, but slowly): `keytool -genkeypair -v -keystore C:\Users\<you>\keys\upload-key.jks -alias upload -keyalg RSA -keysize 2048 -validity 10000` (`keytool` is in Android Studio's `jbr\bin`).
2. Add its four settings to `local.properties` (see Configuration; use `/` in the path).
3. In the repository folder: `.\gradlew.bat :app:bundleRelease`. The bundle is `app\build\outputs\bundle\release\app-release.aab`; it carries the R8 mapping file. Without the settings it is built unsigned.
4. For a smoke test on the phone, build an installable release with `.\gradlew.bat :app:assembleRelease` (signed with the same key) and install the APK from `app\build\outputs\apk\release\`. Check: the Library opens, a song plays, the notification and the widget work, a playlist survives closing the app, the song menu and Settings open, "Copy diagnostics" works.
5. Raise `versionCode` (and `versionName`) in `app/build.gradle.kts` for every upload; Google Play refuses a repeated `versionCode`.

## Configuration

| Setting | Required for | Set in | If missing |
|---|---|---|---|
| `JAVA_HOME` | Running `gradlew` from a terminal | Your shell or system environment: the folder of a JDK 17 or newer, for example Android Studio's `jbr` folder | The wrapper prints "JAVA_HOME is not set". Android Studio's Gradle panel is not affected |
| `sdk.dir` in `local.properties` | Building the `app` module | Created by Android Studio on first sync; not committed | The `app` build fails with "SDK location not found" |
| `release.storeFile`, `release.storePassword`, `release.keyAlias`, `release.keyPassword` in `local.properties` | Signing the release bundle (the upload key) | `local.properties`; not committed; the key file stays outside the repository | The release bundle is built unsigned; Play refuses it. Debug builds and the tests are not affected |
| `acoustid.key` in `local.properties` | The song-details lookup (a free application key from acoustid.org/new-application) | `local.properties`; not committed | The build and the tests work; the lookup is unavailable and sends nothing |

## Project layout

```text
core/    Pure Kotlin/JVM logic (no Android code): the library model, sync rules, sorting and search, queue building, logging and error types; playlists join it
app/     The Android app: the screens, the playback service, the database and the wiring; the widget and Android Auto join it
docs/    Requirements, UX design with mockups, technical design, stories and test plan
cli/     splitmp3 (the command-line splitter, the reference for the in-app one) and verifysplit (checks that the songs are the original's frames); its own README
gradle/  Gradle wrapper and the version catalog (libs.versions.toml)
```

## Documentation

- [Requirements](docs/Requirements.md): what the player does and why.
- [UX design](docs/UX%20Design.md): screens and behavior, with [mockups](docs/mockups/index.html).
- [Technical design](docs/Technical%20Design.md): how it works, the tools chosen and why, and the tool versions.
- [Stories and test plan](docs/Stories%20and%20Test%20Plan.md): scope, status, acceptance criteria and tests.
- [Splitter tool](cli/README.md): the command-line MP3 splitter and the split checker.

## Status and limits

Under development: the player features are being added one story at a time, and the checks above are the current state of the project. Not yet published on Google Play. Scope (Android 13 and newer, phones, MP3 only) is in the requirements.
