# XShare Ultra

Lightweight Android file sharing for Android 8.0+ with LAN discovery, resumable transfers, SHA-256 verification, APK compatibility inspection, and offline adaptive transfer tuning.

## Build
Open this repository in Android Studio or run the GitHub Actions workflow **Build Android APK**. The debug APK is published as a workflow artifact.

## Current implementation scope
- Android native Kotlin app; no account or cloud upload required.
- Same-Wi-Fi local device discovery via Android NSD.
- TCP streaming with progress, SHA-256 verification, and safe filenames.
- APK metadata and compatibility checks using Android PackageManager.
- Lightweight deterministic adaptive transfer controller; offline history-backed tuning.
- Android 8.0 (API 26)+.

## Security and limitations
Transfers are local-network only in this first build. Pairing/transfer confirmation is required before sending. SHA-256 detects corruption; it is not a substitute for end-to-end encryption. The app never bypasses Android package-install security. Nearby Connections / Wi-Fi Direct fallback and encrypted pairing are planned follow-up milestones.

## Device notes
Designed to minimize memory use on 2 GB RAM devices. Transfer buffers are bounded and parallel transfer is disabled on low-memory devices.

## License
MIT
