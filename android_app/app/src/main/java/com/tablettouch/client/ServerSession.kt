package com.tablettouch.client

import android.os.Handler
import android.os.Looper
import android.util.Log
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.nio.ByteBuffer

/**
 * WebSocket-клиент протокола TabletTouch (совместим с pc_server/main.py):
 *
 *  входящие бинарные: 'V'+flag+frame | 'T'+json | 'H'+hello — но сервер шлёт
 *      кадры как b"v"+pkt / b"V"+pkt, а управление как "T"+json;
 *  исходящие текстовые JSON: hello / orientation / touch / ping.
 */
interface SessionListener {
    fun onConnected()
    fun onWelcome(desktopW: Int, desktopH: Int, displayRotatable: Boolean)
    fun onDesktopSize(w: Int, h: Int)
    /** Сервер пересобрал поток повёрнутым: degrees = 0/90/180/270 (CCW). */
    fun onStreamRestart(degrees: Int)
    fun onVideoFrame(flag: Char, nal: ByteArray)
    fun onDisconnected(byServer: Boolean)
}

class ServerSession(
    private val url: String,
    private val token: String,
    private val listener: SessionListener,
) {
    private var ws: WebSocketClient? = null
    @Volatile var connected = false; private set
    var desktopW = 1920; private set
    var desktopH = 1080; private set

    fun connect() {
        if (ws != null) return
        val client = object : WebSocketClient(URI(url)) {
            override fun onOpen(handshake: ServerHandshake?) {
                connected = true
                Log.i(TAG, "open $url")
                // авторизация токеном только если он задан на сервере
                if (token.isNotEmpty()) send(JSONObject().put("auth", token).toString())
                listener.onConnected()
            }

            override fun onMessage(message: String?) {
                message?.let { handleJson(it) }
            }

            override fun onMessage(bytes: ByteBuffer?) {
                bytes ?: return
                val arr = ByteArray(bytes.remaining())
                bytes.get(arr)
                when (arr[0].toInt().toChar()) {
                    'V', 'v' -> listener.onVideoFrame(arr[0].toInt().toChar(),
                        arr.copyOfRange(1, arr.size))
                    'T' -> handleJson(String(arr, 1, arr.size - 1))
                    else -> Log.w(TAG, "unknown binary tag ${arr[0]}")
                }
            }

            override fun onClose(code: Int, reason: String?, remote: Boolean) {
                connected = false
                Log.i(TAG, "close $code $reason")
                listener.onDisconnected(remote)
            }

            override fun onError(ex: Exception?) {
                Log.w(TAG, "error: ${ex?.message}")
            }
        }
        client.connectionLostTimeout = 20
        ws = client
        try {
            client.connectBlocking(4, java.util.concurrent.TimeUnit.SECONDS)
        } catch (_: InterruptedException) { }
    }

    fun close() {
        try { ws?.closeBlocking() } catch (_: Exception) { }
        ws = null
        connected = false
    }

    // ---------------- исходящие команды ----------------

    fun sendHello(screenW: Int, screenH: Int, rotationDeg: Int) {
        send(json { put("type", "hello"); put("screenWidthPx", screenW)
            .put("screenHeightPx", screenH); put("rotationDegrees", rotationDeg) })
    }

    fun sendOrientation(deg: Int) =
        send(json { put("type", "orientation"); put("degrees", deg) })

    /** points: список [x, y, pointerId] в пикселях текущего экрана планшета. */
    fun sendTouch(action: String, pointerId: Int, x: Float, y: Float,
                  extra: List<FloatArray>? = null) {
        val pts = JSONArray()
        if (extra != null) {
            for (p in extra) pts.put(JSONArray().put(p[0]).put(p[1]).put(p[2]))
        } else {
            pts.put(JSONArray().put(x).put(y).put(pointerId.toFloat()))
        }
        send(json { put("type", "touch"); put("action", action)
            .put("pointerId", pointerId); put("points", pts) })
    }

    fun sendButton(button: String, x: Float, y: Float) =
        send(json { put("type", "touch"); put("action", "button")
            .put("button", button); put("x", x); put("y", y) })

    fun sendWheel(ticks: Int) =
        send(json { put("type", "wheel"); put("ticks", ticks) })

    fun sendPing(ts: Long) = send(json { put("type", "ping"); put("ts", ts) })

    private fun send(text: String) {
        val c = ws ?: return
        if (!c.isOpen) return
        try { c.send(text) } catch (e: Exception) { Log.w(TAG, "send: ${e.message}") }
    }

    private inline fun json(block: JSONObject.() -> Unit): String =
        JSONObject().apply(block).toString()

    // ---------------- входящие JSON ----------------

    private fun handleJson(raw: String) {
        val obj = runCatching { JSONObject(raw) }.getOrNull() ?: return
        when (obj.optString("type")) {
            "welcome" -> {
                desktopW = obj.optInt("desktopWidth", desktopW)
                desktopH = obj.optInt("desktopHeight", desktopH)
                listener.onWelcome(desktopW, desktopH, obj.optBoolean("displayRotatable"))
            }
            "desktop" -> {
                desktopW = obj.optInt("desktopWidth", desktopW)
                desktopH = obj.optInt("desktopHeight", desktopH)
                listener.onDesktopSize(desktopW, desktopH)
            }
            "restart" -> listener.onStreamRestart(obj.optInt("degrees", 0))
            "pong" -> { }
            else -> Log.d(TAG, "ctrl: $raw")
        }
    }

    companion object { private const val TAG = "TTSession" }
}
