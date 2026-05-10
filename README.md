# GPSLink Output

<div align="center">

![GPSLink Output Banner](app/src/main/res/mipmap-xxxhdpi/ic_launcher.png)

**Stream live GNSS data from your Android device over Bluetooth — no extra hardware required.**

[![Platform](https://img.shields.io/badge/Platform-Android%208.0%2B-22C55E?style=flat-square&logo=android&logoColor=white)](https://developer.android.com)
[![API](https://img.shields.io/badge/Min%20SDK-API%2026-3DDC84?style=flat-square)](https://developer.android.com/about/versions/oreo)
[![License](https://img.shields.io/badge/License-MIT-3B82F6?style=flat-square)](LICENSE)
[![Build](https://img.shields.io/badge/Build-Gradle-02303A?style=flat-square&logo=gradle)](https://gradle.org)
[![Profile](https://img.shields.io/badge/Bluetooth-SPP-0A1020?style=flat-square)](https://www.bluetooth.com)

</div>

---

## Overview

**GPSLink Output** is an Android application that turns your phone into a Bluetooth GNSS server. It reads raw GPS/GNSS data from the device's internal receiver, formats it as standard NMEA 0183 sentences, and streams it to any paired Bluetooth client over the Serial Port Profile (SPP).

Whether you're feeding position data to a laptop, tablet, navigation unit, or embedded system, GPSLink Output handles the GPS polling, NMEA formatting, and Bluetooth connection management transparently — running reliably in the background as a foreground service.

---

## Table of Contents

- [Features](#features)
- [Interface Overview](#interface-overview)
- [Requirements](#requirements)
- [Setup Guide](#setup-guide)
- [Project Architecture](#project-architecture)
- [Building](#building)
- [License](#license)

---

## Features

### Bluetooth NMEA Streaming

- **SPP server** — listens as a Bluetooth server socket using the standard Serial Port Profile UUID, accepting connections from any NMEA-compatible client.
- **Live NMEA output** — streams sentences derived from live GPS fixes including coordinates, altitude, speed, and satellite info.
- **Write queue** — decouples GPS polling from Bluetooth I/O with a 100-sentence buffer to handle momentary back-pressure without dropping data.

### Background Operation

- **Foreground service** — runs persistently with a status notification, keeping the GPS stream alive when the screen is off or the app is backgrounded.
- **Wake lock** — holds a `PARTIAL_WAKE_LOCK` to prevent the CPU from sleeping during active streaming.
- **Boot receiver** — automatically restarts the service after a device reboot.

### Real-Time Dashboard

- **Fix & coordinates panel** — live display of fix status, satellites used/in-view, latitude, longitude, altitude, and speed (km/h).
- **Satellite detail table** — per-satellite rows showing PRN, constellation (GPS/GLONASS/Galileo/BeiDou/QZSS), SNR, elevation, azimuth, and used-in-fix flag.
- **NMEA terminal** — scrollable live log of the last 20 raw NMEA sentences, appended in O(1) time via a retained `StringBuilder`.

---

## Interface Overview

| Tab | Description |
| :--- | :--- |
| 🏠 **Home** | Fix status indicator, Lat/Lon/Alt/Speed readouts, and Bluetooth connection card showing status and paired device name. |
| 🛰 **Satellite** | Summary counts (in-view / in-use / fix acquired) and a full per-satellite detail table updated in real time. |
| 💻 **Terminal** | Rolling log of the last 20 raw NMEA sentences for live debugging and throughput verification. |

| UI Element | Behaviour |
| :--- | :--- |
| **Fix indicator** | Green = fix acquired, Red = no fix. |
| **Bluetooth status** | Shows `CONNECTED` / `LISTENING` / `ERROR` with the connected device name. |
| **INITIALIZE / SHUTDOWN button** | Single toggle to start or stop the foreground service. Button color and label update to reflect live service state. |

---

## Requirements

| Requirement | Detail |
| :--- | :--- |
| Android version | 8.0 (API 26) or higher |
| Bluetooth | Classic Bluetooth required (SPP) |
| GPS hardware | Internal GNSS receiver required |
| Compile SDK | API 35 |
| JDK | 17 |

### Permissions

| Permission | Purpose |
| :--- | :--- |
| `ACCESS_FINE_LOCATION` | Read GPS coordinates from the internal receiver |
| `ACCESS_COARSE_LOCATION` | Fallback location access |
| `BLUETOOTH_CONNECT` / `BLUETOOTH_SCAN` | Manage Bluetooth connections (Android 12+) |
| `BLUETOOTH` / `BLUETOOTH_ADMIN` | Legacy Bluetooth support (Android ≤ 11) |
| `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_LOCATION` | Keep GPS polling active in the background |
| `RECEIVE_BOOT_COMPLETED` | Auto-start service after reboot |
| `WAKE_LOCK` | Prevent CPU sleep during active streaming |

---

## Setup Guide

**1. Install the app and grant permissions**

On first launch, accept all requested permissions — Location and Bluetooth access are both required for the service to function.

**2. Pair your client device**

Before connecting, pair your receiving device (laptop, tablet, navigation unit, etc.) with your Android phone via `Settings → Bluetooth`.

**3. Start the server**

Tap **INITIALIZE SERVER**. The app will start a foreground service, begin polling the internal GPS, and open a Bluetooth SPP server socket awaiting a client connection.

**4. Connect your client**

From your client device, connect to **GPSLink Output** using any serial/NMEA-compatible application. No baud rate configuration is needed — SPP is baud-rate agnostic.

**5. Verify the stream**

Once connected, the Home tab will show `CONNECTED` and the paired device name. Switch to the Terminal tab to confirm NMEA sentences are flowing.

> **Note**: The service survives backgrounding and screen-off. Tap **SHUTDOWN SERVER** from within the app to stop it, or dismiss via the persistent notification.

---

## Project Architecture

```
app/src/main/java/com/gpslink/output/
├── MainActivity.java           # UI controller, navigation, permission handling
├── GpsBluetoothService.java    # Core foreground service: GPS polling + BT streaming
├── BootReceiver.java           # Auto-start on device boot
└── Prefs.java                  # Shared preferences helper
```

The app follows a **Service → Callback → UI** data flow:

1. `GpsBluetoothService` runs as a foreground service, polling the internal `LocationManager` for GNSS fixes and managing the Bluetooth SPP server socket.
2. On each GPS event, the service formats NMEA sentences, enqueues them to the write thread, and fires a `UiCallback`.
3. `MainActivity` receives callbacks and updates the dashboard, satellite table, and terminal log in real time.

---

## Building

### Requirements

| Tool | Version |
| :--- | :--- |
| Android Studio | Ladybug or newer |
| JDK | 17 |
| Android SDK | API 35 |
| Gradle | Wrapper included |

### Build Instructions

```bash
# Clone the repository
git clone https://github.com/your-org/gpslink-output.git
cd gpslink-output

# Build a debug APK
./gradlew assembleDebug

# Output: app/build/outputs/apk/debug/app-debug.apk
```

For a release build, configure your signing keystore in `gradle.properties` before running:

```bash
./gradlew assembleRelease
```

Release builds have R8 shrinking and obfuscation enabled. Sign the APK with your keystore before distribution.

---

## License

Distributed under the **MIT License**. See [`LICENSE`](LICENSE) for full terms.

---

<div align="center">

Developed for the GNSS and open-source navigation community.

</div>
