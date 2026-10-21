package com.tablettouch.client

import android.content.Context
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Хранилище настроек подключения + поиск сервера в Wi-Fi через UDP-широковещание.
 * Протокол поиска совпадает с ответом TabletTouchServer (main.py / discovery).
 */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("tt_client", Context.MODE_PRIVATE)

    var modeUsb: Boolean
        get() = sp.getBoolean("mode_usb", false)
        set(v) = sp.edit().putBoolean("mode_usb", v).apply()

    var host: String
        get() = sp.getString("host", "") ?: ""
        set(v) = sp.edit().putString("host", v).apply()

    var port: Int
        get() = sp.getInt("port", DEFAULT_PORT)
        set(v) = sp.edit().putInt("port", v).apply()

    var token: String
        get() = sp.getString("token", "") ?: ""
        set(v) = sp.edit().putString("token", v).apply()

    var autoReconnect: Boolean
        get() = sp.getBoolean("auto_reconnect", true)
        set(v) = sp.edit().putBoolean("auto_reconnect", v).apply()

    /** «ws://host:port» для выбранного канала: USB — всегда 127.0.0.1 (adb forward). */
    fun wsUrl(): String =
        if (modeUsb) "ws://127.0.0.1:$port" else "ws://${host.trim()}:$port"

    companion object {
        const val DEFAULT_PORT = 8765
        const val DISCOVERY_PORT = 8766
    }
}

/** Однократное UDP-широковещание; возвращает найденные адреса вида "192.168.1.50". */
object LanScanner {

    fun scanOnce(timeoutMs: Int = 1500): List<String> {
        val found = LinkedHashSet<String>()
        try {
            DatagramSocket().use { sock ->
                sock.broadcast = true
                sock.soTimeout = timeoutMs
                val req = JSONObject()
                    .put("type", "discover")
                    .put("app", "tablettouch")
                    .toString()
                    .toByteArray()

                // широковещание во все подсети, к которым есть доступ
                for (addr in localBroadcastTargets()) {
                    try {
                        sock.send(DatagramPacket(req, req.size, addr, Prefs.DISCOVERY_PORT))
                    } catch (_: Exception) { }
                }

                val buf = ByteArray(1024)
                val deadline = System.currentTimeMillis() + timeoutMs
                while (System.currentTimeMillis() < deadline) {
                    val pkt = DatagramPacket(buf, buf.size)
                    try {
                        sock.receive(pkt)
                    } catch (_: Exception) {
                        break // таймаут — всё, что успели, приняли
                    }
                    val text = String(pkt.data, 0, pkt.length)
                    val obj = runCatching { JSONObject(text) }.getOrNull() ?: continue
                    if (obj.optString("app") == "tablettouch-server") {
                        found += pkt.address.hostAddress
                    }
                }
            }
        } catch (_: Exception) { }
        return found.toList()
    }

    private fun localBroadcastTargets(): List<Inet4Address> {
        val list = ArrayList<Inet4Address>()
        list.add(java.net.InetAddress.getByName("255.255.255.255"))
        try {
            for (nif in NetworkInterface.getNetworkInterfaces()) {
                for (ia in nif.interfaceAddresses) {
                    val b = ia.broadcast ?: continue
                    if (b is Inet4Address) list.add(b)
                }
            }
        } catch (_: Exception) { }
        return list
    }
}
