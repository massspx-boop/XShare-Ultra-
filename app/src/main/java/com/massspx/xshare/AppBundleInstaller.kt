package com.massspx.xshare

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import java.io.File
import java.util.zip.ZipFile
import org.json.JSONObject

object AppBundleInstaller {
    private const val MAX_BUNDLE_BYTES = 20L * 1024 * 1024 * 1024

    fun install(context: Context, bundle: File) {
        require(bundle.isFile && bundle.length() in 1..MAX_BUNDLE_BYTES) { "App bundle ဖိုင် မမှန်ပါ" }
        val tempDir = File(context.cacheDir, "xshare_install_${System.currentTimeMillis()}")
        if (!tempDir.mkdirs()) throw IllegalStateException("ယာယီဖိုင်နေရာ ဖန်တီးမရပါ")
        try {
            ZipFile(bundle).use { zip ->
                val manifestEntry = zip.getEntry("manifest.json")
                    ?: throw IllegalArgumentException("XShare manifest မတွေ့ပါ")
                val manifest = zip.getInputStream(manifestEntry).bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }
                require(manifest.optString("format") == "xshare-app-bundle" && manifest.optInt("formatVersion") == 1) {
                    "မသိသော App bundle အမျိုးအစားဖြစ်သည်"
                }
                val packageName = manifest.optString("packageName")
                require(packageName.matches(Regex("[A-Za-z0-9_.]+"))) { "Package name မမှန်ပါ" }
                val apkList = manifest.optJSONArray("apkFiles") ?: throw IllegalArgumentException("APK စာရင်းမတွေ့ပါ")
                require(apkList.length() in 1..30) { "APK အစိတ်အပိုင်းအရေအတွက် မမှန်ပါ" }
                val extracted = mutableListOf<Pair<String, File>>()
                var total = 0L
                for (i in 0 until apkList.length()) {
                    val item = apkList.getJSONObject(i)
                    val entryName = item.optString("entry")
                    require(entryName == "base.apk" || entryName.matches(Regex("split_[0-9]+\\.apk"))) {
                        "APK ဖိုင်အမည် မမှန်ပါ"
                    }
                    val entry = zip.getEntry(entryName) ?: throw IllegalArgumentException("$entryName မပါဝင်ပါ")
                    require(!entry.isDirectory)
                    val target = File(tempDir, entryName)
                    var copied = 0L
                    zip.getInputStream(entry).use { input ->
                        target.outputStream().buffered().use { output ->
                            val buffer = ByteArray(128 * 1024)
                            while (true) {
                                val n = input.read(buffer)
                                if (n < 0) break
                                copied += n
                                total += n
                                require(total <= MAX_BUNDLE_BYTES) { "APK စုစုပေါင်းအရွယ်အစား 20 GB ကျော်နေသည်" }
                                output.write(buffer, 0, n)
                            }
                        }
                    }
                    require(copied > 0 && (item.optLong("size", copied) == copied)) { "APK အရွယ်အစား မကိုက်ညီပါ" }
                    extracted.add(entryName to target)
                }
                require(extracted.any { it.first == "base.apk" }) { "Base APK မပါဝင်ပါ" }
                val installer = context.packageManager.packageInstaller
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                params.setAppPackageName(packageName)
                val sessionId = installer.createSession(params)
                try {
                    installer.openSession(sessionId).use { session ->
                        extracted.forEach { (name, file) ->
                            session.openWrite(name, 0, file.length()).use { output ->
                                file.inputStream().buffered().use { it.copyTo(output, 128 * 1024) }
                                session.fsync(output)
                            }
                        }
                        val callback = Intent(context, InstallStatusReceiver::class.java)
                            .setAction("com.massspx.xshare.INSTALL_STATUS")
                            .putExtra(PackageInstaller.EXTRA_SESSION_ID, sessionId)
                        val pendingFlags = android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                            if (Build.VERSION.SDK_INT >= 31) android.app.PendingIntent.FLAG_MUTABLE else 0
                        val pending = android.app.PendingIntent.getBroadcast(context, sessionId, callback, pendingFlags)
                        session.commit(pending.intentSender)
                    }
                } catch (e: Exception) {
                    runCatching { installer.abandonSession(sessionId) }
                    throw e
                }
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
