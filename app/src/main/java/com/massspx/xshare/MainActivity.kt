package com.massspx.xshare

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.provider.OpenableColumns
import java.util.concurrent.ConcurrentHashMap

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var devicesBox: LinearLayout
    private lateinit var tuner: AdaptiveTuner
    private lateinit var transfer: TransferProtocol
    private lateinit var nsd: NsdManager
    private var registration: NsdManager.RegistrationListener? = null
    private var discovery: NsdManager.DiscoveryListener? = null
    private val devices = ConcurrentHashMap<String, Pair<String, Int>>()
    private var selectedUri: Uri? = null
    private var selectedName = "file"
    private val pickFile = 1001
    private val nearbyPermission = 1002

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tuner = AdaptiveTuner(this)
        transfer = TransferProtocol(this, tuner)
        nsd = getSystemService(NSD_SERVICE) as NsdManager
        buildUi()
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES), nearbyPermission)
        } else startNetworking()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(22, 24, 22, 20); setBackgroundColor(0xFFF4F6FA.toInt()) }
        val title = TextView(this).apply { text = "XShare Ultra"; textSize = 28f; setTextColor(0xFF101828.toInt()); gravity = Gravity.CENTER_VERTICAL }
        val subtitle = TextView(this).apply { text = "အနီးအနားရှိ ဖုန်းများသို့ ဖိုင်ပို့/လက်ခံ"; textSize = 14f; setTextColor(0xFF667085.toInt()); setPadding(0, 4, 0, 18) }
        status = TextView(this).apply { text = "စတင်နေသည်…"; textSize = 14f; setTextColor(0xFF344054.toInt()); setPadding(0, 12, 0, 12) }
        val pick = Button(this).apply { text = "ဖိုင်ရွေးပြီး ပို့မည်"; setOnClickListener { chooseFile() } }
        val refresh = Button(this).apply { text = "စက်များ ပြန်ရှာမည်"; setOnClickListener { restartDiscovery() } }
        val heading = TextView(this).apply { text = "တွေ့ရှိသော စက်များ"; textSize = 18f; setTextColor(0xFF101828.toInt()); setPadding(0, 20, 0, 8) }
        devicesBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply { addView(devicesBox) }
        root.addView(title); root.addView(subtitle); root.addView(pick)
        root.addView(refresh); root.addView(status); root.addView(heading)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    private fun startNetworking() {
        transfer.startReceiver(
            onIncoming = { name, size, approve ->
                runOnUiThread {
                    AlertDialog.Builder(this)
                        .setTitle("ဖိုင်လက်ခံမလား?")
                        .setMessage("$name\n${size / 1024} KB")
                        .setPositiveButton("လက်ခံမည်") { _, _ -> approve() }
                        .setNegativeButton("ငြင်းမည်", null)
                        .setOnCancelListener { }
                        .show()
                }
            },
            onDone = { file, message -> runOnUiThread {
                status.text = if (file != null) "လက်ခံပြီး: ${file.name}" else "လက်ခံမှု: $message"
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            } }
        )
        registerService()
        startDiscovery()
        status.text = "Wi-Fi တစ်ခုတည်းကို ချိတ်ဆက်ထားသော စက်များကို ရှာနေသည်"
    }

    private fun registerService() {
        val info = NsdServiceInfo().apply { serviceName = "XShare-${Build.MODEL.take(18)}"; serviceType = TransferProtocol.SERVICE_TYPE; port = TransferProtocol.PORT }
        registration = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) { runOnUiThread { status.text = "လက်ခံရန် အသင့်ဖြစ်ပြီ · ${serviceInfo.serviceName}" } }
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) { runOnUiThread { status.text = "စက်ကြေညာမှု မအောင်မြင်ပါ ($errorCode)" } }
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {}
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
        }
        try { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, registration) } catch (_: Exception) {}
    }

    private fun startDiscovery() {
        discovery = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) {}
            override fun onServiceFound(info: NsdServiceInfo) {
                if (info.serviceType.contains("_xshare._tcp")) {
                    try {
                        nsd.resolveService(info, object : NsdManager.ResolveListener {
                            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
                            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                                val host = serviceInfo.host?.hostAddress ?: return
                                if (host == "127.0.0.1") return
                                devices[serviceInfo.serviceName] = host to serviceInfo.port
                                runOnUiThread { renderDevices() }
                            }
                        })
                    } catch (_: Exception) {}
                }
            }
            override fun onServiceLost(info: NsdServiceInfo) { devices.remove(info.serviceName); runOnUiThread { renderDevices() } }
            override fun onDiscoveryStopped(type: String) {}
            override fun onStartDiscoveryFailed(type: String, errorCode: Int) { runOnUiThread { status.text = "စက်ရှာမရပါ ($errorCode)" } }
            override fun onStopDiscoveryFailed(type: String, errorCode: Int) {}
        }
        try { nsd.discoverServices(TransferProtocol.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discovery) } catch (_: Exception) {}
    }

    private fun renderDevices() {
        devicesBox.removeAllViews()
        if (devices.isEmpty()) {
            devicesBox.addView(TextView(this).apply { text = "စက်မတွေ့သေးပါ။ နှစ်ဖက်လုံး Wi-Fi တစ်ခုတည်း ချိတ်ထားပါ။"; setPadding(4, 12, 4, 12); setTextColor(0xFF667085.toInt()) })
            return
        }
        devices.toSortedMap().forEach { (name, endpoint) ->
            devicesBox.addView(Button(this).apply {
                text = "ပို့ရန် → $name"
                setOnClickListener {
                    val uri = selectedUri
                    if (uri == null) { Toast.makeText(this@MainActivity, "အရင်ဆုံး ဖိုင်ရွေးပါ", Toast.LENGTH_SHORT).show() }
                    else {
                        status.text = "$name သို့ ပို့နေသည်…"
                        transfer.send(endpoint.first, endpoint.second, uri, selectedName,
                            onProgress = { done, total -> runOnUiThread { status.text = "ပို့နေသည်: ${done / 1024} / ${total / 1024} KB" } },
                            onDone = { ok, msg -> runOnUiThread { status.text = msg; Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show() } })
                    }
                }
            })
        }
    }

    private fun chooseFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply { type = "*/*"; addCategory(Intent.CATEGORY_OPENABLE); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        startActivityForResult(intent, pickFile)
    }

    @Deprecated("Legacy result API retained for broad Android compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == pickFile && resultCode == Activity.RESULT_OK) {
            val uri = data?.data ?: return
            selectedUri = uri
            selectedName = queryName(uri)
            status.text = "ရွေးထားသောဖိုင်: $selectedName · ပို့မည့်စက်ကိုနှိပ်ပါ"
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
        devices.clear(); renderDevices(); startDiscovery()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == nearbyPermission) startNetworking()
    }

    override fun onDestroy() {
        try { discovery?.let { nsd.stopServiceDiscovery(it) } } catch (_: Exception) {}
        try { registration?.let { nsd.unregisterService(it) } } catch (_: Exception) {}
        transfer.stop()
        super.onDestroy()
    }
}
