# ARK-launcher Privacy Policy

*Last updated: 2026-10-03 · applies to ARK-launcher v0.8.3 and later
([v0.8.2 policy](https://github.com/jrs8205/ARK-launcher/blob/v0.8.2/PRIVACY.md))*

*Tämä seloste suomeksi: [PRIVACY.fi.md](PRIVACY.fi.md)*

ARK-launcher is a free, open-source Android home-screen launcher (Apache 2.0).
This policy describes exactly what data the app handles. The source code is
public, so every claim here can be verified:
<https://github.com/jrs8205/ARK-launcher>.

## The short version

- Your home-screen layout, settings, app list and notification data **stay on
  your device**. There is no telemetry, no analytics, no ads and no tracking of
  any kind.
- The network is used for exactly one purpose: showing the weather in the
  clock widget (including resolving the municipality name). Nothing else in
  the app talks to the internet.
- The app has no user accounts and never sends usage data, analytics or
  advertising identifiers to the developer or to any third party.
- Since v0.7.12 the app contains no self-updater and no Google Drive backup:
  updates come from the app store of your choice (or GitHub), and backups are
  local files you export yourself.

## Data stored on your device

- **Home-screen layout** — icon, folder and widget placements; stored in a
  local database.
- **Settings and preferences** — grid size, gestures, icon pack choice, hidden
  apps, custom app labels, most-used ordering and similar; stored locally.
- **Notification data** — if you grant notification access, notifications are
  read to show dots/badges, status-bar icons and the optional notification and
  people widgets, including their open, reply and dismiss actions. Message text
  is processed in memory and never stored persistently or transmitted by the
  launcher; replies are sent through the originating app. People you pin and
  identity links you create are saved in settings, including any matched contact
  name, number, photo reference and contact URI, and included in local backups.
  Optional batch delivery delays only launcher surfaces; Android notifications,
  sounds and pop-ups remain active. Notification keys and delivery times are
  saved locally to retain waiting state after a restart and excluded from backups.
- **Backups** — a backup you export is written to the file location you choose.
  It contains your layout and settings, nothing else. The app opts out of
  Android's system backup (`allowBackup="false"`), so its data is never copied
  into your Google account's device backup or carried over by a
  device-to-device transfer; the file you export is the only backup.

## Network use

1. **Weather** (only while the weather row is enabled and location permission
   is granted): the app requests the current temperature and conditions from
   the keyless [Open-Meteo](https://open-meteo.com) API. Your coordinates are
   rounded to about one-kilometre precision before the request; the exact
   position never leaves the device.
2. **Municipality name** shown next to the weather: the name is resolved with
   the device's own geocoder. Only if you turn on *Place name from the
   internet* (Settings ▸ Home, off by default) and the device has no working
   geocoder backend (some models don't), the app asks the keyless
   [BigDataCloud](https://www.bigdatacloud.com) reverse-geocoding API instead,
   sending the same ~1 km rounded coordinates plus the device's interface
   language — at most once per 30-minute weather refresh, and only when the
   local geocoder failed. With the setting off, no request is ever made to
   BigDataCloud; on such devices the weather then shows without a place name.

That is the complete list. The "Check for updates" row in Settings only opens
the GitHub releases page in your browser — the app itself performs no update
checks or downloads.

Neither request carries account information, cookies or advertising
identifiers. Like all internet traffic they necessarily reveal your IP address
to the server; the requests identify themselves with a generic
`ARK-launcher/<version>` User-Agent that contains no device details.

## Permissions

| Permission | Used for |
|---|---|
| `QUERY_ALL_PACKAGES` | Listing and launching your installed apps — the launcher's core purpose |
| Notification access (`BIND_NOTIFICATION_LISTENER_SERVICE`) | Notification dots/badges, status-bar icons, notification and people widgets, and local batch delivery |
| Accessibility service (`BIND_ACCESSIBILITY_SERVICE`, opt-in) | The double-tap-to-lock gesture only. The service receives no accessibility events and cannot read screen content; it exists solely to lock the screen, and only runs if you enable it yourself |
| `READ_CALENDAR` | Showing your next event in the clock widget |
| `READ_CONTACTS` | Contact results in app search and matching people-widget contacts (opt-in) |
| `READ_PHONE_STATE` | Signal-strength indicator in the status bar |
| `ACCESS_COARSE_LOCATION` | Weather in the clock widget |
| `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE`, `BLUETOOTH` (≤ Android 11) | Connectivity indicators in the status bar |
| `INTERNET` | The weather requests described above |
| `REQUEST_DELETE_PACKAGES` | The "uninstall" action in an icon's long-press menu |
| `EXPAND_STATUS_BAR` | The swipe-down-for-notifications gesture |

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
