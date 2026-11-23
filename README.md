# XShare Ultra

Android file sharing over a shared local Wi-Fi network. Built with Kotlin and a lightweight local WebView interface.

## Current implementation

- Dark neon responsive Burmese UI bundled locally; no remote website or CDN required.
- Android native Kotlin networking using NSD/mDNS discovery and TCP file transfer.
- Receiver approval before transfer and SHA-256 integrity verification.
- Handles document providers that report unknown file sizes by counting and hashing a bounded-memory stream.
- Unicode-safe received filenames and temporary files committed only after checksum verification.
- Low-RAM adaptive transfer buffers.
- APK metadata inspection.
- **Installed app backup:** lists user-installed apps and packages the base APK plus every installed split APK into a single `.xshare` archive.
- **Split APK install:** receiver extracts the bundle and submits all APK splits in one Android PackageInstaller session. Android displays its normal install confirmation; the user must approve it.
- GitHub Actions builds a debug APK.

## Build

Requires Gradle 8.9 and JDK 17.

    gradle --no-daemon :app:assembleDebug

APK output: `app/build/outputs/apk/debug/app-debug.apk`.

## Transfer files

1. Install XShare Ultra on both Android devices.
2. Connect both devices to the same Wi-Fi network.
3. Open XShare Ultra on both devices and allow Nearby Wi-Fi permission.
4. Select a file, choose the receiving device, and approve the incoming transfer.

## Transfer an installed game/app

1. On the sending phone, open **ဂိမ်း / App ကူးပြောင်းခြင်း** and tap **App စာရင်းဖွင့်မယ်**.
2. Choose the game/app and tap **Bundle ထုတ်**. XShare packages its base APK and installed split APK files into one `.xshare` file.
3. Select the receiving phone from the device list and send the generated bundle.
4. On the receiving phone, find the file under **လက်ခံထားသော Game Bundle** and tap **Install**.
5. If Android asks, allow XShare Ultra to install unknown apps in Settings, return to XShare, tap Install again, and approve Android's package installer confirmation.

## Important limitations

- This transfers the APK package parts that are installed on the source phone. It does not copy Play Store account/license entitlements.
- The target phone must be compatible with the APK's Android version, CPU ABI, features, and signing certificate. Android will reject incompatible or differently signed packages.
- Some games download large OBB/asset/data files separately or store user data in protected locations. Those files are **not included** in this version; the game may download its data again after installation.
- Dynamic features or Play Asset Delivery content may need to be downloaded by the game after installation.
- The receiver must explicitly approve installation. XShare cannot silently install apps or bypass Android security.
- App visibility uses QUERY_ALL_PACKAGES to list installed apps. This is suitable for this sideloaded utility build but is restricted for Google Play distribution.
- The transfer protocol is local TCP with SHA-256 integrity checking, not encryption or device authentication. Use a trusted Wi-Fi network.
- Both devices must run a compatible XShare Ultra build. Guest Wi-Fi/client isolation may block discovery.
- This is a debug APK, not a signed Play Store release.
- Wi-Fi Direct/hotspot fallback, resumable transfers, encrypted pairing, and OBB/data-folder migration are not implemented yet.
