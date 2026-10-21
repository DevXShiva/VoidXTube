<p align="center">
  <img src=".github/assets/voidtube/logo.png" width="180" alt="VoidTube logo">
</p>

<h1 align="center">VoidTube</h1>

<p align="center"><b>Your Media. Your Control.</b><br><sub>By VoidXDevs</sub></p>

<p align="center">
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-MIT-8B5CF6?style=for-the-badge" alt="License: MIT"></a>
  <a href="https://github.com/DevXShiva/VoidXTube/releases/latest"><img src="https://img.shields.io/github/v/release/DevXShiva/VoidXTube?style=for-the-badge&label=release&color=8B5CF6" alt="Latest release"></a>
  <a href="https://github.com/DevXShiva/VoidXTube/actions/workflows/CI.yml"><img src="https://img.shields.io/github/actions/workflow/status/DevXShiva/VoidXTube/CI.yml?style=for-the-badge&label=build" alt="Build status"></a>
  <a href="https://t.me/VoidXDevs"><img src="https://img.shields.io/badge/Telegram-VoidXDevs-22D3EE?style=for-the-badge&logo=telegram" alt="Telegram"></a>
</p>

VoidTube is a premium, dark-themed YouTube client for Android phones and tablets — built by **VoidXDevs**. No ads, no interruptions: a polished glass interface, synced lyrics, background play and one-tap offline downloads, in a modern aesthetic with electric-violet and cyan accents.

> VoidTube is a reworked fork of [NewTube](https://github.com/aleixrodriala/newtube) (which is built on [SmartTube](https://github.com/yuliskov/SmartTube)). It is an independent project and is **not** affiliated with or endorsed by Google, YouTube, NewTube or SmartTube. Their original copyright notices are preserved — see [LICENSE](LICENSE) and [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

## Features

- 🚫 **Zero ads, zero sponsor segments.** Ads are gone, and SponsorBlock skips in-video sponsors, intros and "like and subscribe" reminders.
- ⬇️ **Offline downloads up to 1080p.** Queue videos, watch live progress, cancel anytime, and play them with no data.
- 💎 **Liquid glass UI.** Frosted, lens-bending glass on the navigation bar, player buttons and Shorts.
- 🎤 **Synced lyrics.** Line-by-line lyrics that follow the song, with a choice of providers.
- 🎧 **Background play & picture-in-picture.** Keep the audio going with the screen off, or float a video in its own window.
- 📱 **Shorts, done right.** A clean full-screen Shorts player with glass action buttons.
- 🧑‍🎤 **Channels at a glance.** Channel photos on every card; channel pages with banner, stats and sortable videos.
- 🔎 **YouTube's search filters.** Type, duration, upload date, popularity and every feature filter.
- 🎨 **Make it yours.** One Customization page for theme, app icon, glass, Home layout, tabs and the video menu.
- 👎 **Dislikes are back.** Return YouTube Dislike shows the real count; DeArrow replaces clickbait titles and thumbnails.
- 🔐 **Your account, your subscriptions.** Sign in with a code, or use it without an account. Cast to your TV.

## Install

Grab the APK for your device from [**Releases**](https://github.com/DevXShiva/VoidXTube/releases/latest):

| Your device | File |
|:--|:--|
| Most phones (64-bit) | `VoidTube_<version>_arm64-v8a.apk` |
| Older phones (32-bit) | `VoidTube_<version>_armeabi-v7a.apk` |
| Not sure | `VoidTube_<version>_universal.apk` |

Requires **Android 7.0 (API 24)** or later. VoidTube has its own app id (`io.github.devxshiva.voidxtube`), so it installs alongside other YouTube clients. Releases are built automatically by GitHub Actions (see below).

## Build from source

**Prerequisites:** JDK 17, the Android SDK (AGP 9.2.1 / API 37), and Git.

```bash
# Clone WITH the submodules (the build reads SharedModules + MediaServiceCore)
git clone --recurse-submodules https://github.com/DevXShiva/VoidXTube.git
cd VoidXTube

# Debug APK
./gradlew :smarttubetv:assembleStmobileDebug

# Signed release APK (needs keystore.properties - see docs/GITHUB-SETUP.md)
./gradlew :smarttubetv:assembleStmobileRelease
# Output: smarttubetv/build/outputs/renamed_apks/stmobileRelease/VoidTube_<version>_<abi>.apk
```

If you downloaded the source as a ZIP (no submodules), run `bash tools/add-submodules.sh` once first.

## Configuration

Runtime settings live in the app under **Settings**. Build-time branding is configurable without editing code — each value defaults to VoidTube and can be overridden with a Gradle property (`-P…`) or the matching environment variable (`ORG_GRADLE_PROJECT_…`):

| Value | Gradle property | Default |
|:--|:--|:--|
| Brand name | `voidxBrandName` | `VoidTube` |
| Developer credit | `voidxDeveloper` | `VoidXDevs` |
| Tagline | `voidxTagline` | `Your Media. Your Control.` |
| Telegram channel | `voidxTelegramChannel` | `VoidXDevs` |
| Telegram username | `voidxTelegramUsername` | `VoidXDevs` |
| Telegram URL | `voidxTelegramUrl` | `https://t.me/VoidXDevs` |

Example:

```bash
./gradlew :smarttubetv:assembleStmobileRelease -PvoidxTelegramUrl=https://t.me/your_handle
```

## Keeping up to date

VoidTube tracks its upstream, [**yashoncode/metube**](https://github.com/yashoncode/metube), through a scheduled GitHub Action (`.github/workflows/sync-upstream.yml`). It opens a **pull request** with upstream's changes so they can be reviewed and merged — it never pushes straight to `main`, which keeps VoidTube's branding and UI under our control. See [docs/GITHUB-SETUP.md](docs/GITHUB-SETUP.md) for the details.

Releases: pushing a `v*` tag (e.g. `v3.0.1`) builds the signed APKs and publishes a GitHub Release automatically.

## Contributing

Contributions are welcome. Please read [CONTRIBUTING.md](CONTRIBUTING.md) and the [Code of Conduct](CODE_OF_CONDUCT.md) first. Security issues: see [SECURITY.md](SECURITY.md).

## License & attribution

MIT — see [LICENSE](LICENSE). VoidTube is a fork of NewTube (by [@aleixrodriala](https://github.com/aleixrodriala)), itself built on SmartTube (by [@yuliskov](https://github.com/yuliskov)); both upstream copyright notices are preserved. Third-party software and community data (SponsorBlock, DeArrow, Return YouTube Dislike) are credited in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md). VoidTube is a non-commercial, free, open-source project.

## Community

- Telegram: **[t.me/VoidXDevs](https://t.me/VoidXDevs)** — releases, updates and support.
- Built by **VoidXDevs**.
- Issues and feature requests: [open an issue](https://github.com/DevXShiva/VoidXTube/issues/new/choose).
