# ARK-launcher Privacy Policy

*Last updated: 2026-08-12 · applies to ARK-launcher v0.7.11 and later*

*Tämä seloste suomeksi: [PRIVACY.fi.md](PRIVACY.fi.md)*

ARK-launcher is a free, open-source Android home-screen launcher (Apache 2.0).
This policy describes exactly what data the app handles. The source code is
public, so every claim here can be verified:
<https://github.com/jrs8205/ARK-launcher>.

## The short version

- Your home-screen layout, settings, app list and notification data **stay on
  your device**. There is no telemetry, no analytics, no ads and no tracking of
  any kind.
- The network is used only for the purposes listed under *Network use* below:
  showing the weather in the clock widget (including resolving the municipality
  name), checking GitHub for app updates, and — only if you enable it —
  backing up your layout to *your own* Google Drive.
- The app has no user accounts and never sends usage data, analytics or
  advertising identifiers to the developer or to any third party.

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
   rounded to about one-kilometre precision before the request; the exact
   position never leaves the device.
2. **Municipality name** shown next to the weather: the name is resolved with
   the device's own geocoder first. If the device has no working geocoder
   backend (some models don't), the app falls back to the keyless
   [BigDataCloud](https://www.bigdatacloud.com) reverse-geocoding API, sending
   the same ~1 km rounded coordinates plus the device's interface language —
   at most once per 30-minute weather refresh, and only when the local
   geocoder failed.
3. **Update check**: the app queries the public GitHub Releases API of this
   repository to see whether a newer version exists, and downloads the new APK
   from GitHub when you ask it to. Plain GET requests.
4. **Google Drive backup** (opt-in, default **off**): if you enable Drive
   backup, the app uploads your backup file into the hidden app-specific data
   area of *your own* Google Drive (the `appDataFolder`, OAuth scope
   `drive.appdata`). That scope lets the app see **only its own backup files**
   and nothing else in your Drive; the developer has no access to any of it.
   Three practical consequences:
   - The backups do **not** appear in the normal Drive file list. You can see
     their storage use and delete them all under Drive **Settings → Manage
     apps** (drive.google.com).
   - The app keeps only the **3 newest** backup files: after each successful
     upload it automatically deletes the older backup files it created, so
     old restore points do not accumulate — the oldest ones are removed.
   - Disabling the feature stops both the uploads and the automatic deletion.

None of these requests carries account information, cookies or advertising
identifiers. Like all internet traffic, they necessarily reveal your IP
address to the server, and requests made through Android's built-in HTTP
client include Android's default `User-Agent` header, which names the Android
version and device model.

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
| `WAKE_LOCK`, `RECEIVE_BOOT_COMPLETED`, `FOREGROUND_SERVICE` | Added automatically by Android's WorkManager library, which runs the scheduled Drive-backup job: keeping the device awake while a backup uploads and re-scheduling the job after a reboot. Not used for anything else |

Calendar, contacts and phone-state data are read on demand for the features
above, used on the device only, and never stored beyond in-memory caches or
transmitted anywhere. Location is handled the same way on the device; the only
location data that ever leaves it is the ~1 km rounded coordinates sent to the
weather and reverse-geocoding services described under *Network use*.

## Children

ARK-launcher is a general-purpose utility with no content of its own, no ads
and no purchases. It is not directed at children, and it does not knowingly
collect personal data from children — or from anyone else: the app has no
accounts and collects no personal data at all.

## Changes and contact

Changes to this policy are made in the public repository, where the full
history is visible. Questions and reports:
<https://github.com/jrs8205/ARK-launcher/issues>.
