package com.massspx.xshare

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : Activity() {
    private lateinit var web: WebView
    private lateinit var tuner: AdaptiveTuner
    private lateinit var transfer: TransferProtocol
    private lateinit var nsd: NsdManager
    private var registration: NsdManager.RegistrationListener? = null
    private var discovery: NsdManager.DiscoveryListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private val devices = ConcurrentHashMap<String, Device>()
    private data class Device(val host: String, val port: Int)
    private var selectedUri: Uri? = null
    private var selectedName = "file"
    private var ownServiceName = ""
    private val pickFile = 1001
    private val nearbyPermission = 1002

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tuner = AdaptiveTuner(this)
        transfer = TransferProtocol(this, tuner)
        nsd = getSystemService(NSD_SERVICE) as NsdManager
        ownServiceName = "XShare-${Build.MODEL.take(18)}"
        setupWebUi()
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES), nearbyPermission)
        } else if (Build.VERSION.SDK_INT <= 32 &&
            checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), nearbyPermission)
        } else startNetworking()
    }

    private fun setupWebUi() {
        web = WebView(this)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.allowFileAccess = true
        web.settings.allowContentAccess = false
        web.webChromeClient = WebChromeClient()
        web.webViewClient = WebViewClient()
        web.addJavascriptInterface(NativeBridge(), "Native")
        setContentView(web)
        web.loadUrl("file:///android_asset/index.html")
    }

    private inner class NativeBridge {
        @JavascriptInterface fun pickFile() { runOnUiThread { chooseFile() } }
        @JavascriptInterface fun scan() { runOnUiThread { restartDiscovery() } }
        @JavascriptInterface fun sendTo(name: String) { runOnUiThread { sendToDevice(name) } }
    }

    private fun setStatus(message: String) {
        val safe = JSONObject.quote(message)
        runOnUiThread { if (::web.isInitialized) web.evaluateJavascript("window.XShare && window.XShare.setStatus($safe)", null) }
    }

    private fun renderDevices() {
        val array = JSONArray()
        devices.toSortedMap().forEach { (name, endpoint) ->
            array.put(JSONObject().put("name", name).put("host", endpoint.host).put("port", endpoint.port))
        }
        val json = array.toString()
        runOnUiThread { if (::web.isInitialized) web.evaluateJavascript("window.XShare && window.XShare.renderDevices($json)", null) }
    }

    private fun startNetworking() {
        try {
            val wifi = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
            multicastLock = wifi.createMulticastLock("xshare-mdns").apply { setReferenceCounted(false); acquire() }
        } catch (_: Exception) {}
        transfer.startReceiver(
            onIncoming = { name, size, decide ->
                runOnUiThread {
                    val sizeText = if (size >= 1024L * 1024) "${size / (1024 * 1024)} MB" else "${size / 1024} KB"
                    val dialog = AlertDialog.Builder(this)
                        .setTitle("ဖိုင်လက်ခံမလား?")
                        .setMessage("$name\n$sizeText")
                        .setPositiveButton("လက်ခံမည်") { _, _ -> decide(true) }
                        .setNegativeButton("ငြင်းမည်") { _, _ -> decide(false) }
                        .setOnCancelListener { decide(false) }
                        .create()
                    dialog.show()
                }
            },
            onDone = { file, message ->
                setStatus(if (file != null) "$message · ${file.name}" else message)
                runOnUiThread { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
            }
        )
        registerService()
        startDiscovery()
        setStatus("Wi-Fi Local Network မှ စက်များကို ရှာဖွေနေသည်")
    }

    private fun registerService() {
        val info = NsdServiceInfo().apply {
            serviceName = ownServiceName
            serviceType = TransferProtocol.SERVICE_TYPE
            port = TransferProtocol.PORT
        }
        registration = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                setStatus("လက်ခံရန် အသင့်ဖြစ်ပြီ · ${serviceInfo.serviceName}")
            }
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                setStatus("စက်ကြေညာမှု မအောင်မြင်ပါ ($errorCode)")
            }
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {}
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
        }
        try { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, registration) }
        catch (e: Exception) { setStatus("Service register error: ${e.message}") }
    }

    private fun startDiscovery() {
        discovery = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) { setStatus("အနီးအနားက စက်များကို ရှာနေသည်…") }
            override fun onServiceFound(info: NsdServiceInfo) {
                if (info.serviceName == ownServiceName || !info.serviceType.contains("_xshare._tcp")) return
                try {
                    nsd.resolveService(info, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
                        override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                            val host = serviceInfo.host?.hostAddress ?: return
                            devices[serviceInfo.serviceName] = Device(host, serviceInfo.port)
                            renderDevices()
                        }
                    })
                } catch (_: Exception) {}
            }
            override fun onServiceLost(info: NsdServiceInfo) {
                devices.remove(info.serviceName)
                renderDevices()
            }
            override fun onDiscoveryStopped(type: String) {}
            override fun onStartDiscoveryFailed(type: String, errorCode: Int) {
                setStatus("စက်ရှာမရပါ ($errorCode)")
                try { discovery?.let { nsd.stopServiceDiscovery(it) } } catch (_: Exception) {}
            }
            override fun onStopDiscoveryFailed(type: String, errorCode: Int) {}
        }
        try { nsd.discoverServices(TransferProtocol.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discovery) }
        catch (e: Exception) { setStatus("Discovery error: ${e.message}") }
    }

    private fun chooseFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "*/*"
            addCategory(Intent.CATEGORY_OPENABLE)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(intent, pickFile)
    }

    private fun sendToDevice(name: String) {
        val uri = selectedUri
        val endpoint = devices[name]
        if (uri == null) {
            setStatus("အရင်ဆုံး ＋ ဖိုင်ရွေးမယ် ကိုနှိပ်ပါ")
            return
        }
        if (endpoint == null) {
            setStatus("ဒီစက်ကို မတွေ့တော့ပါ။ ပြန်ရှာပါ")
            return
        }
        setStatus("$name သို့ ပို့ရန် ချိတ်ဆက်နေသည်…")
        transfer.send(endpoint.host, endpoint.port, uri, selectedName,
            onProgress = { done, total ->
                val percent = if (total > 0) (done * 100 / total).toInt() else 0
                setStatus("ပို့နေသည်… $percent% · ${done / 1024} / ${total / 1024} KB")
            },
            onDone = { ok, message ->
                setStatus(if (ok) "✓ $message" else "ပို့မအောင်မြင်: $message")
                runOnUiThread { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
            }
        )
    }

    @Deprecated("Legacy result API retained for broad Android compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == pickFile && resultCode == Activity.RESULT_OK) {
            val uri = data?.data ?: return
            selectedUri = uri
            try { data.flags.let { flags -> contentResolver.takePersistableUriPermission(uri, flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)) } }
            catch (_: Exception) {}
            selectedName = queryName(uri)
            setStatus("ရွေးထားသောဖိုင်: $selectedName · ပို့မည့်စက်ကို ရွေးပါ")
            if (selectedName.endsWith(".apk", true)) {
                Thread {
                    val report = ApkInspector.inspect(this, uri)
                    runOnUiThread {
                        AlertDialog.Builder(this).setTitle("APK Compatibility")
                            .setMessage("Package: ${report.packageName}\nVersion: ${report.versionName}\nMin Android: ${report.minSdk}\n${report.message}")
                            .setPositiveButton("OK", null).show()
                    }
                }.start()
            }
        }
    }

    private fun queryName(uri: Uri): String {
        var name = "shared_file"
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) name = it.getString(0) ?: name
        }
        return name
    }

    private fun restartDiscovery() {
        try { discovery?.let { nsd.stopServiceDiscovery(it) } } catch (_: Exception) {}
        devices.clear()
        renderDevices()
        startDiscovery()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == nearbyPermission) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                startNetworking()
            } else {
                setStatus("အနီးအနားစက်ရှာဖွေရန် Wi-Fi/Location ခွင့်ပြုချက်လိုအပ်သည်။ Settings မှ ခွင့်ပြုပေးပါ")
            }
        }
    }

    override fun onDestroy() {
        try { discovery?.let { nsd.stopServiceDiscovery(it) } } catch (_: Exception) {}
        try { registration?.let { nsd.unregisterService(it) } } catch (_: Exception) {}
        try { multicastLock?.let { if (it.isHeld) it.release() } } catch (_: Exception) {}
        transfer.stop()
        web.removeJavascriptInterface("Native")
        web.destroy()
        super.onDestroy()
    }
}
