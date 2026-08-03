# ARK-launcher Privacy Policy

*Last updated: 2026-08-03 · applies to ARK-launcher v0.7.11 and later*

ARK-launcher is a free, open-source Android home-screen launcher (Apache 2.0).
This policy describes exactly what data the app handles. The source code is
public, so every claim here can be verified:
<https://github.com/jrs8205/ARK-launcher>.

## The short version

- Your home-screen layout, settings, app list and notification data **stay on
  your device**. There is no telemetry, no analytics, no ads and no tracking of
  any kind.
- The network is used only for three things: fetching the current weather for
  the clock widget, checking GitHub for app updates, and — only if you enable
  it — backing up your layout to *your own* Google Drive.
- The app never sends any identifier, account information or usage data to the
  developer or to any third party.

## Data stored on your device

- **Home-screen layout** — icon, folder and widget placements; stored in a
  local database.
- **Settings and preferences** — grid size, gestures, icon pack choice, hidden
  apps, custom app labels, most-used ordering and similar; stored locally.
- **Notification data** — if you grant notification access, notifications are
  read solely to show dots/badges, status-bar icons and the optional
  notifications widget. Notification content is processed in memory on the
  device and never stored persistently or transmitted anywhere.
- **Backups** — a backup you export is written to the file location you choose.
  It contains your layout and settings, nothing else.

## Network use

1. **Weather** (only while the weather row is enabled and location permission
   is granted): the app requests the current temperature and conditions from
   the keyless [Open-Meteo](https://open-meteo.com) API. Your coordinates are
   rounded to about one-kilometre precision before the request, and no
   identifiers or cookies are sent with it. The municipality name is resolved
   with the device's own geocoder.
2. **Update check**: the app queries the public GitHub Releases API of this
   repository to see whether a newer version exists, and downloads the new APK
   from GitHub when you ask it to. Plain GET requests, no identifiers.
3. **Google Drive backup** (opt-in, default **off**): if you enable Drive
   backup, the app uploads your backup file to your own Google Drive using the
   Google account you pick. The file goes only to your Drive; the developer has
   no access to it. Disabling the feature stops the uploads; files already in
   your Drive remain under your control.

## Permissions

| Permission | Used for |
|---|---|
| `QUERY_ALL_PACKAGES` | Listing and launching your installed apps — the launcher's core purpose |
| Notification access (`BIND_NOTIFICATION_LISTENER_SERVICE`) | Notification dots/badges, status-bar icons and the notifications widget |
| `READ_CALENDAR` | Showing your next event in the clock widget |
| `READ_CONTACTS` | Contact results in app search (opt-in) |
| `READ_PHONE_STATE` | Signal-strength indicator in the status bar |
| `ACCESS_COARSE_LOCATION` | Weather in the clock widget |
| `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE`, `BLUETOOTH` (≤ Android 11) | Connectivity indicators in the status bar |
| `INTERNET` | Weather, update checks, optional Drive backup |
| `POST_NOTIFICATIONS` | Update and backup notifications |
| `REQUEST_INSTALL_PACKAGES` | Installing updates you approve via the in-app updater |
| `REQUEST_DELETE_PACKAGES` | The "uninstall" action in an icon's long-press menu |
| `EXPAND_STATUS_BAR` | The swipe-down-for-notifications gesture |

Calendar, contacts, phone-state and location data are read on demand for the
features above, used on the device only, and never stored beyond in-memory
caches or transmitted anywhere.

## Children

ARK-launcher is a general-purpose utility with no content of its own, no ads
and no purchases.

## Changes and contact

Changes to this policy are made in the public repository, where the full
history is visible. Questions and reports:
<https://github.com/jrs8205/ARK-launcher/issues>.
