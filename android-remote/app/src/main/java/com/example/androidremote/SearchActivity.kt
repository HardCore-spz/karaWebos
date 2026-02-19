package com.example.androidremote

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch
import org.json.JSONObject

class SearchActivity : AppCompatActivity(), WebSocketManager.Listener {

    private lateinit var searchEditText: EditText
    private lateinit var searchButton: Button
    private lateinit var statusText: TextView
    private lateinit var resultsRecycler: RecyclerView

    private val adapter = VideoAdapter { item ->
        val videoId = item.id.videoId
        if (videoId.isNullOrBlank()) {
            Toast.makeText(this, "Video ID không hợp lệ", Toast.LENGTH_SHORT).show()
            return@VideoAdapter
        }

        WebSocketManager.sendPlay(videoId)

        val intent = Intent(this, MainActivity::class.java)
        intent.putExtra(MainActivity.EXTRA_VIDEO_ID, videoId)
        intent.putExtra(MainActivity.EXTRA_VIDEO_TITLE, item.snippet.title)
        startActivity(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_search)

        searchEditText = findViewById(R.id.searchEditText)
        searchButton = findViewById(R.id.searchButton)
        statusText = findViewById(R.id.searchStatusText)
        resultsRecycler = findViewById(R.id.resultsRecycler)

        WebSocketManager.init(applicationContext)
        WebSocketManager.reconnectSavedRoom()

        resultsRecycler.layoutManager = LinearLayoutManager(this)
        resultsRecycler.adapter = adapter

        searchButton.setOnClickListener {
            val query = searchEditText.text.toString().trim()
            searchVideos(query)
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

    private fun searchVideos(query: String) {
        if (!ApiService.hasValidApiKey()) {
            Toast.makeText(this, "Hãy cập nhật YouTube API Key trong ApiService.kt", Toast.LENGTH_LONG).show()
            return
        }

        val normalizedQuery = buildKaraokeQuery(query)
        statusText.text = "Đang tìm: $normalizedQuery"

        lifecycleScope.launch {
            try {
                val response = ApiService.youtubeApi.searchVideos(query = normalizedQuery)
                adapter.submitList(response.items)
                statusText.text = "Tìm thấy ${response.items.size} kết quả"
            } catch (error: Exception) {
                statusText.text = "Lỗi tìm kiếm: ${error.message}"
            }
        }
    }

    private fun buildKaraokeQuery(query: String): String {
        val trimmed = query.trim()
        if (trimmed.isBlank()) {
            return "karaoke"
        }

        return if (trimmed.contains("karaoke", ignoreCase = true)) {
            trimmed
        } else {
            "$trimmed karaoke"
        }
    }

    override fun onConnected() {
        statusText.text = "Đã kết nối TV"
    }

    override fun onDisconnected(reason: String) {
        statusText.text = "Mất kết nối, đang reconnect..."
    }

    override fun onMessage(text: String) {
        val payload = try {
            JSONObject(text)
        } catch (_: Exception) {
            return
        }

        if (payload.optString("action") == "error") {
            val message = payload.optString("message")
            if (message == "target_not_connected") {
                statusText.text = "TV chưa online đúng room, kiểm tra mã pairing"
            }
        }
    }
}
