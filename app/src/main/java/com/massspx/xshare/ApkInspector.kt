package com.massspx.xshare

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build

data class ApkReport(
    val packageName: String,
    val versionName: String,
    val minSdk: Int,
    val targetSdk: Int,
    val compatible: Boolean,
    val message: String
)

object ApkInspector {
    fun inspect(context: Context, uri: Uri): ApkReport {
        val temp = kotlin.runCatching {
            val file = java.io.File(context.cacheDir, "inspect_${System.currentTimeMillis()}.apk")
            context.contentResolver.openInputStream(uri)?.use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            } ?: throw IllegalArgumentException("Cannot read APK")
            file
        }.getOrNull() ?: return ApkReport("", "", 0, 0, false, "APK ဖိုင်ကို ဖတ်မရပါ")

        return try {
            @Suppress("DEPRECATION")
            val info = if (Build.VERSION.SDK_INT >= 33) {
                context.packageManager.getPackageArchiveInfo(
                    temp.absolutePath,
                    PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong())
                )
            } else {
                context.packageManager.getPackageArchiveInfo(
                    temp.absolutePath,
                    PackageManager.GET_SIGNING_CERTIFICATES
                )
            } ?: return ApkReport("", "", 0, 0, false, "APK ပျက်နေခြင်း သို့မဟုတ် မပြည့်စုံခြင်း ဖြစ်နိုင်သည်")

            val appInfo = info.applicationInfo
                ?: return ApkReport("", "", 0, 0, false, "APK metadata မပြည့်စုံပါ")
            val minSdk = if (Build.VERSION.SDK_INT >= 24) appInfo.minSdkVersion else 1
            val target = appInfo.targetSdkVersion
            val compatible = minSdk <= Build.VERSION.SDK_INT
            ApkReport(
                info.packageName,
                info.versionName ?: "unknown",
                minSdk,
                target,
                compatible,
                if (compatible) "Android version အရ install လုပ်နိုင်ဖွယ်ရှိသည်"
                else "ဒီ APK သည် Android $minSdk အနည်းဆုံးလိုအပ်သည်"
            )
        } catch (e: Exception) {
            ApkReport("", "", 0, 0, false, e.message ?: "APK စစ်ဆေးမှု မအောင်မြင်ပါ")
        } finally {
            temp.delete()
        }
    }
}
