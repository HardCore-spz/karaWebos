package com.example.androidremote

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

class KaraokeActivity : AppCompatActivity(), WebSocketManager.Listener {

    private lateinit var searchEditText: EditText
    private lateinit var tabLayout: TabLayout
    private lateinit var recyclerView: RecyclerView
    private lateinit var btnPause: ImageButton
    private lateinit var btnPlay: ImageButton
    private lateinit var btnSkip: ImageButton
    private lateinit var btnVolUp: ImageButton
    private lateinit var btnVolDown: ImageButton
    private lateinit var btnSettings: ImageButton
    private lateinit var btnSearch: ImageButton
    private lateinit var btnMic: ImageButton
    private lateinit var nowPlayingText: TextView

    companion object {
        private const val REQUEST_RECORD_AUDIO = 200
    }

    private val speechLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val spoken = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
            if (!spoken.isNullOrBlank()) {
                searchEditText.setText(spoken)
                searchEditText.setSelection(spoken.length)
                performSearch(spoken)
            }
        }
    }

    private val searchResults = mutableListOf<YouTubeVideoItem>()
    private val queue = mutableListOf<YouTubeVideoItem>()
    private var currentPlayingIndex = -1
    private var isPlaying = false
    private var currentTab = 0

    private val videoAdapter = VideoAdapter { item ->
        showSongActionDialog(item)
    }

    private val queueAdapter = QueueAdapter(
        onRemove = { position -> removeFromQueue(position) },
        onClick = { position -> playFromQueue(position) }
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_karaoke)

        searchEditText = findViewById(R.id.searchEditText)
        tabLayout = findViewById(R.id.tabLayout)
        recyclerView = findViewById(R.id.recyclerView)
        btnPause = findViewById(R.id.btnPause)
        btnPlay = findViewById(R.id.btnPlay)
        btnSkip = findViewById(R.id.btnSkip)
        btnVolUp = findViewById(R.id.btnVolUp)
        btnVolDown = findViewById(R.id.btnVolDown)
        btnSettings = findViewById(R.id.btnSettings)
        btnSearch = findViewById(R.id.btnSearch)
        btnMic = findViewById(R.id.btnMic)
        nowPlayingText = findViewById(R.id.nowPlayingText)

        nowPlayingText.isSelected = true

        WebSocketManager.init(applicationContext)
        WebSocketManager.reconnectSavedRoom()

        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = videoAdapter

        loadQueue()

        setupSearch()
        setupTabs()
        setupControls()
        updateTabCounts()

        // Restore now playing text
        if (currentPlayingIndex in queue.indices) {
            nowPlayingText.text = queue[currentPlayingIndex].snippet.title
        }

        // Check for app updates after 2 seconds
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            AppUpdater.checkForUpdate(this@KaraokeActivity)
        }, 2000)
    }

    private fun setupSearch() {
        searchEditText.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                performSearch(searchEditText.text.toString())
                true
            } else false
        }

        btnSearch.setOnClickListener {
            performSearch(searchEditText.text.toString())
        }

        btnMic.setOnClickListener {
            startVoiceRecognition()
        }
    }

    private fun startVoiceRecognition() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_RECORD_AUDIO
            )
            return
        }
        launchSpeechRecognizer()
    }

    private fun launchSpeechRecognizer() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "vi-VN")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Nói tên bài hát...")
        }

        // Kiểm tra xem hệ thống có bất kỳ công cụ nhận dạng giọng nói nào không
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            showNoVoiceDialog()
            return
        }

        try {
            speechLauncher.launch(intent)
        } catch (e: Exception) {
            // Nếu vẫn lỗi intent, thử hướng dẫn cài app Google
            showNoVoiceDialog()
        }
    }

    private fun showNoVoiceDialog() {
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Thiếu dịch vụ giọng nói")
            .setMessage("Thiết bị của bạn chưa cài đặt hoặc đã tắt ứng dụng 'Google' (không phải Chrome). \n\nBạn cần cài ứng dụng Google từ Play Store để sử dụng tính năng tìm kiếm bằng giọng nói.")
            .setPositiveButton("Đã hiểu", null)
            .show()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_RECORD_AUDIO && grantResults.isNotEmpty()
            && grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            launchSpeechRecognizer()
        } else {
            Toast.makeText(this, "Cần cấp quyền micro để tìm bằng giọng nói", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupTabs() {
        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                currentTab = tab?.position ?: 0
                switchTab()
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })
    }

    private fun setupControls() {
        // DỪNG - tạm dừng bài hát
        btnPause.setOnClickListener {
            if (isPlaying) {
                WebSocketManager.sendPause()
                isPlaying = false
            }
        }

        // HÁT TIẾP - tiếp tục hát
        btnPlay.setOnClickListener {
            if (!isPlaying) {
                if (currentPlayingIndex in 0 until queue.size) {
                    val videoId = queue[currentPlayingIndex].id.videoId ?: return@setOnClickListener
                    WebSocketManager.sendPlay(videoId)
                    isPlaying = true
                } else if (queue.isNotEmpty()) {
                    playFromQueue(0)
                }
            }
        }

        // QUA BÀI
        btnSkip.setOnClickListener { playNext() }

        // TO - tăng âm lượng
        btnVolUp.setOnClickListener { WebSocketManager.sendVolumeUp() }
        // NHỎ - giảm âm lượng
        btnVolDown.setOnClickListener { WebSocketManager.sendVolumeDown() }

        btnSettings.setOnClickListener { showSettingsDialog() }
    }

    private fun showSongActionDialog(item: YouTubeVideoItem) {
        if (queue.any { it.id.videoId == item.id.videoId }) {
            Toast.makeText(this, getString(R.string.already_in_queue), Toast.LENGTH_SHORT).show()
            return
        }

        val dialog = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert).create()
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 24, 0, 16)

            addView(TextView(this@KaraokeActivity).apply {
                text = "CHỌN"
                textSize = 22f
                setTextColor(resources.getColor(R.color.white, null))
                gravity = android.view.Gravity.CENTER
                setPadding(0, 40, 0, 40)
                setOnClickListener { addToQueueEnd(item); dialog.dismiss() }
            })

            addView(View(this@KaraokeActivity).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 1
                ).apply { setMargins(32, 0, 32, 0) }
                setBackgroundColor(0x33FFFFFF)
            })

            addView(TextView(this@KaraokeActivity).apply {
                text = "ƯU TIÊN"
                textSize = 22f
                setTextColor(resources.getColor(R.color.white, null))
                gravity = android.view.Gravity.CENTER
                setPadding(0, 40, 0, 40)
                setOnClickListener { addToQueuePriority(item); dialog.dismiss() }
            })

            addView(View(this@KaraokeActivity).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 1
                ).apply { setMargins(32, 0, 32, 0) }
                setBackgroundColor(0x33FFFFFF)
            })

            addView(TextView(this@KaraokeActivity).apply {
                text = "HỦY"
                textSize = 22f
                setTextColor(0xFFFF6666.toInt())
                gravity = android.view.Gravity.CENTER
                setPadding(0, 40, 0, 40)
                setOnClickListener { dialog.dismiss() }
            })
        }
        dialog.setView(layout)
        dialog.show()
    }

    private fun addToQueuePriority(item: YouTubeVideoItem) {
        // Insert right after the currently playing song
        val insertPos = if (currentPlayingIndex >= 0) currentPlayingIndex + 1 else 0
        queue.add(insertPos, item)

        updateTabCounts()
        saveQueue()
        Toast.makeText(this, getString(R.string.added_priority), Toast.LENGTH_SHORT).show()

        if (queue.size == 1 && currentPlayingIndex == -1) {
            playFromQueue(0)
        }

        if (currentTab == 1) {
            queueAdapter.submitList(queue.toList(), currentPlayingIndex)
        }
    }

    private fun addToQueueEnd(item: YouTubeVideoItem) {
        queue.add(item)
        updateTabCounts()
        saveQueue()
        Toast.makeText(this, getString(R.string.added_to_queue), Toast.LENGTH_SHORT).show()

        if (queue.size == 1 && currentPlayingIndex == -1) {
            playFromQueue(0)
        }

        if (currentTab == 1) {
            queueAdapter.submitList(queue.toList(), currentPlayingIndex)
        }
    }

    private fun showSettingsDialog() {
        val currentRoom = WebSocketManager.getSavedRoomId() ?: ""

        val input = EditText(this).apply {
            hint = getString(R.string.pair_hint)
            setText(currentRoom)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            maxLines = 1
            setPadding(48, 32, 48, 16)
            setTextColor(resources.getColor(R.color.text_primary, null))
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 0, 32, 0)
            addView(input)
        }

        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(getString(R.string.settings_pair_title))
            .setView(container)
            .setPositiveButton(getString(R.string.save)) { _, _ ->
                val newRoom = input.text.toString().trim()
                if (newRoom.length != 6) {
                    Toast.makeText(this, "Mã TV phải đủ 6 số", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                WebSocketManager.disconnect()
                WebSocketManager.connect(newRoom)
                Toast.makeText(this, "Đã đổi mã phòng: $newRoom", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun performSearch(query: String) {
        if (!ApiService.hasValidApiKey()) {
            Toast.makeText(this, "API Key chưa hợp lệ", Toast.LENGTH_SHORT).show()
            return
        }

        val normalizedQuery = if (query.isBlank()) "karaoke"
        else if (query.contains("karaoke", ignoreCase = true)) query.trim()
        else "${query.trim()} karaoke"

        lifecycleScope.launch {
            try {
                val response = ApiService.youtubeApi.searchVideos(query = normalizedQuery)
                searchResults.clear()
                searchResults.addAll(response.items)
                if (currentTab == 0) {
                    videoAdapter.submitList(searchResults.toList())
                }
                updateTabCounts()
            } catch (e: Exception) {
                Toast.makeText(this@KaraokeActivity, "Lỗi: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun removeFromQueue(position: Int) {
        if (position !in queue.indices) return

        queue.removeAt(position)

        if (position == currentPlayingIndex) {
            if (queue.isNotEmpty()) {
                val nextIndex = if (position < queue.size) position else 0
                playFromQueue(nextIndex)
            } else {
                currentPlayingIndex = -1
                isPlaying = false
                nowPlayingText.text = getString(R.string.no_song)
                WebSocketManager.sendStop()
            }
        } else if (position < currentPlayingIndex) {
            currentPlayingIndex--
        }

        if (currentTab == 1) {
            queueAdapter.submitList(queue.toList(), currentPlayingIndex)
        }
        updateTabCounts()
        saveQueue()
    }

    private fun playFromQueue(index: Int) {
        if (index !in queue.indices) return

        currentPlayingIndex = index
        val item = queue[index]
        val videoId = item.id.videoId ?: return

        WebSocketManager.sendPlay(videoId)
        isPlaying = true
        nowPlayingText.text = item.snippet.title

        if (currentTab == 1) {
            queueAdapter.submitList(queue.toList(), currentPlayingIndex)
        }
        saveQueue()
    }

    private fun playNext() {
        if (queue.isEmpty()) return

        // Remove the song that just finished
        if (currentPlayingIndex in queue.indices) {
            queue.removeAt(currentPlayingIndex)
            // After removal, the next song slides into currentPlayingIndex position
            if (queue.isNotEmpty()) {
                val nextIndex = if (currentPlayingIndex < queue.size) currentPlayingIndex else 0
                updateTabCounts()
                saveQueue()
                playFromQueue(nextIndex)
            } else {
                currentPlayingIndex = -1
                isPlaying = false
                nowPlayingText.text = getString(R.string.no_song)
                WebSocketManager.sendStop()
                updateTabCounts()
                saveQueue()
                if (currentTab == 1) {
                    queueAdapter.submitList(queue.toList(), currentPlayingIndex)
                }
            }
        } else {
            playFromQueue(0)
        }
    }

    private fun switchTab() {
        if (currentTab == 0) {
            recyclerView.adapter = videoAdapter
            videoAdapter.submitList(searchResults.toList())
        } else {
            recyclerView.adapter = queueAdapter
            queueAdapter.submitList(queue.toList(), currentPlayingIndex)
        }
    }

    private fun updateTabCounts() {
        tabLayout.getTabAt(0)?.text = String.format(getString(R.string.tab_all), searchResults.size)
        tabLayout.getTabAt(1)?.text = String.format(getString(R.string.tab_selected), queue.size)
    }

    private fun saveQueue() {
        val prefs = getSharedPreferences("karaoke_prefs", MODE_PRIVATE)
        val arr = JSONArray()
        for (item in queue) {
            val obj = JSONObject()
            obj.put("videoId", item.id.videoId ?: "")
            obj.put("title", item.snippet.title)
            obj.put("channelTitle", item.snippet.channelTitle)
            obj.put("thumbUrl", item.snippet.thumbnails.medium?.url ?: item.snippet.thumbnails.default?.url ?: "")
            arr.put(obj)
        }
        prefs.edit()
            .putString("saved_queue", arr.toString())
            .putInt("saved_playing_index", currentPlayingIndex)
            .apply()
    }

    private fun loadQueue() {
        val prefs = getSharedPreferences("karaoke_prefs", MODE_PRIVATE)
        val json = prefs.getString("saved_queue", null) ?: return
        try {
            val arr = JSONArray(json)
            queue.clear()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val item = YouTubeVideoItem(
                    id = VideoIdData(videoId = obj.optString("videoId")),
                    snippet = VideoSnippet(
                        title = obj.optString("title"),
                        channelTitle = obj.optString("channelTitle"),
                        thumbnails = ThumbnailCollection(
                            medium = ThumbnailData(url = obj.optString("thumbUrl")),
                            default = ThumbnailData(url = obj.optString("thumbUrl"))
                        )
                    )
                )
                queue.add(item)
            }
            currentPlayingIndex = prefs.getInt("saved_playing_index", -1)
            if (currentPlayingIndex >= queue.size) currentPlayingIndex = -1
        } catch (_: Exception) { }
    }

    override fun onStart() {
        super.onStart()
        WebSocketManager.addListener(this)
    }

    override fun onStop() {
        WebSocketManager.removeListener(this)
        super.onStop()
    }

    override fun onConnected() {}

    override fun onDisconnected(reason: String) {}

    override fun onMessage(text: String) {
        val payload = try { JSONObject(text) } catch (_: Exception) { return }
        if (payload.optString("action") == "ended") {
            playNext()
        }
    }
}
