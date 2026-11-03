# Gmac — MetroList-based Android fork

**Gmac** is a rebranded, independently maintained fork of the open-source [MetroList](https://github.com/MetrolistGroup/Metrolist) Android music player.

## What was changed

- Gmac app name and a Batman-style bat-emblem test launcher artwork (not cleared for public distribution)
- Independent Android application ID: `com.gmac.player`
- Updated visible application labels and selected user-facing strings
- Disabled the upstream updater so this fork will not fetch MetroList releases
- Removed the MetroList-owned `metrolist.cc` verified app-link handler and its Discord OAuth callback
- Retained MetroList's original playback, search, library, lyrics and player code

## Copyright and GPL-3.0

**This fork is not copyright-free.** The original MetroList authors retain their copyright in the original code and assets. Our changes are released under the same **GNU GPL version 3**, as required when distributing this combined work. The original repository's `LICENSE`, original `README.md` and attribution remain in the source.

Upstream: https://github.com/MetrolistGroup/Metrolist

Base upstream revision: `876e355181fd3b04e7063b10442fef1b721d53b3`

This application is not endorsed by MetroList, Google, YouTube or Spotify. It uses a third-party YouTube Music integration, which can be unstable or restricted by the relevant service terms. The source license does not itself grant rights to copyrighted recordings or lyrics.

## Gmac-only contributions

- **v0.3 (development):** Streaming local listening-history CSV export and regression tests.
- **v0.4 (development):** Local Smart Mix recommendation engine (scoring, variety, filtering, deterministic regeneration, explainable picks), Room aggregation, Jetpack Compose preview, and saved playlist snapshots.
- Dedicated GitHub Actions CI for unit tests and separate optimized Android preview artifacts.

Implementation details and verification steps are in [GMAC_CONTRIBUTIONS.md](GMAC_CONTRIBUTIONS.md). Original MetroList copyright and upstream features are not claimed as Gmac-original.

## Building

See [GitHub Actions](https://github.com/HYNprince/pulse-music-android-2026/actions) for development APK workflows. v0.4 uses an **R8-optimized, debug-key-signed beta**, not a production-signed Google Play release. The original Pulse prototype remains on `main`; upstream snapshot provenance is in [PULSE_UPSTREAM_SOURCE_NOTICE.md](PULSE_UPSTREAM_SOURCE_NOTICE.md).

## Beta artwork and trademark caveat

The bat-emblem test launcher was traced from user-provided Batman-style artwork. The image is **not licensed or cleared for commercial/public branding**; public releases should replace it with a separately cleared original logo. Changing the image or app name does not transfer any Batman, MetroList, YouTube, or music copyrights to the fork author.

## Performance beta

The `beta` build type uses R8 code/resource shrinking and `isDebuggable = false`. Its test package has a `.beta` application ID suffix to protect existing Gmac installation data. Device testing is still needed; a lag-free result is not guaranteed.
