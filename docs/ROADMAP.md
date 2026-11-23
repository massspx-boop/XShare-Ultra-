# XShare Ultra — Engineering Roadmap

## Implemented in current build
- [x] Android native Kotlin app shell (minSdk 26)
- [x] Locally bundled responsive dark/neon HTML + CSS interface
- [x] NSD/mDNS discovery on shared Wi-Fi
- [x] TCP file transfer with explicit accept/reject handshake
- [x] SHA-256 integrity verification and temporary-file commit
- [x] Basic collision-safe filenames and transfer size validation
- [x] Low-RAM-aware buffer sizing and local heuristic tuning
- [x] Basic APK metadata / min-SDK inspection
- [x] GitHub Actions debug APK build

## Next engineering milestones
- [ ] Add authenticated device pairing and encrypted transport (TLS or authenticated key exchange)
- [ ] Add resumable transfers with chunk hashes and persisted transfer state
- [ ] Add multi-file queue, cancellation, retry and detailed transfer history
- [ ] Add Wi-Fi Direct / local hotspot fallback for networks with client isolation
- [ ] Add foreground service and Android 13+ transfer notifications
- [ ] Add SAF export destination picker and media visibility in Downloads
- [ ] Add APK set inspection for split APK / XAPK / APKS without claiming one-file APK compatibility
- [ ] Add automated protocol tests and real-device tests on Android 8–15
- [ ] Produce a properly signed release APK after signing credentials are configured

## Constraints
The current app is LAN-only. It does not promise Zapya-level speed or universal device compatibility until tested under controlled, repeatable conditions. The adaptive controller is heuristic logic, not a machine-learning model.
