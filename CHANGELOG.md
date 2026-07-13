# Changelog

All notable changes to Solar Dashboard for Android are documented here. Dates are
in YYYY-MM-DD.

## [Unreleased]

### Changed

- **"$ Saved" now shows an annual projection and today's total.** A savings
  figure that resets each day understated solar's value (a fresh morning showed
  cents). The Energy card's $ Saved section now leads with a "~$X/year at this
  rate" projection extrapolated from the recent daily average, with today's
  running total below it. Accounting is unchanged (all load energy, priced at the
  configured rate). The recent-day window is persisted and survives restarts and
  midnight rollovers (including days the app was closed across midnight), and is
  backfilled from stored history on the first launch after upgrading (bounded by
  the retention window) so the projection is populated immediately.

## [1.0.0] - 2026-07-12

First public release. Signed APK attached to the
[GitHub release](https://github.com/ChronicallyJD/android_solar_dashboard/releases/tag/v1.0.0).

### Added

- **Load-energy based "$ Saved" estimate.** The dashboard Energy card estimates
  the value of the energy delivered to loads so far today, priced at the national
  average residential electricity rate. Basing it on load energy (not solar
  harvested) means it accrues day and night and never double-counts solar that
  flows through the battery. Backed by a persisted daily accumulator that resets
  at local midnight.
- **Energy card** on the dashboard showing Harnessing (solar watts now),
  Expending (AC load watts now), and $ Saved (day total).
- **Low-battery alerts.** Notify by email (Gmail SMTP with an App Password), SMS
  (from the phone's own SIM), and/or a local notification when the average
  battery state of charge crosses below a configurable threshold. Fires once per
  dip and re-arms only after recovering above threshold plus a margin. Alert
  configuration, including the Gmail App Password, is stored encrypted using the
  Android Keystore. Includes a "Send test alert" action.
- **BLE device discovery** in the settings editor for both Victron and BMS
  devices: scan and pick a device by name and MAC to fill in the MAC
  automatically. Victron devices are matched by manufacturer data; BMS devices by
  advertised name and module OUI. Already-configured devices are excluded from the
  results.
- **Advertisement key sanitization.** A pasted Victron key that includes
  separators or a leading MAC prefix is accepted (the trailing 32 hex are used).
- **"Next update" time** shown next to the last-updated timestamp on the dashboard.
- **Collapsible sections** on both the dashboard (device groups) and the settings
  screen (Devices, Polling & History, Low-Battery Alerts, Database Maintenance).
- **In-app Help screen** documenting setup, the advertisement key, the Energy
  card, alerts, and troubleshooting.
- **First-run welcome screen.**
- **Database maintenance:** delete stored history by date range or in full, gated
  behind biometric or PIN authentication.
- **Restore on launch:** the dashboard shows the last stored readings and chart
  history immediately, before the first live scan.
- **User manual** with screenshots under `docs/MANUAL.md`.

### Fixed

- **Victron devices never decoded.** The advertisement parser read the record
  type from the wrong byte (the high nibble of byte 3 instead of byte 4).
  Verified against real SmartSolar MPPT and VE.Bus inverter hardware.
- **Victron devices appeared permanently offline.** Victron Instant Readout is
  broadcast via BLE extended advertising, which Android's default legacy-only
  scan drops. The scanner now reports extended advertisements
  (`setLegacy(false)` and all supported PHYs).
- **False decode from stray beacons.** Some Victron devices emit stray
  manufacturer-data beacons that could decode as garbage within a plausible
  range. The parser now verifies the key-check byte (the first byte of the
  advertisement key) before decoding, which also hardens wrong-key handling.
- **Dashboard stuck on "Waiting for first poll".** Readings are now published
  incrementally as each device is read, so a single slow or offline device no
  longer blocks the first render.
- **Discovery showed "(unnamed)" devices.** The advertised name is now captured
  whenever a non-null name arrives, not only on the first packet seen.

### Changed

- Codebase and user-facing text use plain punctuation (no em-dashes) and drop
  filler adjectives.

### Security

- Alert credentials (including the Gmail App Password) are stored with
  EncryptedSharedPreferences (Android Keystore), falling back to plain storage
  only if the Keystore is unavailable.
- Destructive database-maintenance actions require device re-authentication
  (biometric or PIN).

## [0.1.0] - Initial

- Native Android port of the Python `solar_dashboard` BLE monitor for JBD/Vatrer
  BMS batteries and Victron charging/inverting devices.
- Protocol parsers (JBD register 0x03, Victron Instant Readout AES-128-CTR) in
  pure Kotlin with a JUnit parity suite ported from the reference tests.
- Foreground service polling BMS over GATT and scanning Victron advertisements,
  persisting readings to a local SQLite history database.
- Jetpack Compose dashboard with per-device cards and history charts, and a
  settings screen for device and polling configuration.
