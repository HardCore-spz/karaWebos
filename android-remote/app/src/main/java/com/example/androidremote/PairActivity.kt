package com.pnd.karaoke

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject

class PairActivity : AppCompatActivity(), WebSocketManager.Listener {

    private lateinit var roomEditText: EditText
    private lateinit var connectButton: Button
    private lateinit var statusText: TextView
    private lateinit var settingsButton: ImageButton
    private var hasNavigated = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pair)

        roomEditText = findViewById(R.id.roomEditText)
        connectButton = findViewById(R.id.connectButton)
        statusText = findViewById(R.id.statusText)
        settingsButton = findViewById(R.id.settingsButton)

        WebSocketManager.init(applicationContext)

        settingsButton.setOnClickListener {
            showSettingsDialog()
        }

        WebSocketManager.getSavedRoomId()?.let { roomEditText.setText(it) }

        // Start discovering and check for updates
        AppUpdater.checkForUpdate(this, silent = true)

        // Auto-discover server on startup
        statusText.text = "Đang tìm server trên mạng..."
        WebSocketManager.discoverServer(
            onFound = { ip ->
                statusText.text = "Đã tìm thấy server: $ip"
                // Khi tìm thấy server mới check update local cho chắc
                AppUpdater.checkForUpdate(this, silent = true)
            },
            onTimeout = {
                val cached = WebSocketManager.getServerIP()
                if (cached != null) {
                    statusText.text = "Dùng server đã lưu: $cached"
                    AppUpdater.checkForUpdate(this, silent = true)
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

    private fun showSettingsDialog() {
        val options = arrayOf("Kiểm tra cập nhật", "Thông tin phiên bản")
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Cài đặt")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> AppUpdater.checkForUpdate(this, false)
                    1 -> {
                        val vName = try {
                            packageManager.getPackageInfo(packageName, 0).versionName
                        } catch (_: Exception) { "v1.6" }
                        Toast.makeText(this, "Phiên bản: $vName\nKaraoke Remote v1.6 (PND)", Toast.LENGTH_LONG).show()
                    }
                }
            }
            .show()
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
