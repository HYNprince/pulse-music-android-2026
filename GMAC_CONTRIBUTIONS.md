# Gmac — Original contributions, attribution & testing

**Project:** Gmac Android Music Player (an independently maintained MetroList fork)  
**Maintainer:** V Gokulbalaji (GitHub: [HYNprince](https://github.com/HYNprince))  
**Source:** https://github.com/HYNprince/pulse-music-android-2026  
**Upstream project:** https://github.com/MetrolistGroup/Metrolist  
**Original MetroList base revision:** `876e355181fd3b04e7063b10442fef1b721d53b3`  
**License:** [GNU GPL version 3](LICENSE) — the source remains a GPL-licensed derivative work.

## Attribution and scope of ownership

This is not an original-from-scratch player. The original authors retain ownership of MetroList's player, streaming integration, offline/cache engine, lyrics, search, library, design elements, and other retained source/assets. Gmac is a project name for the separately maintained fork. The maintainer may identify and take credit for **Gmac-specific modifications and project maintenance**, not claim exclusive copyright in the entire upstream codebase.

The Gmac modifications remain under the GPL requirements applicable to this combined work. Keep original headers and this license when sharing source or builds. If distributing binaries to others, provide the complete corresponding source in accordance with GPLv3, including build instructions and modification notices.

**Branding boundary:** Current bat-themed test icon derives from Batman-style third-party artwork and is **not cleared for public/commercial releases**. This copyright/trademark question is separate from the GPL source license; do not represent the icon as owned. No logo changes are made by this documentation.

## Gmac-specific changes (2026)

### v0.3 — Listening History CSV Export

- Added a history export action using Android's document picker.
- Streams all local play events via paged Room queries rather than loading the full history into memory.
- Escapes CSV data including quotes and malicious spreadsheet-leading formula characters.
- Unit and Room regression tests check formatting and repeated-play export behavior.

Relevant source:
- `app/src/main/kotlin/com/metrolist/music/utils/HistoryCsvExporter.kt`
- `app/src/main/kotlin/com/metrolist/music/viewmodels/HistoryViewModel.kt`
- `app/src/test/kotlin/com/metrolist/music/utils/HistoryCsvExporterTest.kt`

### v0.4 — Smart Mix Playlist Generator (in draft PR #2)

- Four local-first presets: Favorites, Rediscover, Balanced Mix, Offline Mix.
- Explainable and reproducible ranking from listening-event totals, recency, favorites, and song availability.
- Bounded Room aggregate query; recommendation work is performed away from the UI thread.
- Artist-diversity limit and song-count options with deterministic regenerate action.
- Creates an editable snapshot in the existing local playlist database; does not sync to YouTube without user action.
- Regression tests for ranking/filtering and database playlist persistence.

Relevant source:
- `app/src/main/kotlin/com/metrolist/music/smartmix/SmartMixEngine.kt`
- `app/src/main/kotlin/com/metrolist/music/ui/component/GmacSmartMixDialog.kt`
- `app/src/test/kotlin/com/metrolist/music/smartmix/SmartMixEngineTest.kt`
- `app/src/test/kotlin/com/metrolist/music/db/SmartMixDatabaseIntegrationTest.kt`

## How to verify

1. Open [v0.4 GitHub Actions](https://github.com/HYNprince/pulse-music-android-2026/actions/workflows/gmac-v04-verify.yml) and verify the tests, R8-optimized APK compilation, signature, and separate package ID.
2. Install the optimized development artifact alongside the previous beta; do not overwrite existing app data.
3. On the phone, play/like several songs and open Library → Playlists → Create Smart Mix.
4. Try all four presets, counts 10/25/50, Offline Only, and Regenerate. Confirm no duplicate IDs and reasonable explanations.
5. Save a mix and reopen the playlist; confirm track order and persistence after restarting the app.
6. Confirm behavior with no history, an all-offline library, no offline music, and a library concentrated around one artist.
7. Compare scrolling, search, song switching, and playlist opening against v0.3; note device model and any crashes or slowdowns.

**Status:** Automated CI green on v0.4 preview before this documentation update; tests must be rerun after the new Room integration test. Full device acceptance is not yet claimed. This repository is not distributing a public release here.

## Resume description (accurate attribution)

**Gmac — Android Music Player (Open-Source MetroList Fork) | Kotlin, Jetpack Compose, Room, GitHub Actions**

- Extended a GPL-3.0 Android music-player fork with a local Smart Mix recommendation engine combining listening-history signals, favorites, offline availability, and artist diversity.
- Added paginated CSV listening-history export and automated Kotlin/Room regression tests; built and verified release-optimized preview APKs with GitHub Actions.
- Kept MetroList upstream attribution and licensed source intact; features are undergoing personal Android-device validation.

**Do not claim:** to have created the MetroList engine, original streaming integrations, all app UI, all downloads/lyrics functionality, public adoption, benchmarked speed gains, or a commercially cleared Batman logo.
