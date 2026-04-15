package com.example.androidremote

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import org.json.JSONObject

class PairActivity : AppCompatActivity(), WebSocketManager.Listener {
    private companion object {
        const val PREFS = "karaoke_prefs"
        const val KEY_RECENT_ROOMS = "recent_rooms"
        const val MAX_RECENT_ROOMS = 3
    }

    private lateinit var roomEditText: EditText
    private lateinit var connectButton: Button
    private lateinit var scanQrButton: Button
    private lateinit var statusText: TextView
    private lateinit var settingsButton: ImageButton
    private lateinit var recentRoomsSection: LinearLayout
    private lateinit var recentRoomButtons: List<Button>

    private var hasNavigated = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private var roomValidationTask: Runnable? = null
    private var checkingRoom = false
    private val recentRooms = mutableListOf<String>()

    private val scanLauncher = registerForActivityResult(ScanContract()) { result ->
        val raw = result?.contents
        if (!raw.isNullOrBlank()) {
            handleQrPayload(raw)
        }
    }

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startQrScanner()
        } else {
            Toast.makeText(this, "Can cap quyen camera de quet QR", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pair)

        roomEditText = findViewById(R.id.roomEditText)
        connectButton = findViewById(R.id.connectButton)
        scanQrButton = findViewById(R.id.scanQrButton)
        statusText = findViewById(R.id.statusText)
        settingsButton = findViewById(R.id.settingsButton)
        recentRoomsSection = findViewById(R.id.recentRoomsSection)
        recentRoomButtons = listOf(
            findViewById(R.id.recentRoom1),
            findViewById(R.id.recentRoom2),
            findViewById(R.id.recentRoom3)
        )

        WebSocketManager.init(applicationContext)

        settingsButton.setOnClickListener {
            showSettingsDialog()
        }

        val savedRoom = WebSocketManager.getSavedRoomId()
        savedRoom?.let { roomEditText.setText(it) }
        recentRooms.clear()
        recentRooms.addAll(loadRecentRooms())
        renderRecentRooms()

        // Start discovering and check for updates
        AppUpdater.checkForUpdate(this, silent = true)

        statusText.text = "Chế độ online: Cloud deploy"

        if (!savedRoom.isNullOrBlank() && savedRoom.length == 6) {
            autoValidateSavedRoom(savedRoom)
        }

        connectButton.setOnClickListener {
            val room = roomEditText.text.toString().trim()
            if (room.length != 6) {
                Toast.makeText(this, "Mã TV phải đủ 6 số", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            connectToRoom(room, fromAutoCheck = false)
        }

        scanQrButton.setOnClickListener {
            ensureCameraAndScan()
        }
    }

    private fun ensureCameraAndScan() {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            startQrScanner()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startQrScanner() {
        val options = ScanOptions().apply {
            setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            setPrompt("Dua camera vao ma QR tren TV")
            setBeepEnabled(true)
            setOrientationLocked(true)
        }
        scanLauncher.launch(options)
    }

    private fun handleQrPayload(raw: String) {
        val room = extractRoomCode(raw)
        if (room == null) {
            Toast.makeText(this, "QR khong hop le", Toast.LENGTH_SHORT).show()
            return
        }

        roomEditText.setText(room)
        connectToRoom(room, fromAutoCheck = false)
    }

    private fun extractRoomCode(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.matches(Regex("^\\d{6}$"))) {
            return trimmed
        }

        val uri = runCatching { Uri.parse(trimmed) }.getOrNull()
        val roomFromQuery = uri?.getQueryParameter("room")
        if (!roomFromQuery.isNullOrBlank() && roomFromQuery.matches(Regex("^\\d{6}$"))) {
            return roomFromQuery
        }

        val match = Regex("(\\d{6})").find(trimmed)
        return match?.groupValues?.getOrNull(1)
    }

    private fun autoValidateSavedRoom(room: String) {
        statusText.text = "Dang kiem tra ma phong cu $room ..."
        connectToRoom(room, fromAutoCheck = true)
    }

    private fun connectToRoom(room: String, fromAutoCheck: Boolean) {
        checkingRoom = true
        statusText.text = if (fromAutoCheck) {
            "Dang xac thuc phong $room tren Cloud..."
        } else {
            "Dang ket noi Cloud deploy..."
        }

        cancelRoomValidationTask()
        WebSocketManager.connectCloud(room)

        roomValidationTask = Runnable {
            if (hasNavigated) return@Runnable
            checkingRoom = false
            WebSocketManager.disconnect()
            statusText.text = if (fromAutoCheck) {
                "Phong cu dang cham phan hoi. Bam Ket noi de thu lai."
            } else {
                "Ket noi cham hoac gian doan. Thu lai trong giay lat."
            }
        }
        mainHandler.postDelayed(roomValidationTask!!, 12000)
    }

    private fun cancelRoomValidationTask() {
        roomValidationTask?.let { mainHandler.removeCallbacks(it) }
        roomValidationTask = null
    }

    private fun loadRecentRooms(): List<String> {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val raw = prefs.getString(KEY_RECENT_ROOMS, "") ?: ""
        if (raw.isBlank()) return emptyList()
        return raw.split(",")
            .map { it.trim() }
            .filter { it.matches(Regex("^\\d{6}$")) }
            .distinct()
            .take(MAX_RECENT_ROOMS)
    }

    private fun saveRecentRooms() {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        prefs.edit().putString(KEY_RECENT_ROOMS, recentRooms.take(MAX_RECENT_ROOMS).joinToString(",")).apply()
    }

    private fun addRecentRoom(room: String) {
        recentRooms.remove(room)
        recentRooms.add(0, room)
        while (recentRooms.size > MAX_RECENT_ROOMS) {
            recentRooms.removeAt(recentRooms.lastIndex)
        }
        saveRecentRooms()
        renderRecentRooms()
    }

    private fun removeRecentRoom(room: String) {
        if (recentRooms.remove(room)) {
            saveRecentRooms()
            renderRecentRooms()
        }
        clearRoomQueueCache(room)
    }

    private fun clearRoomQueueCache(room: String) {
        if (!room.matches(Regex("^\\d{6}$"))) return
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        prefs.edit()
            .remove("saved_queue_$room")
            .remove("saved_playing_index_$room")
            .apply()
    }

    private fun renderRecentRooms() {
        if (recentRooms.isEmpty()) {
            recentRoomsSection.visibility = View.GONE
            for (button in recentRoomButtons) {
                button.visibility = View.GONE
            }
            return
        }

        recentRoomsSection.visibility = View.VISIBLE
        for (i in recentRoomButtons.indices) {
            val button = recentRoomButtons[i]
            val room = recentRooms.getOrNull(i)
            if (room != null) {
                button.visibility = View.VISIBLE
                button.text = room
                button.setOnClickListener {
                    roomEditText.setText(room)
                    connectToRoom(room, fromAutoCheck = false)
                }
            } else {
                button.visibility = View.GONE
            }
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
                        Toast.makeText(this, "Phiên bản: $vName\nKaraoke Player (PND)", Toast.LENGTH_LONG).show()
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
        cancelRoomValidationTask()
        WebSocketManager.removeListener(this)
        super.onStop()
    }

    override fun onConnected() {
        statusText.text = "Da ket noi ${WebSocketManager.getConnectionTargetLabel()}, dang xac nhan phong..."
    }

    override fun onDisconnected(reason: String) {
        if (!hasNavigated && !checkingRoom) {
            statusText.text = "Mat ket noi: $reason"
        }
    }

    override fun onMessage(text: String) {
        val payload = try {
            JSONObject(text)
        } catch (_: Exception) {
            return
        }

        when (payload.optString("action")) {
            "joined" -> {
                checkingRoom = false
                cancelRoomValidationTask()
                statusText.text = "Join room thanh cong"
                val room = roomEditText.text.toString().trim()
                if (room.matches(Regex("^\\d{6}$"))) {
                    addRecentRoom(room)
                }
                if (!hasNavigated) {
                    hasNavigated = true
                    startActivity(Intent(this, KaraokeActivity::class.java))
                    finish()
                }
            }
            "error" -> {
                checkingRoom = false
                cancelRoomValidationTask()
                val message = payload.optString("message")
                statusText.text = "Loi: $message"

                val normalized = message.trim().lowercase()
                val isExplicitInvalidRoom = normalized == "room_not_found" ||
                    normalized == "invalid_room" ||
                    normalized == "room_expired" ||
                    normalized == "room_deleted"

                if (isExplicitInvalidRoom) {
                    val room = roomEditText.text.toString().trim()
                    removeRecentRoom(room)
                    WebSocketManager.clearSavedRoom()
                    statusText.text = "Phong nay da het han. Vui long lay ma moi tren TV."
                }
            }
        }
    }
}
