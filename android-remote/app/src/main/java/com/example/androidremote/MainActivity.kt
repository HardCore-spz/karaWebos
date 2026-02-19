package com.example.androidremote

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject

class MainActivity : AppCompatActivity(), WebSocketManager.Listener {

    companion object {
        const val EXTRA_VIDEO_ID = "extra_video_id"
        const val EXTRA_VIDEO_TITLE = "extra_video_title"
    }

    private lateinit var currentVideoText: TextView
    private lateinit var playButton: Button
    private lateinit var pauseButton: Button
    private lateinit var stopButton: Button

    private var currentVideoId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        currentVideoText = findViewById(R.id.currentVideoText)
        playButton = findViewById(R.id.playButton)
        pauseButton = findViewById(R.id.pauseButton)
        stopButton = findViewById(R.id.stopButton)

        WebSocketManager.init(applicationContext)
        WebSocketManager.reconnectSavedRoom()

        val videoIdFromIntent = intent.getStringExtra(EXTRA_VIDEO_ID)
        val titleFromIntent = intent.getStringExtra(EXTRA_VIDEO_TITLE)

        currentVideoId = videoIdFromIntent ?: WebSocketManager.getLastVideoId()

        if (!titleFromIntent.isNullOrBlank()) {
            currentVideoText.text = "Đang chọn: $titleFromIntent"
        } else if (!currentVideoId.isNullOrBlank()) {
            currentVideoText.text = "Video ID: $currentVideoId"
        }

        playButton.setOnClickListener {
            val videoId = currentVideoId
            if (videoId.isNullOrBlank()) {
                Toast.makeText(this, "Chưa có video để phát", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            WebSocketManager.sendPlay(videoId)
        }

        pauseButton.setOnClickListener {
            WebSocketManager.sendPause()
        }

        stopButton.setOnClickListener {
            WebSocketManager.sendStop()
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
    }

    override fun onDisconnected(reason: String) {
        Toast.makeText(this, "Mất kết nối, app sẽ tự reconnect", Toast.LENGTH_SHORT).show()
    }

    override fun onMessage(text: String) {
        val payload = try {
            JSONObject(text)
        } catch (_: Exception) {
            return
        }

        if (payload.optString("action") == "error" && payload.optString("message") == "target_not_connected") {
            Toast.makeText(this, "TV chưa online đúng room", Toast.LENGTH_SHORT).show()
        }
    }
}
