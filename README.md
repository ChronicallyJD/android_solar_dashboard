# Solar Dashboard for Android

A native Android port of [`solar_dashboard`](../solar_dashboard), the Python
BLE monitor for **JBD/Vatrer BMS** batteries and **Victron** charging/inverting
devices. Same protocols, same parsing, same dashboard, as a sideloadable APK
that talks to your devices directly over the phone's Bluetooth.

## What it does

- **JBD / Vatrer / Overkill / Daly BMS**: connects over GATT (service `ff00`
  / `ffe0` / Nordic UART), reads register `0x03` basic info: pack voltage,
  current, SoC, per-cell balance, temperatures, cycles, FET status, faults,
  runtime estimates. Optional password auth (`0x06`).
- **Victron Instant Readout**: passively scans BLE advertisements
  (manufacturer ID `0x02E1`), AES-128-CTR decrypts them with your advertisement
  key, and parses every record type: Solar Charger (`0x01`), Battery Monitor
  (`0x02`/`0x08`), Inverter (`0x03`), Inverter RS / Multi RS (`0x06`/`0x0B`),
  DC/DC (`0x04`/`0x09`/`0x0D`), SmartLithium (`0x05`), VE.Bus (`0x07`/`0x0C`).
- **Device discovery**: a scan in the settings editor lists nearby devices by
  name and MAC and fills in the MAC for you. Victron devices are matched by
  manufacturer data; BMS devices by advertised name and module OUI.
- **Dashboard**: aggregate overview (total PV, AC out, average SoC), per-device
  cards mirroring the web UI, faults/alarms highlighted, and rolling history
  line charts (voltage, current, PV power, SoC).
- **Foreground service**: polls on the configured cadence (Victron scan every
  ~30 s, BMS GATT every ~120 s, BMS read one-at-a-time) and persists successful
  readings to a local SQLite history database with retention.
- **Low-battery alerts**: when the average battery state of charge drops below a
  configurable threshold, notify by email (Gmail SMTP with an App Password), SMS
  (from the phone's own SIM), and/or a local notification. Fires once per dip
  with re-arm hysteresis. Credentials are stored encrypted (Android Keystore).
- **Help and maintenance**: an in-app help screen documents setup; history can
  be pruned by date range or in full, gated behind biometric or PIN unlock.

## Protocol parity

The protocol logic is a direct port of the Python modules, kept in pure,
unit-testable Kotlin:

| Python | Kotlin |
| --- | --- |
| `solar_monitor/jbd.py` | `protocol/JbdProtocol.kt` |
| `solar_monitor/victron.py` | `protocol/VictronParser.kt` |
| `solar_monitor/models.py` | `protocol/DeviceReading.kt` |
| `tests/test_jbd_protocol.py` | `test/.../JbdProtocolTest.kt` |
| `tests/test_victron_parsers.py` | `test/.../VictronParserTest.kt` |

The JUnit suites port the Python test vectors verbatim (checksums, framing,
production-date bitfields, balance-cell spanning, VE.Bus bit-packing, AES-CTR
round-trips, the full record-selection/plausibility pipeline) so the Kotlin
parsers are held to the same expected values as the reference implementation.

Run them with:

```
./gradlew :app:testDebugUnitTest
```

## Building

Requires JDK 17 + the Android SDK (platform 35, build-tools 35). From the repo
root:

```
./gradlew :app:assembleDebug
```

The APK lands at `app/build/outputs/apk/debug/app-debug.apk`. It is signed with
the Android debug key, which is all that's needed for sideloading.

## Installing (sideload)

1. Copy `app-debug.apk` to your phone (USB, cloud, etc.).
2. On the phone, allow "install unknown apps" for your file manager/browser.
3. Tap the APK to install.

Or over ADB:

```
adb install app-debug.apk
```

Minimum Android 8.0 (API 26); targets Android 15 (API 35).

## First run

1. Grant the Bluetooth (and, on Android 11 and below, Location) and notification
   permissions when prompted. Android requires them for BLE scanning.
2. Open **Settings** (gear icon) and add your devices. Use "Scan for nearby
   devices" to fill in the MAC:
   - **BMS**: name, MAC address, optional BLE name, password (default `0000`).
   - **Victron**: name, MAC address, 32-hex **advertisement key** (VictronConnect
     gear icon, Product info, *Instant Readout via Bluetooth*, Show,
     advertisement key, not the Encryption key), and device type.
3. Return to the dashboard; the foreground service begins polling.

## Notes on scope

This port focuses on the on-device monitoring experience. The Python project's
optional HTTPS server and MCP server are host-oriented features and are not part
of the phone app; the dashboard, history, alerts, and both device protocols are
fully reproduced.
