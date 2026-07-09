# ARK-launcher

[![Latest release](https://img.shields.io/github/v/release/jrs8205/ARK-launcher?sort=semver)](https://github.com/jrs8205/ARK-launcher/releases/latest)
[![License: Apache-2.0](https://img.shields.io/github/license/jrs8205/ARK-launcher)](LICENSE)
[![Downloads](https://img.shields.io/github/downloads/jrs8205/ARK-launcher/total)](https://github.com/jrs8205/ARK-launcher/releases)
[![Built with Jetpack Compose](https://img.shields.io/badge/Built%20with-Jetpack%20Compose-4285F4)](https://developer.android.com/jetpack/compose)

A modern, lightweight Android home-screen launcher built entirely with **Jetpack Compose**.
It is **bilingual** — Finnish (default) and English — and shares the visual identity of the
**Arkikeskus** app, but works as a standalone launcher for anyone.

> Status: early but daily-driven. Built from scratch on a current Kotlin/Compose stack.

📖 **New here?** The **[Wiki](https://github.com/jrs8205/ARK-launcher/wiki)** has the full user guide —
[setup](https://github.com/jrs8205/ARK-launcher/wiki/Getting-Started),
[features](https://github.com/jrs8205/ARK-launcher/wiki/Features),
[icon packs](https://github.com/jrs8205/ARK-launcher/wiki/Icon-Packs),
[backup](https://github.com/jrs8205/ARK-launcher/wiki/Backup-and-Restore) and a
[FAQ](https://github.com/jrs8205/ARK-launcher/wiki/FAQ).

## Screenshots

<p align="center">
  <img src="docs/screenshots/home.png" width="24%" alt="Home screen with a widget" />
  <img src="docs/screenshots/drawer.png" width="24%" alt="App drawer with search" />
  <img src="docs/screenshots/widgets.png" width="24%" alt="Widget picker with search" />
  <img src="docs/screenshots/settings.png" width="24%" alt="Settings" />
</p>

## Download

Download the latest signed APK from the
[**Releases**](https://github.com/jrs8205/ARK-launcher/releases/latest) page and open it on
your device to install. After the first install the app keeps itself up to date from new GitHub
releases (it notifies you and installs with one tap).

Requires **Android 11 (API 30)** or newer.

### Auto-updates with Obtainium

Prefer an app-store-style experience with automatic updates? Install
[**Obtainium**](https://github.com/ImranR98/Obtainium) and add this repository as an app source:

```
https://github.com/jrs8205/ARK-launcher
```

Obtainium watches the Releases page and updates the launcher for you.

## Features

- **Paged home screen** with free icon placement and smooth drag-and-drop — move icons within a
  page, across pages, and between the home grid and the dock. Drag an app from the drawer straight
  onto any page.
- **Dock** of favorite apps (reorder, drag in/out).
- **App drawer with universal search** — one search box finds:
  - installed **apps**,
  - common **system settings** pages (type "wifi", "battery", …),
  - **contacts** (opt-in; gated by a setting + the `READ_CONTACTS` permission),
  - a **calculator / unit converter** (type `12*7` or `100 cm to in`).
  - plus an optional **most-used apps** row.
- **Home-screen widgets** — add, resize, move, **reconfigure**, and stretch a widget to **full
  width** (edge to edge). Collection widgets (e.g. chat lists) scroll in place.
- **Folders** on the home screen and inside the drawer.
- **Notification dots / badges** (via a notification-listener service; dot or Nova-style count).
- **Material You themed icons** (monochrome, on supported Android versions).
- **Configurable gestures:** swipe up → app drawer, swipe down → notifications, and a configurable
  **left-edge swipe** that launches an app of your choice.
- **Customizable look** — adjustable app-label **text size** and **colour**, dock opacity, grid
  columns, page indicator, and more.
- **Backup & restore** — export/import your layout to a file, plus optional **Google Drive**
  auto-backup (Wi-Fi-only / charging-only schedules).
- **In-app updater** — checks GitHub releases in the background and installs updates with one tap.
- **Lock desktop** — a toggle that prevents accidental moving, removing, or adding of items.
- **Pixel-style long-press menus** with app shortcuts and actions, plus an **empty-area menu**
  (home settings / wallpaper), all in one consistent visual style.
- Hide apps from the drawer, rename apps with custom labels, and a self-contained settings screen.

## Tech stack

- **Kotlin** + **Jetpack Compose** (Material 3), single-Activity.
- **Hilt** (DI), **Room** (layout persistence), **DataStore** (preferences), **Coil** (icons),
  **WorkManager** (background tasks), **AppWidgetHost** (widgets), **OkHttp** (networking).
- Multi-module architecture with `build-logic` convention plugins.
- JDK 21, AGP 9, Gradle 9.

## Build

You need **JDK 21** (the Android Studio JBR works well) and the Android SDK.

```bash
# Point the build at your SDK (or set sdk.dir in local.properties)
./gradlew :app:assembleDebug
```

Install on a connected device:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Set it as the default launcher from Android's Settings ▸ Apps ▸ Default apps ▸ Home app, or accept
the prompt the app shows.

Requirements: `minSdk 30`.

## Project layout

- `app` — the single Activity, manifest HOME intent filter, DI entry point, the AppWidgetHost.
- `core/*` — `model`, `common`, `data` (Room/DataStore/repositories + search providers + backup),
  `ui` (shared Compose components, popups, the expressive theme), `designsystem`, `launcher`.
- `feature/*` — `home` (workspace + dock + widgets), `appdrawer`, `settings`, `backup`, `updater`.

## License

Licensed under the **Apache License, Version 2.0** — see [LICENSE](LICENSE).

This project adapts design/architecture patterns (re-implemented in Compose) from the
Android Open Source Project's **Launcher3** (also Apache-2.0); see [NOTICE](NOTICE) for attribution.

## Contributing

Issues and pull requests are welcome. By contributing you agree your contributions are licensed
under the project's Apache-2.0 license.
