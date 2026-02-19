package com.example.androidremote

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

object AppUpdater {

    private val handler = Handler(Looper.getMainLooper())

    data class VersionInfo(
        val versionCode: Int,
        val versionName: String,
        val changelog: String
    )

    private const val GITHUB_VERSION_URL = "https://raw.githubusercontent.com/HardCore-spz/karaWebos/main/karaoke-ws-server/update/version.json"
    private const val GITHUB_APK_URL = "https://raw.githubusercontent.com/HardCore-spz/karaWebos/main/karaoke-ws-server/update/app.apk"

    /** Check for update. Priority: Local (faster for testing), then GitHub. */
    fun checkForUpdate(context: Context, silent: Boolean = true) {
        Thread {
            // Thử check local trước (nhanh hơn khi đang dev)
            val serverIP = WebSocketManager.getServerIP()
            if (serverIP != null) {
                if (checkLocalUpdateSync(context, silent, serverIP)) return@Thread
            }

            // Nếu local không có hoặc server offline, check GitHub
            checkGitHubUpdate(context, silent)
        }.start()
    }

    private fun checkLocalUpdateSync(context: Context, silent: Boolean, serverIP: String): Boolean {
        try {
            val url = URL("http://$serverIP:3000/version")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 1500
            if (conn.responseCode == 200) {
                val json = conn.inputStream.bufferedReader().readText()
                val obj = JSONObject(json)
                val remote = VersionInfo(
                    versionCode = obj.getInt("versionCode"),
                    versionName = obj.getString("versionName"),
                    changelog = obj.optString("changelog", "")
                )
                if (remote.versionCode > getCurrentVersionCode(context)) {
                    handler.post { showUpdateDialog(context, remote, "http://$serverIP:3000/apk") }
                    return true
                } else if (!silent) {
                    handler.post { Toast.makeText(context, "Bản local đã mới nhất (v${remote.versionName})", Toast.LENGTH_SHORT).show() }
                    return true // Ngừng lại vì đã tìm thấy bản local mới nhất
                }
            }
        } catch (_: Exception) {}
        return false
    }

    private fun checkGitHubUpdate(context: Context, silent: Boolean) {
        try {
            val url = URL(GITHUB_VERSION_URL)
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 3000
            if (conn.responseCode != 200) {
                if (!silent) handler.post { Toast.makeText(context, "Không thể check GitHub", Toast.LENGTH_SHORT).show() }
                return
            }

            val json = conn.inputStream.bufferedReader().readText()
            val obj = JSONObject(json)
            val remote = VersionInfo(
                versionCode = obj.getInt("versionCode"),
                versionName = obj.getString("versionName"),
                changelog = obj.optString("changelog", "")
            )

            if (remote.versionCode > getCurrentVersionCode(context)) {
                handler.post { showUpdateDialog(context, remote, GITHUB_APK_URL) }
            } else if (!silent) {
                handler.post { Toast.makeText(context, "Đã là phiên bản mới nhất trên GitHub", Toast.LENGTH_SHORT).show() }
            }
        } catch (_: Exception) {
            if (!silent) handler.post { Toast.makeText(context, "Không thể check update", Toast.LENGTH_SHORT).show() }
        }
    }

    private fun getCurrentVersionCode(context: Context): Int {
        return try {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            @Suppress("DEPRECATION")
            info.versionCode
        } catch (_: Exception) {
            0
        }
    }

    private fun showUpdateDialog(context: Context, version: VersionInfo, downloadUrl: String) {
        AlertDialog.Builder(context, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Cập nhật v${version.versionName}")
            .setMessage("Có phiên bản mới!\n\n${version.changelog}")
            .setPositiveButton("Cập nhật") { _, _ ->
                downloadAndInstall(context, downloadUrl)
            }
            .setNegativeButton("Để sau", null)
            .show()
    }

    private fun downloadAndInstall(context: Context, downloadUrl: String) {
        // Show progress dialog
        val progressBar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            setPadding(48, 24, 48, 0)
        }
        val percentText = TextView(context).apply {
            text = "0%"
            textSize = 14f
            setPadding(48, 8, 48, 24)
            setTextColor(0xFFFFFFFF.toInt())
        }
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(progressBar)
            addView(percentText)
        }

        val dialog = AlertDialog.Builder(context, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Đang tải cập nhật...")
            .setView(layout)
            .setCancelable(false)
            .show()

        Thread {
            try {
                val url = URL(downloadUrl)
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 10000
                conn.readTimeout = 30000

                val totalSize = conn.contentLength
                val updateDir = File(context.getExternalFilesDir(null), "updates")
                updateDir.mkdirs()
                val apkFile = File(updateDir, "KaraokeRemote.apk")

                val input = conn.inputStream
                val output = FileOutputStream(apkFile)
                val buffer = ByteArray(8192)
                var downloaded = 0L

                while (true) {
                    val count = input.read(buffer)
                    if (count == -1) break
                    output.write(buffer, 0, count)
                    downloaded += count

                    if (totalSize > 0) {
                        val percent = (downloaded * 100 / totalSize).toInt()
                        handler.post {
                            progressBar.progress = percent
                            percentText.text = "$percent%"
                        }
                    }
                }

                output.flush()
                output.close()
                input.close()
                conn.disconnect()

                handler.post {
                    dialog.dismiss()
                    installApk(context, apkFile)
                }
            } catch (e: Exception) {
                handler.post {
                    dialog.dismiss()
                    Toast.makeText(context, "Lỗi tải: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun installApk(context: Context, apkFile: File) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile
        )

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
        }

        context.startActivity(intent)
    }
}
