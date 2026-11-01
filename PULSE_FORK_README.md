# Gmac — MetroList-based Android fork

**Gmac** is a rebranded, independently maintained fork of the open-source [MetroList](https://github.com/MetrolistGroup/Metrolist) Android music player.

## What was changed

- Gmac app name and an original green original silver waveform launcher/app logo
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

## Building

See `https://github.com/HYNprince/pulse-music-android-2026/actions` for our separate test APK workflow. This is a **debug build**, not a production-signed Google Play release. The original Pulse prototype remains available on the repository's `main` branch, and the unmodified MetroList snapshot remains on `metrolist-source`.
