package com.byf3332.cattunnel

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.LinkAddress
import android.os.Bundle
import android.text.Editable
import android.text.Spannable
import android.text.SpannableString
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import java.net.Inet4Address

class MainActivity : ComponentActivity() {

    private lateinit var spPort: Spinner
    private lateinit var etTcpPort: EditText
    private lateinit var tvListen: TextView
    private lateinit var tvLog: TextView
    private lateinit var logScroll: ScrollView

    private val ACTION_USB_PERMISSION = "com.byf3332.cattunnel.USB_PERMISSION"

    private val TARGET_VID = 0x10C4
    private val TARGET_PID = 0xEA70
    private var serviceRunning = false

    private data class Candidate(
        val device: UsbDevice,
        val driver: UsbSerialDriver,
        val portIndex: Int,
        val label: String
    )

    private val candidates = mutableListOf<Candidate>()
    private var selectedIndex: Int = 0

    private var pendingAction: String? = null

    private var permissionInFlight = false
    private var permissionDeviceName: String? = null

    // -----------------------------
    // Log settings
    // -----------------------------
    private enum class Level { INFO, TX, RX, ERR }

    private val MAX_LOG_CHARS = 120_000
    private val MAX_LOG_LINES = 2500

    private val COLOR_INFO = Color.parseColor("#222222")
    private val COLOR_TX   = Color.parseColor("#1565C0")
    private val COLOR_RX   = Color.parseColor("#2E7D32")
    private val COLOR_ERR  = Color.parseColor("#C62828")

    private fun levelColor(lv: Level): Int = when (lv) {
        Level.INFO -> COLOR_INFO
        Level.TX   -> COLOR_TX
        Level.RX   -> COLOR_RX
        Level.ERR  -> COLOR_ERR
    }

    private fun log(lv: Level, msg: String) {
        val prefix = when (lv) {
            Level.INFO -> "INFO"
            Level.TX   -> "TX  "
            Level.RX   -> "RX  "
            Level.ERR  -> "ERR "
        }

        val line = "$prefix | $msg\n"
        val spannable = SpannableString(line).apply {
            setSpan(
                ForegroundColorSpan(levelColor(lv)),
                0, line.length,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }

        runOnUiThread {
            tvLog.append(spannable)
            trimLogIfNeeded()
            logScroll.post { logScroll.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    private fun logInfo(msg: String) = log(Level.INFO, msg)
    private fun logTx(msg: String) = log(Level.TX, msg)
    private fun logRx(msg: String) = log(Level.RX, msg)
    private fun logErr(msg: String) = log(Level.ERR, msg)

    private fun trimLogIfNeeded() {
        val e = tvLog.editableText ?: return

        if (e.length > MAX_LOG_CHARS) {
            val cutTo = e.length - (MAX_LOG_CHARS * 2 / 3)
            var idx = e.indexOf("\n", cutTo)
            if (idx < 0) idx = cutTo
            e.delete(0, idx.coerceAtMost(e.length))
        }

        val text = e.toString()
        var lines = 0
        for (ch in text) if (ch == '\n') lines++
        if (lines > MAX_LOG_LINES) {
            val target = (MAX_LOG_LINES * 2 / 3)
            var keepFrom = 0
            var seen = 0
            for (i in text.length - 1 downTo 0) {
                if (text[i] == '\n') {
                    seen++
                    if (seen >= target) {
                        keepFrom = i + 1
                        break
                    }
                }
            }
            if (keepFrom > 0 && keepFrom < e.length) {
                e.delete(0, keepFrom)
            }
        }
    }

    // -----------------------------
    // Receivers
    // -----------------------------
    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_USB_PERMISSION) return

            permissionInFlight = false
            permissionDeviceName = null

            val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            if (!granted || device == null) {
                logErr("USB permission denied")
                return
            }
            logInfo("USB permission granted for ${device.deviceName}")

            when (pendingAction) {
                "probe" -> doProbeInternal()
                "testId" -> doTestIdInternal()
            }
        }
    }

    // ✅ 接收 Service 发来的日志
    private val serviceLogReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != CatBridgeService.ACTION_LOG) return
            val level = intent.getStringExtra(CatBridgeService.EXTRA_LOG_LEVEL) ?: "INFO"
            val msg = intent.getStringExtra(CatBridgeService.EXTRA_LOG_MSG) ?: return

            when (level) {
                "TX" -> logTx(msg)
                "RX" -> logRx(msg)
                "ERR" -> logErr(msg)
                else -> logInfo(msg)
            }
        }
    }

    // -----------------------------
    // Network: show LAN IP (Wi-Fi/Hotspot/USB)
    // -----------------------------
    private var cm: ConnectivityManager? = null
    private var netCallback: ConnectivityManager.NetworkCallback? = null

    private fun tcpPortValue(): Int =
        etTcpPort.text.toString().toIntOrNull() ?: 4532

    private fun isLanTransport(caps: NetworkCapabilities?): Boolean {
        if (caps == null) return false
        // ✅ 只认局域网类：Wi-Fi（含热点）、USB/RNDIS(通常是 ETHERNET)、蓝牙共享(可选)
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return true
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return true
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) return true
        return false
    }

    private fun getLanIpv4List(): List<String> {
        val c = cm ?: return emptyList()
        val out = linkedSetOf<String>()

        for (nw in c.allNetworks) {
            val caps = c.getNetworkCapabilities(nw)
            if (!isLanTransport(caps)) continue

            val lp = c.getLinkProperties(nw) ?: continue
            for (la: LinkAddress in lp.linkAddresses) {
                val addr = la.address
                if (addr is Inet4Address) {
                    if (addr.isLoopbackAddress) continue
                    if (addr.isLinkLocalAddress) continue  // 169.254.x.x
                    out.add(addr.hostAddress ?: continue)
                }
            }
        }
        return out.toList()
    }

    private fun refreshListenUi() {
        val port = tcpPortValue()
        val ips = getLanIpv4List()

        val text = if (ips.isEmpty()) {
            "Listen: (no LAN IP) :$port"
        } else {
            // 多 IP 就展示第一个（一般就是你要的那个），但你也能从日志看到全部
            "Listen: ${ips.first()}:$port"
        }
        tvListen.text = text
    }

    private fun startNetworkWatcher() {
        cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager

        // 初次刷新
        refreshListenUi()

        // 监听网络变化（Wi-Fi/热点开关、切换 AP 等都会触发）
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = refreshListenUi()
            override fun onLost(network: Network) = refreshListenUi()
            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) = refreshListenUi()
            override fun onLinkPropertiesChanged(network: Network, linkProperties: android.net.LinkProperties) = refreshListenUi()
        }
        netCallback = callback
        try {
            cm?.registerDefaultNetworkCallback(callback)
        } catch (_: Exception) {
            // 某些系统限制/bug：不注册也没事，至少按钮点的时候会刷新
        }
    }

    private fun stopNetworkWatcher() {
        try {
            val c = cm
            val cb = netCallback
            if (c != null && cb != null) c.unregisterNetworkCallback(cb)
        } catch (_: Exception) {}
        netCallback = null
        cm = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        spPort = findViewById(R.id.spPort)
        etTcpPort = findViewById(R.id.etTcpPort)
        tvListen = findViewById(R.id.tvListen)
        tvLog = findViewById(R.id.tvLog)
        logScroll = findViewById(R.id.logScroll)

        spPort.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            listOf("No ports (Probe first)")
        )
        etTcpPort.setText("4532")

        ContextCompat.registerReceiver(
            this, usbReceiver, IntentFilter(ACTION_USB_PERMISSION),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        ContextCompat.registerReceiver(
            this, serviceLogReceiver, IntentFilter(CatBridgeService.ACTION_LOG),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        // 端口改动时刷新 Listen: ip:port
        etTcpPort.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                refreshListenUi()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        startNetworkWatcher()

        spPort.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                selectedIndex = position
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        findViewById<Button>(R.id.btnProbe).setOnClickListener {
            pendingAction = "probe"
            requestPermissionForTargetOrBest()
        }

        findViewById<Button>(R.id.btnTestId).setOnClickListener {
            pendingAction = "testId"
            requestPermissionForSelectedOrTargetOrBest()
        }

        findViewById<Button>(R.id.btnStart).setOnClickListener {
            val tcpPort = tcpPortValue()
            val cand = candidates.getOrNull(selectedIndex)
            if (cand == null) {
                logErr("START: no candidate. Plug radio and Probe first.")
                return@setOnClickListener
            }

            val usb = getSystemService(USB_SERVICE) as UsbManager
            if (!usb.hasPermission(cand.device)) {
                pendingAction = "probe"
                logInfo("START: no permission yet, requesting…")
                requestPermissionIfNeeded(cand.device)
                return@setOnClickListener
            }

            val it = Intent(this, CatBridgeService::class.java).apply {
                action = CatBridgeService.ACTION_START
                putExtra(CatBridgeService.EXTRA_DEVICE_NAME, cand.device.deviceName)
                putExtra(CatBridgeService.EXTRA_TCP_PORT, tcpPort)
                putExtra(CatBridgeService.EXTRA_PORT_INDEX, cand.portIndex)
            }

            ContextCompat.startForegroundService(this, it)

            val ips = getLanIpv4List()
            if (ips.isEmpty()) {
                logInfo("START: service requested. TCP port=$tcpPort (no LAN IP yet)")
            } else {
                logInfo("START: service requested. Listen: ${ips.first()}:$tcpPort")
                if (ips.size > 1) logInfo("LAN IPs: ${ips.joinToString(", ")}")
            }

            serviceRunning = true
        }

        findViewById<Button>(R.id.btnStop).setOnClickListener {
            if (!serviceRunning) {
                logErr("STOP: bridge not running.")
                return@setOnClickListener
            }

            val it = Intent(this, CatBridgeService::class.java).apply {
                action = CatBridgeService.ACTION_STOP
            }
            startService(it)

            serviceRunning = false
            logInfo("STOP: service requested.")
        }

        findViewById<Button>(R.id.btnClearLog).setOnClickListener {
            runOnUiThread { tvLog.text = "" }
            logInfo("Log cleared.")
        }

        handleUsbAttachIntent(intent)
        refreshListenUi()
        logInfo("UI ready. Plug radio and press TEST.")
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleUsbAttachIntent(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        stopNetworkWatcher()
        try { unregisterReceiver(usbReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(serviceLogReceiver) } catch (_: Exception) {}
    }

    private fun handleUsbAttachIntent(intent: Intent) {
        if (intent.action != UsbManager.ACTION_USB_DEVICE_ATTACHED) return
        val dev = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE) ?: return

        logInfo("USB attached via intent: ${dev.deviceName} vid=${dev.vendorId} pid=${dev.productId}")

        if (!(dev.vendorId == TARGET_VID && dev.productId == TARGET_PID)) {
            logInfo("Ignore non-CP210x attach")
            return
        }

        pendingAction = "probe"
        requestPermissionIfNeeded(dev)
    }

    private fun requestPermissionForTargetOrBest() {
        val usb = getSystemService(USB_SERVICE) as UsbManager
        val devices = usb.deviceList.values.toList()
        if (devices.isEmpty()) {
            logErr("No USB devices. Check OTG + cable.")
            return
        }

        val target = devices.firstOrNull { it.vendorId == TARGET_VID && it.productId == TARGET_PID }
        if (target != null) {
            requestPermissionIfNeeded(target)
            return
        }

        val best = devices.firstOrNull { d ->
            val driver = UsbSerialProber.getDefaultProber().probeDevice(d)
            driver != null && driver.ports.isNotEmpty()
        }

        if (best == null) {
            logErr("No USB serial device found by DefaultProber.")
            return
        }

        requestPermissionIfNeeded(best)
    }

    private fun requestPermissionForSelectedOrTargetOrBest() {
        val selDevice = candidates.getOrNull(selectedIndex)?.device
        if (selDevice != null) {
            requestPermissionIfNeeded(selDevice)
            return
        }
        requestPermissionForTargetOrBest()
    }

    private fun requestPermissionIfNeeded(device: UsbDevice) {
        val usb = getSystemService(USB_SERVICE) as UsbManager

        if (usb.hasPermission(device)) {
            permissionInFlight = false
            permissionDeviceName = null
            when (pendingAction) {
                "probe" -> doProbeInternal()
                "testId" -> doTestIdInternal()
            }
            return
        }

        if (permissionInFlight && permissionDeviceName == device.deviceName) {
            logInfo("Permission request already in-flight for ${device.deviceName}")
            return
        }

        permissionInFlight = true
        permissionDeviceName = device.deviceName

        val pi = PendingIntent.getBroadcast(
            this, 0, Intent(ACTION_USB_PERMISSION),
            PendingIntent.FLAG_IMMUTABLE
        )
        usb.requestPermission(device, pi)
        logInfo("Requested USB permission for ${device.deviceName} ...")
    }

    private fun doProbeInternal() {
        val usb = getSystemService(USB_SERVICE) as UsbManager
        val devices = usb.deviceList.values.toList()
        logInfo("USB devices found: ${devices.size}")
        if (devices.isEmpty()) return

        candidates.clear()

        for ((devIdx, d) in devices.withIndex()) {
            logInfo("[$devIdx] ${d.deviceName} vid=${d.vendorId} pid=${d.productId} ifaces=${d.interfaceCount}")
            for (i in 0 until d.interfaceCount) {
                val intf = d.getInterface(i)
                logInfo("    if[$i] class=${intf.interfaceClass} sub=${intf.interfaceSubclass} proto=${intf.interfaceProtocol}")
            }

            val driver = UsbSerialProber.getDefaultProber().probeDevice(d)
            if (driver == null || driver.ports.isEmpty()) {
                logInfo("    -> no serial driver")
                continue
            }

            logInfo("    -> serial driver=${driver.javaClass.simpleName} ports=${driver.ports.size}")
            for (p in driver.ports.indices) {
                val label = "dev$devIdx-port$p  ${d.vendorId}:${d.productId}  ${driver.javaClass.simpleName}"
                candidates.add(Candidate(d, driver, p, label))
            }
        }

        if (candidates.isEmpty()) {
            logErr("No serial ports found by DefaultProber.")
            spPort.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, listOf("No ports"))
            selectedIndex = 0
            return
        }

        candidates.sortWith(compareBy<Candidate>(
            { !(it.device.vendorId == TARGET_VID && it.device.productId == TARGET_PID) },
            { it.portIndex }
        ))

        val labels = candidates.map { it.label }
        spPort.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        selectedIndex = 0
        spPort.setSelection(0)
        logInfo("Candidates: ${labels.size}. Selected index=0")
    }

    private fun doTestIdInternal() {
        val usb = getSystemService(USB_SERVICE) as UsbManager
        val cand = candidates.getOrNull(selectedIndex)
        if (cand == null) {
            logErr("TestID: no candidate. Please Probe first.")
            return
        }

        val device = cand.device
        val driver = cand.driver
        val portIndex = cand.portIndex

        val ports = driver.ports
        logInfo("TestID selected: ${cand.label}")
        logInfo("driver=${driver.javaClass.simpleName} ports.size=${ports.size}")

        if (portIndex !in ports.indices) {
            logErr("Port index invalid: $portIndex")
            return
        }

        val conn = usb.openDevice(device)
        if (conn == null) {
            logErr("openDevice() failed (permission?)")
            return
        }

        val port: UsbSerialPort = ports[portIndex]
        val baudCandidates = listOf(38400, 9600, 19200, 115200, 57600, 4800)
        val cmd = "ID;"

        try {
            port.open(conn)
            try { port.dtr = true } catch (_: Exception) {}
            try { port.rts = true } catch (_: Exception) {}
            try { port.purgeHwBuffers(true, true) } catch (_: Exception) {}

            for (baud in baudCandidates) {
                try {
                    port.setParameters(baud, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)

                    logTx("baud=$baud -> $cmd")
                    port.write(cmd.toByteArray(Charsets.US_ASCII), 1000)

                    val buf = ByteArray(256)
                    val n = port.read(buf, 2000)
                    if (n > 0) {
                        val resp = String(buf, 0, n, Charsets.US_ASCII)
                        logRx("baud=$baud <- ${resp.replace("\n", "\\n").replace("\r", "\\r")}")
                        return
                    } else {
                        logInfo("baud=$baud no response")
                    }
                } catch (e: Exception) {
                    logErr("baud=$baud error: ${e::class.java.simpleName}: ${e.message}")
                }
            }

            logErr("TestID failed on all baud candidates.")
        } catch (e: Exception) {
            logErr("Error: ${e::class.java.simpleName}: ${e.message}")
        } finally {
            try { port.close() } catch (_: Exception) {}
            try { conn.close() } catch (_: Exception) {}
        }
    }
}