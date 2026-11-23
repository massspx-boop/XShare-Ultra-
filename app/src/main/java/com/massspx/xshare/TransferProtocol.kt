package com.massspx.xshare

import android.content.Context
import android.net.Uri
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

class TransferProtocol(private val context: Context, private val tuner: AdaptiveTuner) {
    companion object { const val PORT = 45872; const val SERVICE_TYPE = "_xshare._tcp." }
    private val running = AtomicBoolean(false)
    @Volatile private var server: ServerSocket? = null

    fun startReceiver(onIncoming: (String, Long, () -> Boolean) -> Unit, onDone: (File?, String) -> Unit) {
        if (!running.compareAndSet(false, true)) return
        Thread {
            try {
                ServerSocket(PORT).use { ss ->
                    server = ss
                    while (running.get()) {
                        val socket = try { ss.accept() } catch (_: Exception) { break }
                        Thread { receiveOne(socket, onIncoming, onDone) }.start()
                    }
                }
            } catch (e: Exception) { onDone(null, e.message ?: "Receiver error") }
        }.start()
    }

    private fun receiveOne(socket: Socket, onIncoming: (String, Long, () -> Boolean) -> Unit, onDone: (File?, String) -> Unit) {
        socket.use { s ->
            try {
                s.soTimeout = 120_000
                val input = DataInputStream(BufferedInputStream(s.getInputStream()))
                val name = input.readUTF().replace(Regex("[^A-Za-z0-9._ -]"), "_").take(120).ifBlank { "received_file" }
                val size = input.readLong()
                val expected = input.readUTF()
                if (size < 0 || size > 20L * 1024 * 1024 * 1024) throw IllegalArgumentException("Invalid file size")
                var approved = false
                val latch = java.util.concurrent.CountDownLatch(1)
                onIncoming(name, size) { approved = true; latch.countDown(); true }
                // UI callback may decline by not invoking the approval closure; timeout protects a stalled dialog.
                if (!latch.await(90, java.util.concurrent.TimeUnit.SECONDS) || !approved) throw SecurityException("Transfer not approved")
                val dir = context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
                val outFile = File(dir, name)
                val safeFile = if (outFile.exists()) File(dir, "${System.currentTimeMillis()}_$name") else outFile
                val digest = MessageDigest.getInstance("SHA-256")
                safeFile.outputStream().buffered(64 * 1024).use { output ->
                    val buffer = ByteArray(tuner.plan(size).bufferBytes)
                    var remaining = size
                    while (remaining > 0) {
                        val n = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                        if (n < 0) throw java.io.EOFException("Transfer ended early")
                        output.write(buffer, 0, n); digest.update(buffer, 0, n); remaining -= n
                    }
                }
                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                if (!actual.equals(expected, true)) { safeFile.delete(); throw SecurityException("SHA-256 verification failed") }
                onDone(safeFile, "Verified successfully")
            } catch (e: Exception) { onDone(null, e.message ?: "Receive failed") }
        }
    }

    fun send(host: String, port: Int, uri: Uri, displayName: String, onProgress: (Long, Long) -> Unit, onDone: (Boolean, String) -> Unit) {
        Thread {
            val started = System.currentTimeMillis()
            var bytes = 0L
            try {
                val size = context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
                if (size < 0) throw IllegalArgumentException("Cannot read file size")
                val sha = MessageDigest.getInstance("SHA-256")
                context.contentResolver.openInputStream(uri).use { stream ->
                    if (stream == null) throw IllegalArgumentException("Cannot open selected file")
                    val buf = ByteArray(64 * 1024)
                    while (true) { val n = stream.read(buf); if (n < 0) break; sha.update(buf, 0, n) }
                }
                val hash = sha.digest().joinToString("") { "%02x".format(it) }
                Socket(host, port).use { socket ->
                    socket.tcpNoDelay = true
                    val out = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
                    out.writeUTF(displayName.take(120)); out.writeLong(size); out.writeUTF(hash); out.flush()
                    context.contentResolver.openInputStream(uri).use { stream ->
                        if (stream == null) throw IllegalArgumentException("Cannot reopen selected file")
                        val buffer = ByteArray(tuner.plan(size).bufferBytes)
                        while (true) {
                            val n = stream.read(buffer); if (n < 0) break
                            out.write(buffer, 0, n); bytes += n; onProgress(bytes, size)
                        }
                        out.flush()
                    }
                }
                tuner.record(bytes, System.currentTimeMillis() - started, true)
                onDone(true, "Sent and verified by receiver")
            } catch (e: Exception) {
                tuner.record(bytes, System.currentTimeMillis() - started, false)
                onDone(false, e.message ?: "Send failed")
            }
        }.start()
    }

    fun stop() { running.set(false); try { server?.close() } catch (_: Exception) {} }
}
