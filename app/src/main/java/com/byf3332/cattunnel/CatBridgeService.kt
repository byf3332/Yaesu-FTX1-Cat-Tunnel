package com.byf3332.cattunnel

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber

class CatBridgeService : Service() {

    companion object {
        const val ACTION_START = "com.byf3332.cattunnel.action.START"
        const val ACTION_STOP  = "com.byf3332.cattunnel.action.STOP"

        const val EXTRA_DEVICE_NAME = "deviceName"
        const val EXTRA_TCP_PORT = "tcpPort"
        const val EXTRA_PORT_INDEX = "portIndex"

        private const val CHANNEL_ID = "cat_tunnel"
        private const val NOTIF_ID = 1001
        private var isRunning = false

        // ✅ Activity 侧接收日志用
        const val ACTION_LOG = "com.byf3332.cattunnel.action.LOG"
        const val EXTRA_LOG_LEVEL = "level"
        const val EXTRA_LOG_MSG = "msg"
    }

    private var bridge: CatTcpBridge? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    private fun emitLog(level: String, msg: String) {
        val it = Intent(ACTION_LOG).apply {
            setPackage(packageName) // ✅ 只发给本 app
            putExtra(EXTRA_LOG_LEVEL, level)
            putExtra(EXTRA_LOG_MSG, msg)
        }
        sendBroadcast(it)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val deviceName = intent.getStringExtra(EXTRA_DEVICE_NAME)
                val tcpPort = intent.getIntExtra(EXTRA_TCP_PORT, 4532)
                val portIndex = intent.getIntExtra(EXTRA_PORT_INDEX, 0)

                if (deviceName.isNullOrBlank()) {
                    stopSelf()
                    return START_NOT_STICKY
                }

                // ⚠️ Android 8+ 必须尽快 startForeground（5s 内）
                startAsForeground("Starting… TCP:$tcpPort")
                acquireWakeLock()

                emitLog("INFO", "Service START requested. dev=$deviceName tcp=$tcpPort portIndex=$portIndex")
                startBridge(deviceName, portIndex, tcpPort)

                return START_STICKY
            }

            ACTION_STOP -> {
                if (isRunning) {
                    stopBridge()
                    releaseWakeLock()
                    stopForeground(true)
                }
                stopSelf()
                return START_NOT_STICKY
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        emitLog("INFO", "Service destroyed.")
        stopBridge()
        releaseWakeLock()
        super.onDestroy()
    }

    private fun startBridge(deviceName: String, portIndex: Int, tcpPort: Int) {
        stopBridge()

        val usb = getSystemService(USB_SERVICE) as UsbManager
        val dev = usb.deviceList.values.firstOrNull { it.deviceName == deviceName }
        if (dev == null) {
            updateNotif("USB device not found")
            emitLog("ERR", "USB device not found: $deviceName")
            return
        }
        if (!usb.hasPermission(dev)) {
            updateNotif("No USB permission (open app once)")
            emitLog("ERR", "No USB permission for ${dev.deviceName}")
            return
        }

        val driver = UsbSerialProber.getDefaultProber().probeDevice(dev)
        if (driver == null || driver.ports.isEmpty()) {
            updateNotif("No serial driver/ports")
            emitLog("ERR", "No serial driver/ports for ${dev.deviceName}")
            return
        }
        if (portIndex !in driver.ports.indices) {
            updateNotif("PortIndex out of range")
            emitLog("ERR", "PortIndex out of range: $portIndex (0..${driver.ports.size - 1})")
            return
        }

        val port: UsbSerialPort = driver.ports[portIndex]

        bridge = CatTcpBridge(
            usb = usb,
            device = dev,
            port = port,
            tcpPort = tcpPort,
            logInfo = {
                updateNotif("Running TCP:$tcpPort (port $portIndex)")
                emitLog("INFO", it)
            },
            logTx = { emitLog("TX", it) },
            logRx = { emitLog("RX", it) },
            logErr = {
                updateNotif("ERR: ${it.take(50)}")
                emitLog("ERR", it)
            },
        )

        try {
            bridge!!.start()
            isRunning = true
            updateNotif("Running TCP:$tcpPort (port $portIndex)")
            emitLog("INFO", "Bridge started. Listening TCP:$tcpPort")
        } catch (e: Exception) {
            updateNotif("Start failed: ${e.message}")
            emitLog("ERR", "Bridge start failed: ${e.message}")
            stopBridge()
        }
    }

    private fun stopBridge() {
        try { bridge?.stop() } catch (_: Exception) {}
        bridge = null
        isRunning = false
    }

    // ---------- Notification ----------
    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            val ch = NotificationChannel(
                CHANNEL_ID,
                "CAT Tunnel",
                NotificationManager.IMPORTANCE_LOW
            )
            nm.createNotificationChannel(ch)
        }
    }

    private fun buildNotif(text: String): Notification {
        return if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setContentTitle("CAT Tunnel")
                .setContentText(text)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setContentTitle("CAT Tunnel")
                .setContentText(text)
                .setOngoing(true)
                .build()
        }
    }

    private fun startAsForeground(text: String) {
        startForeground(NOTIF_ID, buildNotif(text))
    }

    private fun updateNotif(text: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotif(text))
    }

    // ---------- WakeLock ----------
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CatTunnel::Bridge").apply {
            setReferenceCounted(false)
            acquire()
        }
        emitLog("INFO", "WakeLock acquired.")
    }

    private fun releaseWakeLock() {
        try { wakeLock?.release() } catch (_: Exception) {}
        wakeLock = null
        emitLog("INFO", "WakeLock released.")
    }
}