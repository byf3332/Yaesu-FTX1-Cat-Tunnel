package com.byf3332.cattunnel

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import com.hoho.android.usbserial.driver.UsbSerialPort
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class CatTcpBridge(
    private val usb: UsbManager,
    private val device: UsbDevice,
    private val port: UsbSerialPort,
    private val tcpPort: Int,
    private val logInfo: (String) -> Unit,
    private val logTx: (String) -> Unit,   // TCP->CAT
    private val logRx: (String) -> Unit,   // CAT->TCP
    private val logErr: (String) -> Unit,
) {
    private val running = AtomicBoolean(false)

    private var usbConn: UsbDeviceConnection? = null
    private var server: ServerSocket? = null
    private var client: Socket? = null

    private val ioPool = Executors.newCachedThreadPool()

    fun start() {
        if (!running.compareAndSet(false, true)) return

        // 1) 打开串口（把 openDevice 放到 start 里）
        val conn = usb.openDevice(device)
        if (conn == null) {
            running.set(false)
            throw IllegalStateException("openDevice() failed (permission?)")
        }
        usbConn = conn

        port.open(conn)
        try { port.dtr = true } catch (_: Exception) {}
        try { port.rts = true } catch (_: Exception) {}
        try { port.purgeHwBuffers(true, true) } catch (_: Exception) {}

        port.setParameters(
            38400, 8,
            UsbSerialPort.STOPBITS_1,
            UsbSerialPort.PARITY_NONE
        )

        logInfo("Serial opened @38400 (CAT).")

        // 2) 起 TCP Server
        val ss = ServerSocket()
        server = ss
        ss.reuseAddress = true
        ss.bind(InetSocketAddress("0.0.0.0", tcpPort))
        logInfo("TCP listening on 0.0.0.0:$tcpPort")

        ioPool.execute { acceptLoop(ss) }
    }

    private fun acceptLoop(ss: ServerSocket) {
        while (running.get()) {
            val s = try {
                ss.accept()
            } catch (e: Exception) {
                if (running.get()) logErr("accept error: ${e.message}")
                break
            }

            if (!running.get()) {
                try { s.close() } catch (_: Exception) {}
                break
            }

            // 单客户端：踢掉旧的
            closeClient()

            client = s
            try {
                s.tcpNoDelay = true
                s.keepAlive = true
                s.soTimeout = 1000          // 关键：避免 read 永久卡死
            } catch (_: Exception) {}

            val ins = try { s.getInputStream() } catch (e: Exception) {
                logErr("getInputStream failed: ${e.message}")
                closeClient()
                continue
            }
            val out = try { s.getOutputStream() } catch (e: Exception) {
                logErr("getOutputStream failed: ${e.message}")
                closeClient()
                continue
            }

            logInfo("Client connected: ${s.inetAddress.hostAddress}:${s.port}")

            // 把流作为参数传进去（避免 clientIn/clientOut race）
            ioPool.execute { tcpToSerialLoop(ins) }
            ioPool.execute { serialToTcpLoop(out) }
        }
    }

    private fun tcpToSerialLoop(ins: InputStream) {
        val buf = ByteArray(4096)
        logInfo("TCP->CAT thread started")

        while (running.get()) {
            val n = try {
                ins.read(buf)
            } catch (e: SocketTimeoutException) {
                continue
            } catch (e: Exception) {
                if (running.get()) logErr("tcp->cat read error: ${e.message}")
                closeClient()
                return
            }

            if (n <= 0) {
                logInfo("Client disconnected (TCP EOF).")
                closeClient()
                return
            }

            logInfo("TCP RX n=$n")                 // 你要看的“TCP RX”就在这里
            try {
                port.write(buf, 0, n, 2000)
                logTx("TCP→CAT ${preview(buf, n)}")
            } catch (e: Exception) {
                if (running.get()) logErr("tcp->cat serial write error: ${e.message}")
                closeClient()
                return
            }
        }
    }

    private fun serialToTcpLoop(out: OutputStream) {
        val buf = ByteArray(4096)
        logInfo("CAT->TCP thread started")

        while (running.get()) {
            val n = try {
                port.read(buf, 200)
            } catch (e: Exception) {
                if (running.get()) logErr("cat->tcp serial read error: ${e.message}")
                closeClient()
                return
            }

            if (n <= 0) continue

            try {
                out.write(buf, 0, n)
                out.flush()
                logRx("CAT→TCP ${preview(buf, n)}")
            } catch (e: Exception) {
                if (running.get()) logErr("cat->tcp tcp write error: ${e.message}")
                closeClient()
                return
            }
        }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        logInfo("Stopping bridge...")

        try { closeClient() } catch (_: Exception) {}
        try { server?.close() } catch (_: Exception) {}
        server = null

        try { port.close() } catch (_: Exception) {}
        try { usbConn?.close() } catch (_: Exception) {}
        usbConn = null

        ioPool.shutdownNow()
        logInfo("Bridge stopped.")
    }

    private fun closeClient() {
        try { client?.close() } catch (_: Exception) {}
        client = null
    }

    private fun preview(buf: ByteArray, n: Int): String {
        val take = minOf(n, 120)
        val s = String(buf, 0, take, Charsets.US_ASCII)
            .replace("\r", "\\r")
            .replace("\n", "\\n")
        return if (n > take) "$s…" else s
    }
}

// 兼容 offset/len 写法
private fun UsbSerialPort.write(buf: ByteArray, off: Int, len: Int, timeoutMs: Int) {
    if (len <= 0) return
    if (off == 0 && len == buf.size) {
        this.write(buf, timeoutMs)
    } else {
        this.write(buf.copyOfRange(off, off + len), timeoutMs)
    }
}