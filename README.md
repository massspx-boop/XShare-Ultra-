# XShare Ultra

Android file sharing over a shared local Wi-Fi network. Built with Kotlin and a lightweight local WebView interface.

## Current implementation

- Dark neon responsive UI (HTML/CSS) bundled locally; no remote website or CDN required.
- Android native Kotlin networking using NSD/mDNS device discovery and TCP transfer.
- Receiver approval/rejection handshake before file bytes are sent.
- SHA-256 integrity verification at the receiving device.
- Temporary .part files are renamed only after a successful integrity check.
- Collision-safe received filenames and basic filename sanitization.
- Adaptive single-stream buffer sizing using device low-RAM status and local transfer history.
- APK metadata inspection for package, version and minimum Android SDK.
- GitHub Actions builds a debug APK on each push.

## Build

The repository uses Gradle 8.9 and JDK 17.

    gradle --no-daemon :app:assembleDebug

APK output: app/build/outputs/apk/debug/app-debug.apk.

## How to use

1. Install XShare Ultra on both Android devices.
2. Connect both devices to the same Wi-Fi network (internet access is not required).
3. Open XShare Ultra on both devices and allow Nearby Wi-Fi permission when Android requests it.
4. On the sending phone, tap "ဖိုင်ရွေးမယ်" and choose a file.
5. Tap the receiving phone in the discovered devices list.
6. The receiving phone must approve the transfer.

Received files are stored in the app-specific Downloads folder. Android may remove these files if the app is uninstalled.

## Important security and compatibility notes

- Current transport is TCP on the local network. SHA-256 detects in-transit modification, but it is not encryption or device authentication. Only transfer files on a trusted Wi-Fi network.
- Both phones must run a compatible XShare Ultra build and be on the same local network. Guest Wi-Fi or router client isolation may block discovery/transfer.
- Current transfer limit is 20 GB; the source provider must expose a readable file size.
- This is a debug build, not a signed Play Store release.
- Wi-Fi Direct / hotspot fallback, encrypted pairing, resumable transfers, multi-file queues, split APK/XAPK/APKS installation support, and background transfer notifications are not implemented yet.
- Adaptive tuning is a small offline heuristic, not a trained AI model.
