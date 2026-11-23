package com.massspx.xshare

import android.content.Context
import android.net.Uri
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class TransferProtocol(private val context: Context, private val tuner: AdaptiveTuner) {
    companion object {
        const val PORT = 45872
        const val SERVICE_TYPE = "_xshare._tcp."
        private const val MAX_FILE_BYTES = 20L * 1024 * 1024 * 1024
    }

    private val running = AtomicBoolean(false)
    @Volatile private var server: ServerSocket? = null

    fun startReceiver(
        onIncoming: (String, Long, (Boolean) -> Unit) -> Unit,
        onDone: (File?, String) -> Unit
    ) {
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
            } catch (e: Exception) {
                if (running.get()) onDone(null, e.message ?: "Receiver error")
            } finally { server = null }
        }.start()
    }

    private fun receiveOne(
        socket: Socket,
        onIncoming: (String, Long, (Boolean) -> Unit) -> Unit,
        onDone: (File?, String) -> Unit
    ) {
        socket.use { s ->
            var partial: File? = null
            try {
                s.soTimeout = 90_000
                val input = DataInputStream(BufferedInputStream(s.getInputStream()))
                val output = DataOutputStream(BufferedOutputStream(s.getOutputStream()))
                val rawName = input.readUTF()
                val name = rawName.substringAfterLast('/').substringAfterLast('\\')
                    .replace(Regex("[^A-Za-z0-9._ -]"), "_").take(120).ifBlank { "received_file" }
                val size = input.readLong()
                val expected = input.readUTF()
                if (size < 0 || size > MAX_FILE_BYTES || !expected.matches(Regex("[a-fA-F0-9]{64}"))) {
                    output.writeByte(0); output.flush()
                    throw IllegalArgumentException("ဖိုင်အချက်အလက် မမှန်ပါ")
                }

                val decision = CountDownLatch(1)
                val accepted = AtomicBoolean(false)
                onIncoming(name, size) { allow ->
                    accepted.set(allow)
                    decision.countDown()
                    true
                }
                val decided = decision.await(90, TimeUnit.SECONDS) && accepted.get()
                output.writeByte(if (decided) 1 else 0)
                output.flush()
                if (!decided) throw SecurityException("လက်ခံသူက ဖိုင်ပို့ခြင်းကို ငြင်းပယ်လိုက်သည်")

                val dir = context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
                if (!dir.exists() && !dir.mkdirs()) throw IllegalStateException("သိမ်းဆည်းမည့်နေရာ ဖန်တီးမရပါ")
                var finalFile = File(dir, name)
                if (finalFile.exists()) finalFile = File(dir, "${System.currentTimeMillis()}_$name")
                partial = File(dir, ".${finalFile.name}.part")
                val digest = MessageDigest.getInstance("SHA-256")
                val bufferSize = tuner.plan(size).bufferBytes.coerceIn(32 * 1024, 512 * 1024)
                partial!!.outputStream().buffered(bufferSize).use { fileOut ->
                    val buffer = ByteArray(bufferSize)
                    var remaining = size
                    while (remaining > 0) {
                        val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                        if (count < 0) throw EOFException("ဖိုင်ပို့မှု မပြီးဆုံးခင် ပြတ်တောက်သွားသည်")
                        fileOut.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                        remaining -= count
                    }
                }
                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                if (!actual.equals(expected, true)) throw SecurityException("SHA-256 မကိုက်ညီပါ")
                if (!partial!!.renameTo(finalFile)) throw IllegalStateException("ဖိုင်သိမ်းဆည်းမရပါ")
                partial = null
                output.writeByte(1)
                output.flush()
                onDone(finalFile, "ဖိုင်လက်ခံပြီး · SHA-256 စစ်ဆေးမှုအောင်မြင်")
            } catch (e: Exception) {
                try { partial?.delete() } catch (_: Exception) {}
                try { DataOutputStream(s.getOutputStream()).writeByte(0) } catch (_: Exception) {}
                onDone(null, e.message ?: "လက်ခံမှု မအောင်မြင်ပါ")
            }
        }
    }

    fun send(
        host: String,
        port: Int,
        uri: Uri,
        displayName: String,
        onProgress: (Long, Long) -> Unit,
        onDone: (Boolean, String) -> Unit
    ) {
        Thread {
            val started = System.currentTimeMillis()
            var sent = 0L
            try {
                val size = context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
                if (size < 0 || size > MAX_FILE_BYTES) throw IllegalArgumentException("ဖိုင်အရွယ်အစားကို ဖတ်မရပါ သို့မဟုတ် 20 GB ကျော်နေသည်")
                val digest = MessageDigest.getInstance("SHA-256")
                context.contentResolver.openInputStream(uri).use { stream ->
                    if (stream == null) throw IllegalArgumentException("ဖိုင်ကို ဖွင့်မရပါ")
                    val buffer = ByteArray(128 * 1024)
                    while (true) { val n = stream.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
                }
                val hash = digest.digest().joinToString("") { "%02x".format(it) }
                Socket().use { socket ->
                    socket.connect(java.net.InetSocketAddress(host, port), 12_000)
                    socket.soTimeout = 90_000
                    socket.tcpNoDelay = true
                    val out = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
                    val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
                    out.writeUTF(displayName.take(120))
                    out.writeLong(size)
                    out.writeUTF(hash)
                    out.flush()
                    if (input.readUnsignedByte() != 1) throw SecurityException("လက်ခံသူက ဖိုင်ကို လက်မခံပါ")
                    context.contentResolver.openInputStream(uri).use { stream ->
                        if (stream == null) throw IllegalArgumentException("ဖိုင်ကို ပြန်ဖွင့်မရပါ")
                        val buffer = ByteArray(tuner.plan(size).bufferBytes.coerceIn(32 * 1024, 512 * 1024))
                        var remaining = size
                        while (remaining > 0) {
                            val n = stream.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                            if (n < 0) throw EOFException("ဖိုင်ဖတ်နေစဉ် ပြတ်တောက်သွားသည်")
                            out.write(buffer, 0, n)
                            sent += n
                            remaining -= n
                            onProgress(sent, size)
                        }
                        out.flush()
                    }
                    if (input.readUnsignedByte() != 1) throw SecurityException("လက်ခံစက်တွင် ဖိုင်စစ်ဆေးမှု မအောင်မြင်ပါ")
                }
                tuner.record(sent, System.currentTimeMillis() - started, true)
                onDone(true, "ပို့ပြီး · လက်ခံစက်တွင် ဖိုင်စစ်ဆေးမှုအောင်မြင်")
            } catch (e: Exception) {
                tuner.record(sent, System.currentTimeMillis() - started, false)
                onDone(false, e.message ?: "ပို့မှု မအောင်မြင်ပါ")
            }
        }.start()
    }

    fun stop() {
        running.set(false)
        try { server?.close() } catch (_: Exception) {}
    }
}
