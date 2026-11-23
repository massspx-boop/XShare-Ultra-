package com.massspx.xshare

import android.app.ActivityManager
import android.content.Context
import kotlin.math.max
import kotlin.math.min

/**
 * Small deterministic offline controller. It uses only local transfer outcomes;
 * it never downloads a model or allocates a large inference runtime.
 */
class AdaptiveTuner(context: Context) {
    data class Plan(val chunkBytes: Int, val bufferBytes: Int, val parallelStreams: Int, val retryDelayMs: Long)
    private val prefs = context.getSharedPreferences("tuning_profile", Context.MODE_PRIVATE)
    private val lowRam = (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).isLowRamDevice

    fun plan(fileBytes: Long): Plan {
        val avgMbps = prefs.getFloat("avg_mbps", 8f).coerceIn(0.2f, 200f)
        val stable = prefs.getFloat("stability", 0.8f).coerceIn(0.1f, 1f)
        val chunk = when {
            lowRam -> 64 * 1024
            stable < 0.45f -> 64 * 1024
            avgMbps > 35f && fileBytes > 64L * 1024 * 1024 -> 512 * 1024
            else -> 256 * 1024
        }
        return Plan(chunk, if (lowRam) 64 * 1024 else 256 * 1024, 1, if (stable < 0.4f) 900 else 350)
    }

    fun record(bytes: Long, elapsedMs: Long, succeeded: Boolean) {
        if (elapsedMs <= 0) return
        val mbps = (bytes.toDouble() * 8.0 / elapsedMs / 1000.0).toFloat().coerceIn(0f, 300f)
        val old = prefs.getFloat("avg_mbps", 8f)
        val stability = prefs.getFloat("stability", 0.8f)
        prefs.edit()
            .putFloat("avg_mbps", old * 0.75f + mbps * 0.25f)
            .putFloat("stability", (stability * 0.8f + (if (succeeded) 1f else 0f) * 0.2f).coerceIn(0.1f, 1f))
            .apply()
    }
}
