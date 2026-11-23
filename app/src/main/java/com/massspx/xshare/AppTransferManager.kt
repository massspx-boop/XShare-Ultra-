package com.massspx.xshare

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class ShareableApp(
    val packageName: String,
    val label: String,
    val version: String,
    val apkCount: Int
)

class AppTransferManager(private val context: Context) {
    private fun packages(): List<PackageInfo> {
        val pm = context.packageManager
        @Suppress("DEPRECATION")
        return if (Build.VERSION.SDK_INT >= 33) {
            pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(PackageManager.GET_META_DATA.toLong()))
        } else {
            pm.getInstalledPackages(PackageManager.GET_META_DATA)
        }
    }

    fun listUserApps(): List<ShareableApp> = packages().mapNotNull { info ->
        val app = info.applicationInfo ?: return@mapNotNull null
        if ((app.flags and ApplicationInfo.FLAG_SYSTEM) != 0 ||
            (app.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0) return@mapNotNull null
        val paths = listOfNotNull(app.sourceDir) + (app.splitSourceDirs?.toList() ?: emptyList())
        if (paths.isEmpty() || paths.any { !File(it).isFile }) return@mapNotNull null
        ShareableApp(
            info.packageName,
            runCatching { app.loadLabel(context.packageManager).toString() }.getOrDefault(info.packageName),
            info.versionName ?: "unknown",
            paths.size
        )
    }.sortedBy { it.label.lowercase() }

    fun createBundle(packageName: String): File {
        val pm = context.packageManager
        @Suppress("DEPRECATION")
        val info = if (Build.VERSION.SDK_INT >= 33) {
            pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(PackageManager.GET_META_DATA.toLong()))
        } else {
            pm.getPackageInfo(packageName, PackageManager.GET_META_DATA)
        }
        val app = info.applicationInfo ?: throw IllegalStateException("App metadata မတွေ့ပါ")
        val sourceFiles = listOfNotNull(app.sourceDir) + (app.splitSourceDirs?.toList() ?: emptyList())
        require(sourceFiles.isNotEmpty() && sourceFiles.all { File(it).isFile }) {
            "APK အစိတ်အပိုင်းအားလုံးကို ဖတ်မရပါ"
        }
        val label = runCatching { app.loadLabel(pm).toString() }.getOrDefault(packageName)
            .replace(Regex("[\\/:*?\"<>|\\u0000-\\u001F]"), "_").take(70)
        val dir = context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
        if (!dir.exists() && !dir.mkdirs()) throw IllegalStateException("သိမ်းဆည်းမည့်နေရာ မဖန်တီးနိုင်ပါ")
        val output = File(dir, "${label}_${System.currentTimeMillis()}.xshare")
        val entries = JSONArray()
        sourceFiles.forEachIndexed { index, path ->
            val file = File(path)
            entries.put(JSONObject().put("entry", if (index == 0) "base.apk" else "split_${index}.apk")
                .put("size", file.length()))
        }
        val manifest = JSONObject()
            .put("format", "xshare-app-bundle")
            .put("formatVersion", 1)
            .put("packageName", packageName)
            .put("versionName", info.versionName ?: "unknown")
            .put("versionCode", if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong())
            .put("apkFiles", entries)
        ZipOutputStream(FileOutputStream(output).buffered()).use { zip ->
            zip.setLevel(0)
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(manifest.toString().toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            sourceFiles.forEachIndexed { index, path ->
                val entryName = if (index == 0) "base.apk" else "split_${index}.apk"
                zip.putNextEntry(ZipEntry(entryName))
                FileInputStream(path).buffered().use { it.copyTo(zip, 128 * 1024) }
                zip.closeEntry()
            }
        }
        return output
    }
}
