# Privacy Policy

**App:** Solar Dashboard (`com.offgrid.solardashboard`)
**Last updated:** 2026-07-13

Solar Dashboard monitors your Bluetooth batteries, solar chargers, and inverters.
It runs entirely on your phone. This policy explains what data the app handles and
what leaves your device.

## Summary

- The app does not collect, transmit, or sell your personal data.
- There are no analytics, advertising, or third-party tracking libraries.
- All readings and settings are stored locally on your phone.
- Data leaves your phone only when you turn on an optional alert (email or SMS),
  and then only to the recipients and services you configure.

## Data the app stores on your device

- **Device readings and history:** voltage, current, state of charge, power,
  temperature, and similar values read from your devices, kept in a local
  database on the phone so the app can draw charts and totals.
- **Device configuration:** the Bluetooth MAC addresses you add, Victron
  advertisement keys, and any BMS password you set.
- **Alert settings:** the thresholds you choose and, if you enable email alerts,
  your Gmail address, an app password, and the recipient address. Credentials are
  stored encrypted using the Android Keystore.

None of this is sent to the developer or to any server operated by the developer.
The app has no account system and no backend.

## Permissions and why they are used

- **Bluetooth (scan, connect):** to find and read your solar and battery devices.
  On Android 12 and newer the scan permission is declared "never for location",
  so the app does not derive your location from Bluetooth.
- **Location (Android 11 and older only):** older Android versions require the
  location permission before an app may scan for Bluetooth Low Energy devices.
  The app requests it only on those versions and only for scanning. It does not
  read, store, or share your location. The permission is not requested on Android
  12 or newer.
- **Internet:** used only to send optional email alerts through Gmail. The app
  makes no other network connections.
- **Send SMS:** used only to send optional text-message alerts from your phone to
  the number you configure.
- **Notifications:** to show status and alert notifications on your phone.
- **Foreground service, run at boot, ignore battery optimizations:** to keep
  polling your devices in the background so monitoring and alerts keep working.

## Data that can leave your phone

Nothing leaves your phone unless you turn on an alert:

- **Email alerts (optional):** when enabled, alert messages are sent through
  Google's Gmail SMTP servers using the Gmail account and app password you
  provide. That traffic is handled by Google under Google's own privacy policy.
- **SMS alerts (optional):** when enabled, text messages are sent from your phone
  through your mobile carrier to the number you set. Standard carrier rates and
  policies apply.

The app sends these only to the destinations you configure. It does not send your
data anywhere else.

## Retention and deletion

All data stays on your device. You control it:

- Delete stored history by date range or in full from **Settings > Database
  Maintenance**.
- Uninstalling the app removes all of its data from your phone.

## Children

The app is a utility for monitoring power equipment and is not directed at
children. It does not knowingly collect data from anyone.

## Changes to this policy

If this policy changes, the updated version will be posted at the same location
with a new "Last updated" date.

## Contact

Questions about this policy: offgridwithjd@gmail.com
