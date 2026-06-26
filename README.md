# Arkikeskus Launcher

A modern, lightweight Android home-screen launcher built entirely with **Jetpack Compose**.
It is **bilingual** — Finnish (default) and English — and shares the visual identity of the
**Arkikeskus** app, but works as a standalone launcher for anyone.

> Status: early but daily-driven. Built from scratch on a current Kotlin/Compose stack.

## Features

- **Paged home screen** with free icon placement and smooth drag-and-drop — move icons within a
  page, across pages, and between the home grid and the dock.
- **Dock** of favorite apps (reorder, drag in/out).
- **App drawer with universal search** — one search box finds:
  - installed **apps**,
  - common **system settings** pages (type "wifi", "battery", …),
  - **contacts** (opt-in; gated by a setting + the `READ_CONTACTS` permission),
  - a **calculator / unit converter** (type `12*7` or `100 cm to in`).
- **Folders** on the home screen and inside the drawer.
- **Notification dots / badges** (via a notification-listener service; dot or Nova-style count).
- **Material You themed icons** (monochrome, on supported Android versions).
- **Configurable gestures:** swipe up → app drawer, swipe down → notifications, and a configurable
  **left-edge swipe** that launches an app of your choice.
- **Lock desktop** — a toggle that prevents accidental moving, removing, or adding of items.
- **Pixel-style long-press menus** with app shortcuts and actions, plus an **empty-area menu**
  (home settings / wallpaper), all in one consistent visual style.
- Hide apps from the drawer, rename apps with custom labels, and a self-contained settings screen.

## Tech stack

- **Kotlin** + **Jetpack Compose** (Material 3), single-Activity.
- **Hilt** (DI), **Room** (layout persistence), **DataStore** (preferences), **Coil** (icons).
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

- `app` — the single Activity, manifest HOME intent filter, DI entry point.
- `core/*` — `model`, `common`, `data` (Room/DataStore/repositories + search providers),
  `ui` (shared Compose components, popups, the expressive theme), `designsystem`, `launcher`.
- `feature/*` — `home` (workspace + dock), `appdrawer`, `settings`, `widgets`.

## License

Licensed under the **Apache License, Version 2.0** — see [LICENSE](LICENSE).

This project adapts design/architecture patterns (re-implemented in Compose) from the
Android Open Source Project's **Launcher3** (also Apache-2.0); see [NOTICE](NOTICE) for attribution.

## Contributing

Issues and pull requests are welcome. By contributing you agree your contributions are licensed
under the project's Apache-2.0 license.
