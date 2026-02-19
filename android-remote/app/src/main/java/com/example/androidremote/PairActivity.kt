package com.example.androidremote

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject

class PairActivity : AppCompatActivity(), WebSocketManager.Listener {

    private lateinit var roomEditText: EditText
    private lateinit var connectButton: Button
    private lateinit var statusText: TextView
    private var hasNavigated = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pair)

        roomEditText = findViewById(R.id.roomEditText)
        connectButton = findViewById(R.id.connectButton)
        statusText = findViewById(R.id.statusText)

        WebSocketManager.init(applicationContext)

        WebSocketManager.getSavedRoomId()?.let { roomEditText.setText(it) }

        // Auto-discover server on startup
        statusText.text = "Đang tìm server trên mạng..."
        WebSocketManager.discoverServer(
            onFound = { ip ->
                statusText.text = "Đã tìm thấy server: $ip"
            },
            onTimeout = {
                val cached = WebSocketManager.getServerIP()
                if (cached != null) {
                    statusText.text = "Dùng server đã lưu: $cached"
                } else {
                    statusText.text = "Không tìm thấy server. Kiểm tra WiFi."
                }
            }
        )

        connectButton.setOnClickListener {
            val room = roomEditText.text.toString().trim()
            if (room.length != 6) {
                Toast.makeText(this, "Mã TV phải đủ 6 số", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (WebSocketManager.getServerIP() == null) {
                statusText.text = "Đang tìm server..."
                WebSocketManager.discoverServer(
                    onFound = { ip ->
                        statusText.text = "Server: $ip — Đang kết nối..."
                        WebSocketManager.connect(room)
                    },
                    onTimeout = {
                        statusText.text = "Không tìm thấy server. Kiểm tra WiFi."
                    }
                )
                return@setOnClickListener
            }

            statusText.text = "Đang kết nối..."
            WebSocketManager.connect(room)
        }
    }

    override fun onStart() {
        super.onStart()
        WebSocketManager.addListener(this)
    }

    override fun onStop() {
        WebSocketManager.removeListener(this)
        super.onStop()
    }

    override fun onConnected() {
        statusText.text = "Đã kết nối socket, đang join room..."
    }

    override fun onDisconnected(reason: String) {
        statusText.text = "Mất kết nối: $reason"
    }

    override fun onMessage(text: String) {
        val payload = try {
            JSONObject(text)
        } catch (_: Exception) {
            return
        }

        when (payload.optString("action")) {
            "joined" -> {
                statusText.text = "Join room thành công"
                if (!hasNavigated) {
                    hasNavigated = true
                    startActivity(Intent(this, KaraokeActivity::class.java))
                    finish()
                }
            }
            "error" -> {
                statusText.text = "Lỗi: ${payload.optString("message")}" 
            }
        }
    }
}
