# Pulse Music Starter — Android v0.1

**Working title only**. Original Spotify/MetroList-inspired dark interface; no copied UI assets, app icon, or third-party music-player source. Rename freely.

## Working features in source

- Native Kotlin + Jetpack Compose dark UI: Home, Search, Library, mini-player, full-screen player
- Android Media3 media-session foreground service for lock-screen/background playback
- Local audio import through the Android document picker (persistent URI permission, no broad storage permission)
- A deliberately **tiny** demo online catalog: two classical recordings with documented reuse permissions
- Streaming, in-app offline downloads for both demo recordings (foreground download while app is active)
- Downloaded files remain available in app-private storage after app restarts
- Favorites persist locally, search filters the starter catalog and imported files
- No advertisements, no analytics SDK, no backend, no account required

## Not implemented yet (do not advertise these as finished)

- Spotify OAuth, playlists, JSON import, multi-user sync, listening rooms
- Full licensed catalog, automatic search across internet sources
- Playlist creation or cloud syncing
- Media3 DownloadService for **background** downloading, retries and resume after interruption
- Guaranteed fault-free playback or scaling to large audiences
- Production accessibility audits and device test matrix


## No Android Studio? Build APK free on GitHub Actions

The `.github/workflows/build-apk.yml` file builds a **debug APK** on GitHub's hosted Linux runner. You don't need Android Studio or Android SDK on your Mac. GitHub's standard hosted runner is free for public repositories, within applicable service terms. An internet connection and a GitHub account are needed.

1. Create a **public, empty** GitHub repository (do not pre-add README/license).
2. Unzip this project. Upload **the contents inside** `PulseMusicStarter/` to the repository's root, preserving the `.github/workflows/build-apk.yml` file. Do **not** upload the ZIP as one file, and do not nest everything under an extra `PulseMusicStarter` folder.
3. The workflow runs automatically on a push to `main` or `master`. Alternatively go to the repository's **Actions** tab → **Build Android APK** → **Run workflow**.
4. If the workflow succeeds, open its latest run → **Artifacts** → download `Pulse-Music-debug-APK`. Extract that downloaded ZIP to get `app-debug.apk`.
5. Send/install the debug APK on your **Android** phone (not your iPhone). Android may ask you to allow installs from the file manager/browser. Only install APKs from source you trust.

**First cloud build is not yet verified.** If the workflow fails, open its failed step and share the error log. We'll correct the code/config based on the actual compiler output. Debug APKs are for testing, not a polished release to distribute broadly; a signed release build and testing come next.

## Build on macOS

1. Install recent **Android Studio** and Android SDK Platform **36** with JDK 17.
2. Unzip the archive and choose **File > Open** -> `PulseMusicStarter`.
3. Allow Gradle sync to download dependencies. Requires an internet connection and a compatible Gradle installation; Gradle wrapper properties and bootstrap scripts are supplied; on the first command-line run `./gradlew` downloads the official Gradle wrapper JAR and Gradle 8.13 (network required). If Android Studio cannot find the wrapper JAR, run `./gradlew --version` once in Terminal before re-syncing.
4. On the first build if prompted about a missing Gradle wrapper jar, open Terminal in the project folder and run `./gradlew --version`, then re-open/sync Android Studio.
5. Choose an Android device (Android 8+) or emulator, and click **Run ▶**.
6. Use **Import music** to pick existing audio files, or tap a preloaded recording to stream it. Use Download to save one for offline playback.
7. To build an APK: **Build > Build Bundle(s) / APK(s) > Build APK(s)**. Debug APK appears under `app/build/outputs/apk/debug/`.

If Android Studio reports Gradle or SDK mismatches, install the required SDK via **SDK Manager** and use bundled JDK 17, then re-sync. No APK was compiled in the project generation environment; first Gradle sync/build on your Mac is the verification step.

## Track credits (two test recordings only)

- **Für Elise**, Ludwig van Beethoven, performance uploaded by Gaodifan — [Wikimedia Commons](https://commons.wikimedia.org/wiki/File:FurElise.ogg), CC0 1.0.
- **Moonlight Sonata**, Ludwig van Beethoven, Mutopia recording — [Wikimedia Commons](https://commons.wikimedia.org/wiki/File:Moonlight_Sonata.ogg), marked public domain.

Both files are fetched on demand directly from Wikimedia Commons; no audio is repackaged in this ZIP. Internet availability, source access policies and licensing should be revalidated before public distribution. Offline files are app-private and normally removed on uninstall.

## Architectural layout

- `MusicData.kt`: media catalog, device imports, favorites storage and offline files
- `PlaybackService.kt`: Media3 session + ExoPlayer in an Android foreground-capable service
- `PlayerEngine.kt`: MediaController / Compose player state
- `MainActivity.kt`: Compose navigation, player UI and UI actions

## Responsible roadmap

1. Fix build/device issues based on your Android Studio output. Add tests, error reporting and accessibility.
2. Upgrade downloads to Media3 `DownloadManager` / `DownloadService` and verify resumption, checksums, and handling of network/power interruptions.
3. Add Spotify data export JSON playlist *metadata* importer (no Spotify audio ripping), then authorized OAuth under Spotify's developer access rules.
4. Integrate additional approved music sources or direct artist upload permissions, with a server-side catalog and license audit.
5. Implement playlists, queue editor, scalable listening rooms and load testing.

## Licensing

This starter's **original application code** is offered under MIT (see LICENSE). Media3/Compose/Android dependencies retain their respective licenses; included media source links retain their own terms. Do **not** mirror MetroList UI assets or scrape/rehost Spotify/YouTube audio.
