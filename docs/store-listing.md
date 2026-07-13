# Google Play store listing

Source text for the Play Console listing, kept here so it stays in step with the
app. Character limits: short description 80, full description 4000.

## App details

- **Package name:** `com.offgrid.solardashboard`
- **Privacy policy:** https://github.com/ChronicallyJD/android_solar_dashboard/blob/main/PRIVACY.md
- **Advertising ID:** not used (answer "No" on the Data safety form)

## Short description (61 characters)

```
Monitor Victron solar and JBD battery devices over Bluetooth.
```

## Full description (2904 characters)

```
Solar Dashboard monitors your Bluetooth batteries, solar chargers, and inverters. It runs entirely on your phone. There is no account, no cloud service, and no tracking.

The app reads two kinds of devices:

- JBD (Jiabaida) BMS battery packs, the same protocol used by the Xiaoxiang / JBD app. Many rebranded LiFePO4 batteries ship a JBD BMS and should work.
- Victron devices over Instant Readout: SmartSolar and BlueSolar MPPT chargers, Phoenix, MultiPlus and Quattro inverters, SmartShunt and BMV monitors, Orion DC-DC converters, and more.

What you can see:

- Battery packs: voltage, current, state of charge, per-cell voltages and balance, temperatures, cycle count, MOSFET status, and faults.
- Solar chargers: PV watts now and yield for the day, plus battery voltage and current.
- Inverters: AC output.

Dashboard:

- Energy card with solar power now (Harnessing), load power now (Expending), and a $ Saved estimate. $ Saved shows a per-year projection from your recent daily average and today's total, priced at an electricity rate you can set.
- Summary tiles for total solar watts, total inverter output, and average battery state of charge.
- Per-device cards grouped by type, each showing online status and the latest reading.
- History charts with selectable time ranges (1h, 6h, 24h, all) and CSV export.

Setup:

- A built-in scanner lists nearby devices and fills in the Bluetooth address for you.
- For Victron devices, paste the advertisement key from VictronConnect once.
- For a battery, set a password only if your pack needs one.

Alerts (optional):

- Get notified when the average battery state of charge drops below a threshold you set.
- Choose any combination of email (through your Gmail account), SMS (from your phone's SIM), and a local notification.
- Alert credentials are stored encrypted using the Android Keystore.

Background monitoring:

- A foreground service polls your devices on an interval you choose.
- The dashboard shows the last stored readings and charts on launch, before the first scan.
- Readings are saved to a local database with a retention period you set. You can delete stored history by date range or in full, gated behind your device's biometric or PIN.

Privacy:

- All readings and settings stay on your phone.
- The app does not collect or sell your data and contains no analytics, advertising, or tracking libraries.
- The only outbound traffic is the email and SMS alerts you turn on, sent to the destinations you configure.

Requirements:

- Android 8.0 or newer.
- Bluetooth. The phone does the scanning and connecting.
- For Victron devices, the advertisement key from VictronConnect.
- For SMS alerts, a SIM with cell service. For email alerts, a Gmail account with an app password.

This app is not affiliated with Victron Energy, Jiabaida (JBD), or any battery brand. Device and brand names are used only to describe compatibility.
```
