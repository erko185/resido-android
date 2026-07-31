package sk.efabrica.resido.update

import android.app.Activity
import android.content.Intent
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import sk.efabrica.resido.BuildConfig
import sk.efabrica.resido.R
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Self-update against the generic update host, the Android counterpart of
 * electron-updater: check at start + every 4 hours, ask before downloading
 * and before installing (the app runs unattended on reception tablets, a
 * silent install could interrupt an order mid-shift), and network errors
 * only log - flaky hotel WiFi must never surface as an error dialog.
 *
 * The host serves latest.json:
 *   {"versionCode": 10200, "versionName": "1.2.0",
 *    "apkUrl": "https://.../resido-1.2.0.apk", "sha256": "..."}
 */
class UpdateManager(private val activity: Activity) {

    data class RemoteVersion(
        val versionCode: Int,
        val versionName: String,
        val apkUrl: String,
        val sha256: String?,
    )

    sealed class CheckResult {
        data class UpToDate(val versionName: String) : CheckResult()
        data class Available(val remote: RemoteVersion) : CheckResult()
        object Failed : CheckResult()
    }

    private var updatePromptShown = false
    private var checkScope: CoroutineScope? = null

    fun startPeriodicChecks(scope: CoroutineScope) {
        checkScope = scope
        scope.launch {
            while (isActive) {
                maybePromptForUpdate()
                delay(CHECK_INTERVAL_MS)
            }
        }
    }

    /** One-shot check used by the settings screen's status line. */
    suspend fun check(): CheckResult {
        val remote = fetchRemoteVersion() ?: return CheckResult.Failed

        return if (remote.versionCode > BuildConfig.VERSION_CODE) {
            CheckResult.Available(remote)
        } else {
            CheckResult.UpToDate(BuildConfig.VERSION_NAME)
        }
    }

    private suspend fun maybePromptForUpdate() {
        val result = check()

        if (result !is CheckResult.Available || updatePromptShown || activity.isFinishing) {
            return
        }

        updatePromptShown = true

        AlertDialog.Builder(activity)
            .setTitle(R.string.update_dialog_title)
            .setMessage(activity.getString(R.string.update_dialog_message, result.remote.versionName))
            .setPositiveButton(R.string.update_dialog_download) { _, _ ->
                checkScope?.launch {
                    downloadAndPromptInstall(result.remote)
                }
            }
            .setNegativeButton(R.string.update_dialog_later) { _, _ ->
                // Allow the next periodic check to offer the update again.
                updatePromptShown = false
            }
            .setOnCancelListener { updatePromptShown = false }
            .show()
    }

    suspend fun downloadAndPromptInstall(remote: RemoteVersion) {
        Toast.makeText(activity, R.string.update_downloading, Toast.LENGTH_SHORT).show()

        val apkFile = withContext(Dispatchers.IO) { downloadApk(remote) }

        if (apkFile == null) {
            updatePromptShown = false
            Toast.makeText(activity, R.string.update_download_failed, Toast.LENGTH_LONG).show()
            return
        }

        if (activity.isFinishing) {
            return
        }

        AlertDialog.Builder(activity)
            .setTitle(R.string.update_downloaded_title)
            .setMessage(activity.getString(R.string.update_downloaded_message, remote.versionName))
            .setPositiveButton(R.string.update_install_now) { _, _ -> installApk(apkFile) }
            .setNegativeButton(R.string.update_dialog_later) { _, _ -> updatePromptShown = false }
            .setOnCancelListener { updatePromptShown = false }
            .show()
    }

    private fun fetchRemoteVersionBlocking(): RemoteVersion? {
        val url = URL(BuildConfig.UPDATE_BASE_URL.trimEnd('/') + "/latest.json")
        val connection = url.openConnection() as HttpURLConnection

        return try {
            connection.connectTimeout = HTTP_TIMEOUT_MS
            connection.readTimeout = HTTP_TIMEOUT_MS
            connection.setRequestProperty("Cache-Control", "no-cache")

            val body = connection.inputStream.bufferedReader().readText()
            val json = JSONObject(body)

            RemoteVersion(
                versionCode = json.getInt("versionCode"),
                versionName = json.getString("versionName"),
                apkUrl = json.getString("apkUrl"),
                sha256 = json.optString("sha256").takeIf { it.isNotBlank() },
            )
        } catch (e: Exception) {
            Log.w(TAG, "Update check failed: ${e.message}")
            null
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun fetchRemoteVersion(): RemoteVersion? =
        withContext(Dispatchers.IO) { fetchRemoteVersionBlocking() }

    private fun downloadApk(remote: RemoteVersion): File? {
        val directory = File(activity.cacheDir, "updates").apply { mkdirs() }
        val target = File(directory, "resido-update.apk")

        return try {
            val connection = URL(remote.apkUrl).openConnection() as HttpURLConnection
            connection.connectTimeout = HTTP_TIMEOUT_MS
            connection.readTimeout = DOWNLOAD_READ_TIMEOUT_MS

            connection.inputStream.use { input ->
                target.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            connection.disconnect()

            if (remote.sha256 != null && !sha256Of(target).equals(remote.sha256, ignoreCase = true)) {
                Log.w(TAG, "Downloaded APK sha256 mismatch")
                target.delete()
                return null
            }

            target
        } catch (e: Exception) {
            Log.w(TAG, "Update download failed: ${e.message}")
            target.delete()
            null
        }
    }

    private fun installApk(file: File) {
        val uri = FileProvider.getUriForFile(
            activity,
            "${BuildConfig.APPLICATION_ID}.fileprovider",
            file,
        )

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        try {
            activity.startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Package installer launch failed: ${e.message}")
        }
    }

    private fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")

        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                digest.update(buffer, 0, read)
            }
        }

        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val TAG = "ResidoUpdate"
        const val CHECK_INTERVAL_MS = 4 * 60 * 60 * 1000L
        const val HTTP_TIMEOUT_MS = 10_000
        const val DOWNLOAD_READ_TIMEOUT_MS = 60_000
    }
}
