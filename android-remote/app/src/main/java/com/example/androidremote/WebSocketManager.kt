package com.pnd.karaoke

import android.content.Context
import android.os.Handler
import android.os.Looper
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.ArrayDeque
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.TimeUnit

object WebSocketManager {
    private const val PREFS = "karaoke_prefs"
    private const val KEY_ROOM_ID = "room_id"
    private const val KEY_LAST_VIDEO_ID = "last_video_id"
    private const val KEY_SERVER_IP = "server_ip"

    private const val UDP_PORT = 3001
    private const val SERVER_PORT = 3000

    private var serverIP: String? = null

    private fun getWsUrl(): String {
        val ip = serverIP ?: "127.0.0.1"
        return "ws://$ip:$SERVER_PORT"
    }

    interface Listener {
        fun onConnected()
        fun onDisconnected(reason: String)
        fun onMessage(text: String)
    }

    private val listeners = CopyOnWriteArraySet<Listener>()
    private val handler = Handler(Looper.getMainLooper())

    private val client: OkHttpClient = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private var appContext: Context? = null
    private var webSocket: WebSocket? = null
    private var roomId: String? = null
    private var reconnectAttempt = 0
    private var manualClose = false
    private var isConnected = false
    private var isConnecting = false
    private var isJoinedRoom = false
    private var activeSocketToken = 0
    private var reconnectRunnable: Runnable? = null
    private val pendingCommands = ArrayDeque<String>()

    fun init(context: Context) {
        appContext = context.applicationContext
        // Load cached server IP
        val prefs = appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        serverIP = prefs?.getString(KEY_SERVER_IP, null)
    }

    /** Discover server on LAN via UDP broadcast. Calls back on main thread. */
    fun discoverServer(onFound: (String) -> Unit, onTimeout: () -> Unit) {
        Thread {
            var found: String? = null
            var sock: DatagramSocket? = null
            try {
                sock = DatagramSocket()
                sock.broadcast = true
                sock.soTimeout = 3000 // 3 second timeout

                val msg = "KARAOKE_DISCOVER".toByteArray()
                val broadcastAddr = InetAddress.getByName("255.255.255.255")
                val packet = DatagramPacket(msg, msg.size, broadcastAddr, UDP_PORT)
                sock.send(packet)

                val buf = ByteArray(256)
                val recvPacket = DatagramPacket(buf, buf.size)
                sock.receive(recvPacket)

                val response = String(recvPacket.data, 0, recvPacket.length).trim()
                // Format: KARAOKE_SERVER:IP:PORT
                if (response.startsWith("KARAOKE_SERVER:")) {
                    val parts = response.split(":")
                    if (parts.size >= 3) {
                        found = parts[1]
                    }
                }
            } catch (_: Exception) {
                // timeout or error
            } finally {
                sock?.close()
            }

            if (found != null) {
                serverIP = found
                persistServerIP(found)
                handler.post { onFound(found) }
            } else {
                handler.post { onTimeout() }
            }
        }.start()
    }

    fun getServerIP(): String? = serverIP

    fun addListener(listener: Listener) {
        listeners.add(listener)
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    fun connect(roomId: String) {
        val isSameRoom = this.roomId == roomId
        this.roomId = roomId
        persistRoomId(roomId)
        manualClose = false
        isJoinedRoom = false

        if (isSameRoom && (isConnected || isConnecting)) {
            return
        }

        reconnectAttempt = 0
        openSocket()
    }

    fun reconnectSavedRoom() {
        val savedRoom = getSavedRoomId() ?: return
        if (roomId == savedRoom && (isConnected || isConnecting)) {
            return
        }
        connect(savedRoom)
    }

    fun disconnect() {
        manualClose = true
        clearReconnectSchedule()
        isConnected = false
        isConnecting = false
        isJoinedRoom = false
        pendingCommands.clear()
        activeSocketToken += 1
        webSocket?.close(1000, "manual close")
        webSocket = null
    }

    fun sendPlay(videoId: String) {
        val payload = JSONObject()
            .put("action", "play")
            .put("videoId", videoId)
            .toString()
        send(payload)
        persistLastVideoId(videoId)
    }

    fun sendPause() {
        val payload = JSONObject().put("action", "pause").toString()
        send(payload)
    }

    fun sendStop() {
        val payload = JSONObject().put("action", "stop").toString()
        send(payload)
    }

    fun sendVolumeUp() {
        val payload = JSONObject().put("action", "volume_up").toString()
        send(payload)
    }

    fun sendVolumeDown() {
        val payload = JSONObject().put("action", "volume_down").toString()
        send(payload)
    }

    fun getSavedRoomId(): String? {
        val prefs = appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE) ?: return null
        return prefs.getString(KEY_ROOM_ID, null)
    }

    fun getLastVideoId(): String? {
        val prefs = appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE) ?: return null
        return prefs.getString(KEY_LAST_VIDEO_ID, null)
    }

    private fun openSocket() {
        if (roomId.isNullOrBlank()) {
            return
        }

        clearReconnectSchedule()
        isConnecting = true
        isConnected = false
        isJoinedRoom = false

        val token = ++activeSocketToken
        webSocket?.cancel()
        val request = Request.Builder().url(getWsUrl()).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (token != activeSocketToken) return
                isConnecting = false
                isConnected = true
                reconnectAttempt = 0
                sendJoinRoom()
                notifyConnected()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (token != activeSocketToken) return
                handleIncoming(text)
                notifyMessage(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                if (token != activeSocketToken) return
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (token != activeSocketToken) return
                isConnecting = false
                isConnected = false
                isJoinedRoom = false
                notifyDisconnected("closed: $reason")
                scheduleReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (token != activeSocketToken) return
                isConnecting = false
                isConnected = false
                isJoinedRoom = false
                notifyDisconnected("failure: ${t.message ?: "unknown"}")
                scheduleReconnect()
            }
        })
    }

    private fun sendJoinRoom() {
        val joinRoom = roomId ?: return
        val payload = JSONObject()
            .put("action", "join")
            .put("room", joinRoom)
            .put("role", "controller")
            .toString()
        webSocket?.send(payload)
        persistRoomId(joinRoom)
    }

    private fun send(text: String) {
        if (isConnected && isJoinedRoom) {
            webSocket?.send(text)
            return
        }

        if (isControlCommand(text)) {
            pendingCommands.addLast(text)
            if (pendingCommands.size > 20) {
                pendingCommands.removeFirst()
            }
        }

        if (!isConnected && !isConnecting && !manualClose) {
            openSocket()
        }
    }

    private fun handleIncoming(text: String) {
        val payload = try {
            JSONObject(text)
        } catch (_: Exception) {
            return
        }

        when (payload.optString("action")) {
            "joined" -> {
                isJoinedRoom = true
                flushPendingCommands()
            }
            "error" -> {
                if (payload.optString("message") == "not_joined") {
                    isJoinedRoom = false
                    sendJoinRoom()
                }
            }
        }
    }

    private fun flushPendingCommands() {
        while (pendingCommands.isNotEmpty() && isConnected && isJoinedRoom) {
            val command = pendingCommands.removeFirst()
            webSocket?.send(command)
        }
    }

    private fun isControlCommand(text: String): Boolean {
        return try {
            val action = JSONObject(text).optString("action")
            action == "play" || action == "pause" || action == "stop" || action == "volume_up" || action == "volume_down"
        } catch (_: Exception) {
            false
        }
    }

    private fun scheduleReconnect() {
        if (manualClose) return
        if (isConnected || isConnecting) return

        val joinRoom = roomId ?: getSavedRoomId() ?: return
        roomId = joinRoom

        clearReconnectSchedule()
        reconnectAttempt += 1
        val delay = (1000L * reconnectAttempt).coerceAtMost(10000L)

        reconnectRunnable = Runnable {
            if (!manualClose) {
                openSocket()
            }
        }

        handler.postDelayed(reconnectRunnable!!, delay)
    }

    private fun clearReconnectSchedule() {
        reconnectRunnable?.let { handler.removeCallbacks(it) }
        reconnectRunnable = null
    }

    private fun notifyConnected() {
        handler.post {
            listeners.forEach { it.onConnected() }
        }
    }

    private fun notifyDisconnected(reason: String) {
        handler.post {
            listeners.forEach { it.onDisconnected(reason) }
        }
    }

    private fun notifyMessage(text: String) {
        handler.post {
            listeners.forEach { it.onMessage(text) }
        }
    }

    private fun persistRoomId(roomId: String) {
        val prefs = appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE) ?: return
        prefs.edit().putString(KEY_ROOM_ID, roomId).apply()
    }

    private fun persistLastVideoId(videoId: String) {
        val prefs = appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE) ?: return
        prefs.edit().putString(KEY_LAST_VIDEO_ID, videoId).apply()
    }

    private fun persistServerIP(ip: String) {
        val prefs = appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE) ?: return
        prefs.edit().putString(KEY_SERVER_IP, ip).apply()
    }
}
