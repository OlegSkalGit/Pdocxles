package com.pdocxles.app.update

import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

object AppUpdateManager {

    private const val REPO_OWNER = "OlegSkalGit"
    private const val REPO_NAME = "Pdocxles"
    private const val API_URL = "https://api.github.com/repos/$REPO_OWNER/$REPO_NAME/releases?per_page=50"
    private const val CHECK_THROTTLE_MS = 24 * 60 * 60 * 1000L // 24 hours (once a day)

    private const val PREFS_NAME = "pdocxles_update_prefs"
    private const val KEY_LAST_CHECK_MS = "key_last_update_check_ms"

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    fun extractVersionNumbers(str: String): List<Int> {
        val regex = Regex("""\d+""")
        return regex.findAll(str).mapNotNull { it.value.toIntOrNull() }.toList()
    }

    fun isVersionNewer(remote: List<Int>, local: List<Int>): Boolean {
        val minLen = minOf(remote.size, local.size)
        for (i in 0 until minLen) {
            if (remote[i] > local[i]) return true
            if (remote[i] < local[i]) return false
        }
        return remote.size > local.size
    }

    fun getLastUpdateCheckMs(context: Context): Long {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getLong(KEY_LAST_CHECK_MS, 0L)
    }

    fun setLastUpdateCheckMs(context: Context, timestamp: Long = System.currentTimeMillis()) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putLong(KEY_LAST_CHECK_MS, timestamp).apply()
    }

    fun getAppVersionName(context: Context): String {
        return try {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            pInfo.versionName ?: "1.0.0"
        } catch (e: Exception) {
            "1.0.0"
        }
    }

    /**
     * Checks for updates. By default (force = false), runs at most once every 24 hours.
     */
    fun checkAndDownloadUpdate(
        context: Context,
        force: Boolean = false,
        onResult: ((String) -> Unit)? = null
    ) {
        val lastCheckMs = getLastUpdateCheckMs(context)
        val now = System.currentTimeMillis()

        if (!force && lastCheckMs != 0L && (now - lastCheckMs < CHECK_THROTTLE_MS)) {
            val hoursLeft = (CHECK_THROTTLE_MS - (now - lastCheckMs)) / 3600000L
            val msg = "Update check skipped (24h throttle active, next check in ~${hoursLeft}h)."
            onResult?.let { mainHandler.post { it(msg) } }
            return
        }

        executor.execute {
            performUpdateCheck(context, force, onResult)
        }
    }

    private fun performUpdateCheck(
        context: Context,
        force: Boolean,
        onResult: ((String) -> Unit)?
    ) {
        val appContext = context.applicationContext
        setLastUpdateCheckMs(context)

        val installedVersionName = getAppVersionName(appContext)
        val localInstalledVer = extractVersionNumbers(installedVersionName)

        val releasesList = fetchReleasesFromUrl(API_URL)
        if (releasesList.isEmpty()) {
            val err = "Failed to fetch releases from GitHub."
            onResult?.let { mainHandler.post { it(err) } }
            return
        }

        var latestRemoteVer: List<Int> = emptyList()
        var latestRemoteName = ""
        var latestRemoteUrl = ""

        for (release in releasesList) {
            val assets = release.optJSONArray("assets") ?: continue
            for (j in 0 until assets.length()) {
                val asset = assets.getJSONObject(j)
                val assetName = asset.optString("name", "")
                val downloadUrl = asset.optString("browser_download_url", "")

                if (assetName.endsWith(".apk", ignoreCase = true) && downloadUrl.isNotEmpty()) {
                    val ver = extractVersionNumbers(assetName)
                    if (isVersionNewer(ver, latestRemoteVer)) {
                        latestRemoteVer = ver
                        latestRemoteName = assetName
                        latestRemoteUrl = downloadUrl
                    }
                }
            }
        }

        if (latestRemoteVer.isEmpty() || latestRemoteUrl.isEmpty()) {
            val msg = "No valid APK release assets found."
            onResult?.let { mainHandler.post { it(msg) } }
            return
        }

        if (isVersionNewer(latestRemoteVer, localInstalledVer)) {
            if (force) {
                startDownload(appContext, latestRemoteUrl, latestRemoteName, onResult)
            } else {
                promptUserForUpdate(context, installedVersionName, latestRemoteName, latestRemoteUrl, onResult)
            }
        } else {
            val upToDateMsg = "App is up to date (v$installedVersionName)"
            onResult?.let { mainHandler.post { it(upToDateMsg) } }
        }
    }

    private fun promptUserForUpdate(
        context: Context,
        installedVerStr: String,
        latestRemoteName: String,
        latestRemoteUrl: String,
        onResult: ((String) -> Unit)?
    ) {
        mainHandler.post {
            val title = "Доступне оновлення"
            val message = "Доступна нова версія Pdocxles\n(Поточна: $installedVerStr / Нова: $latestRemoteName).\n\nЗавантажити зараз?"

            val downloadAction = {
                executor.execute {
                    startDownload(context.applicationContext, latestRemoteUrl, latestRemoteName, onResult)
                }
            }

            val laterAction = {
                setLastUpdateCheckMs(context)
                val msg = "Оновлення відкладено."
                onResult?.let { mainHandler.post { it(msg) } }
            }

            val activity = findActivity(context)
            if (activity != null && !activity.isFinishing && !activity.isDestroyed) {
                AlertDialog.Builder(activity)
                    .setTitle(title)
                    .setMessage(message)
                    .setPositiveButton("Завантажити") { dialog, _ ->
                        dialog.dismiss()
                        downloadAction()
                    }
                    .setNegativeButton("Пізніше") { dialog, _ ->
                        dialog.dismiss()
                        laterAction()
                    }
                    .setCancelable(false)
                    .show()
            }
        }
    }

    fun performManualUpdateCheck(context: Context) {
        Toast.makeText(context, "Перевірка оновлень...", Toast.LENGTH_SHORT).show()
        checkAndDownloadUpdate(context, force = true) { result ->
            Toast.makeText(context, result, Toast.LENGTH_LONG).show()
        }
    }

    fun startDownload(
        context: Context,
        downloadUrl: String,
        fileName: String,
        onResult: ((String) -> Unit)? = null
    ) {
        val started = downloadWithDownloadManager(context, downloadUrl, fileName)
        if (started) {
            val successMsg = "Завантаження оновлення: $fileName"
            onResult?.let { mainHandler.post { it(successMsg) } }
        } else {
            val publicDownloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val destFile = File(publicDownloadDir, fileName)
            val success = downloadFileWithRedirects(downloadUrl, destFile)
            if (success) {
                val successMsg = "Оновлення завантажено: $fileName"
                installApk(context, destFile)
                onResult?.let { mainHandler.post { it(successMsg) } }
            } else {
                val failMsg = "Не вдалося завантажити оновлення: $fileName"
                onResult?.let { mainHandler.post { it(failMsg) } }
            }
        }
    }

    private fun downloadWithDownloadManager(context: Context, downloadUrl: String, fileName: String): Boolean {
        return try {
            val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
                ?: return false

            val publicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!publicDir.exists()) publicDir.mkdirs()
            val existingFile = File(publicDir, fileName)
            if (existingFile.exists()) {
                existingFile.delete()
            }

            val request = DownloadManager.Request(Uri.parse(downloadUrl)).apply {
                setTitle("Pdocxles Update")
                setDescription("Завантаження $fileName...")
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                setMimeType("application/vnd.android.package-archive")
            }

            val downloadId = downloadManager.enqueue(request)

            val onCompleteReceiver = object : BroadcastReceiver() {
                override fun onReceive(recvContext: Context?, intent: Intent?) {
                    val id = intent?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L) ?: -1L
                    if (id == downloadId) {
                        try {
                            context.unregisterReceiver(this)
                        } catch (_: Exception) {}

                        val downloadedFile = File(publicDir, fileName)
                        if (downloadedFile.exists() && downloadedFile.length() > 0) {
                            installApk(context, downloadedFile)
                        }
                    }
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(
                    onCompleteReceiver,
                    IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
                    Context.RECEIVER_EXPORTED
                )
            } else {
                context.registerReceiver(
                    onCompleteReceiver,
                    IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
                )
            }

            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun installApk(context: Context, apkFile: File) {
        mainHandler.post {
            try {
                if (!apkFile.exists()) return@post
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    val uri: Uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apkFile)
                    } else {
                        Uri.fromFile(apkFile)
                    }
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun findActivity(context: Context?): Activity? {
        var ctx = context
        while (ctx is ContextWrapper) {
            if (ctx is Activity) return ctx
            ctx = ctx.baseContext
        }
        return null
    }

    private fun fetchReleasesFromUrl(urlStr: String): List<JSONObject> {
        var conn: HttpURLConnection? = null
        val result = mutableListOf<JSONObject>()
        try {
            val url = URL(urlStr)
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10000
                readTimeout = 15000
                setRequestProperty("User-Agent", "Pdocxles-Updater/1.0")
                setRequestProperty("Accept", "application/vnd.github.v3+json")
            }

            if (conn.responseCode == 200) {
                val jsonText = conn.inputStream.bufferedReader().use { it.readText() }.trim()
                if (jsonText.startsWith("{")) {
                    result.add(JSONObject(jsonText))
                } else if (jsonText.startsWith("[")) {
                    val arr = JSONArray(jsonText)
                    for (i in 0 until arr.length()) {
                        result.add(arr.getJSONObject(i))
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            conn?.disconnect()
        }
        return result
    }

    private fun downloadFileWithRedirects(urlStr: String, destFile: File, redirectCount: Int = 0): Boolean {
        if (redirectCount > 5) return false
        var conn: HttpURLConnection? = null
        return try {
            val url = URL(urlStr)
            conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000
                readTimeout = 60000
                setRequestProperty("User-Agent", "Pdocxles-Updater/1.0")
                instanceFollowRedirects = true
            }

            val responseCode = conn.responseCode
            if (responseCode == HttpURLConnection.HTTP_MOVED_PERM ||
                responseCode == HttpURLConnection.HTTP_MOVED_TEMP ||
                responseCode == 307 ||
                responseCode == 308
            ) {
                val newUrl = conn.getHeaderField("Location")
                conn.disconnect()
                if (!newUrl.isNullOrEmpty()) {
                    return downloadFileWithRedirects(newUrl, destFile, redirectCount + 1)
                }
                return false
            }

            if (responseCode != HttpURLConnection.HTTP_OK) return false

            val tempFile = File(destFile.parentFile, "${destFile.name}.tmp")
            conn.inputStream.use { input ->
                FileOutputStream(tempFile).use { output ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                    }
                }
            }

            if (tempFile.exists() && tempFile.length() > 0) {
                if (destFile.exists()) destFile.delete()
                tempFile.renameTo(destFile)
                return true
            }
            false
        } catch (e: Exception) {
            e.printStackTrace()
            false
        } finally {
            conn?.disconnect()
        }
    }
}
