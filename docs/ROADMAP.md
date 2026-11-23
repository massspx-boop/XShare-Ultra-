# XShare Ultra roadmap

## Implemented in the initial source drop
- Native Android app shell and same-Wi-Fi DNS-SD discovery.
- TCP file streaming with incoming-transfer approval.
- SHA-256 verification and bounded memory buffers.
- Basic offline adaptive tuning profile (low-RAM aware).
- APK package/minimum Android version inspection.
- GitHub Actions debug APK build.

## Next engineering milestones
1. Add authenticated pairing and end-to-end encryption (TLS with per-session keys).
2. Add resumable chunk transfer with persisted partial-file metadata and re-hashing.
3. Add APK split-set (.apks/.xapk) detection, ABI and signature comparison, and install-result diagnostics.
4. Add Wi-Fi Direct / hotspot fallback for networks without a shared access point.
5. Train and benchmark a tiny offline LiteRT throughput model against the deterministic baseline; ship only if it wins on low-RAM devices.
6. Benchmark against other transfer apps on identical phones and network conditions before making speed claims.
