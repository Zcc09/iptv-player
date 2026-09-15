package com.zcc09.iptvplayer.core

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.zcc09.iptvplayer.BuildConfig
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

data class UpdateInfo(
    val versionName: String,
    val title: String,
    val notes: String,
    val apkUrl: String,
    val apkSize: Long
)

sealed class UpdateState {
    data object Idle : UpdateState()
    data object Checking : UpdateState()
    data class Available(val info: UpdateInfo) : UpdateState()
    data class Downloading(
        val info: UpdateInfo,
        val percent: Int,
        val downloadedBytes: Long,
        val totalBytes: Long
    ) : UpdateState()
    data class ReadyToInstall(val info: UpdateInfo, val apkFile: File) : UpdateState()
    data class Error(val message: String) : UpdateState()
}

/**
 * Checks GitHub releases for updates, downloads new APKs with progress reporting,
 * and launches the system package installer.
 */
object Updater {

    private const val GITHUB_REPO = "Zcc09/iptv-player"
    private const val RELEASES_API = "https://api.github.com/repos/$GITHUB_REPO/releases/latest"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private var lastCheckedMs = 0L

    /**
     * Determines if [latestTag] is newer than [currentVersion] using semver comparison.
     * Delegates to [VersionTools] (pure, unit-tested on the JVM).
     */
    fun isNewerVersion(latestTag: String, currentVersion: String): Boolean =
        VersionTools.isNewer(latestTag, currentVersion)

    /**
     * Checks the GitHub API for the latest release.
     */
    suspend fun checkForUpdate(
        currentVersion: String,
        isManual: Boolean = false
    ): UpdateInfo? = withContext(Dispatchers.IO) {
        // The Play build must not self-update: Google Play forbids it and owns
        // updates for that flavor (see PlayUpdate). Only the sideloaded github
        // build does the GitHub release check + APK install.
        if (!BuildConfig.SELF_UPDATE) {
            Logx.i("GitHub updater skipped: this flavor updates through Google Play")
            return@withContext null
        }

        val now = System.currentTimeMillis()
        if (!isManual && now - lastCheckedMs < 10 * 60 * 1000) {
            return@withContext null
        }
        lastCheckedMs = now

        _state.value = UpdateState.Checking
        try {
            val conn = (URL(RELEASES_API).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 15_000
                setRequestProperty("User-Agent", "IPTVPlayer-Updater")
                setRequestProperty("Accept", "application/vnd.github+json")
            }

            if (conn.responseCode != 200) {
                val err = "GitHub check returned HTTP ${conn.responseCode}"
                Logx.w(err)
                if (isManual) {
                    _state.value = UpdateState.Error(err)
                    Repo.postStatus(err)
                } else {
                    _state.value = UpdateState.Idle
                }
                return@withContext null
            }

            val body = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()

            val root = json.parseToJsonElement(body).jsonObject
            val tagName = root["tag_name"]?.jsonPrimitive?.content ?: ""
            val title = root["name"]?.jsonPrimitive?.content ?: tagName
            val releaseNotes = root["body"]?.jsonPrimitive?.content ?: ""
            val assets = root["assets"]?.jsonArray ?: emptyList()

            // Find release APK, fallback to any .apk asset
            val apkAsset = assets.firstOrNull {
                it.jsonObject["name"]?.jsonPrimitive?.content == "iptv-player-release.apk"
            } ?: assets.firstOrNull {
                it.jsonObject["name"]?.jsonPrimitive?.content?.endsWith(".apk", ignoreCase = true) == true
            }

            if (apkAsset == null) {
                Logx.w("Latest release ($tagName) has no APK asset attached")
                _state.value = UpdateState.Idle
                return@withContext null
            }

            val apkUrl = apkAsset.jsonObject["browser_download_url"]?.jsonPrimitive?.content ?: ""
            val apkSize = apkAsset.jsonObject["size"]?.jsonPrimitive?.long ?: 0L

            if (isNewerVersion(tagName, currentVersion)) {
                val info = UpdateInfo(
                    versionName = tagName,
                    title = title,
                    notes = releaseNotes,
                    apkUrl = apkUrl,
                    apkSize = apkSize
                )
                Logx.i("Update found: $tagName (current: $currentVersion)")
                _state.value = UpdateState.Available(info)
                return@withContext info
            } else {
                Logx.i("App is up to date: $currentVersion (latest on GitHub: $tagName)")
                _state.value = UpdateState.Idle
                if (isManual) {
                    Repo.postStatus("Internet TV Player is up to date ($currentVersion)")
                }
                return@withContext null
            }
        } catch (t: Throwable) {
            Logx.e("Failed to check for updates: ${t.message}")
            if (isManual) {
                _state.value = UpdateState.Error(t.message ?: "Network error checking update")
                Repo.postStatus("Update check failed: ${t.message}")
            } else {
                _state.value = UpdateState.Idle
            }
            null
        }
    }

    fun dismissUpdate() {
        _state.value = UpdateState.Idle
    }

    /**
     * Downloads the APK file to the app's cache directory with progress reporting.
     */
    fun startDownload(context: Context, info: UpdateInfo) {
        scope.launch {
            _state.value = UpdateState.Downloading(info, 0, 0L, info.apkSize)
            try {
                val downloadedFile = downloadApkFile(context, info) { percent, downloaded, total ->
                    _state.value = UpdateState.Downloading(info, percent, downloaded, total)
                }
                Logx.i("Update downloaded to ${downloadedFile.absolutePath} (${downloadedFile.length()} bytes)")
                _state.value = UpdateState.ReadyToInstall(info, downloadedFile)
                installApk(context, downloadedFile)
            } catch (t: Throwable) {
                Logx.e("Update download failed", t)
                _state.value = UpdateState.Error("Download failed: ${t.message}")
            }
        }
    }

    private suspend fun downloadApkFile(
        context: Context,
        info: UpdateInfo,
        onProgress: (percent: Int, downloaded: Long, total: Long) -> Unit
    ): File = withContext(Dispatchers.IO) {
        val updatesDir = File(context.cacheDir, "updates").apply { mkdirs() }
        val cleanTag = info.versionName.replace(Regex("[^A-Za-z0-9_.-]"), "_")
        val targetFile = File(updatesDir, "iptv-player-$cleanTag.apk")
        if (targetFile.exists()) targetFile.delete()

        val (stream, contentLength) = openStreamWithRedirects(info.apkUrl, timeoutMs = 30_000)
        val totalBytes = if (contentLength > 0) contentLength else info.apkSize

        stream.use { input ->
            targetFile.outputStream().use { output ->
                val buf = ByteArray(32 * 1024)
                var bytesSoFar = 0L
                var read: Int
                while (input.read(buf).also { read = it } != -1) {
                    output.write(buf, 0, read)
                    bytesSoFar += read
                    val percent = if (totalBytes > 0) {
                        ((bytesSoFar * 100) / totalBytes).toInt().coerceIn(0, 100)
                    } else -1
                    onProgress(percent, bytesSoFar, totalBytes)
                }
                output.flush()
            }
        }
        targetFile
    }

    private fun openStreamWithRedirects(initialUrl: String, timeoutMs: Int = 30_000): Pair<InputStream, Long> {
        var url = initialUrl
        var redirects = 0
        while (redirects < 6) {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "IPTVPlayer-Updater")
            }
            val code = conn.responseCode
            if (code == HttpURLConnection.HTTP_MOVED_PERM ||
                code == HttpURLConnection.HTTP_MOVED_TEMP ||
                code == 307 || code == 308
            ) {
                val loc = conn.getHeaderField("Location") ?: break
                conn.disconnect()
                url = loc
                redirects++
                continue
            }
            val length = conn.contentLengthLong.takeIf { it > 0 } ?: conn.contentLength.toLong()
            return Pair(conn.inputStream, length)
        }
        throw IOException("Too many redirects downloading update: $initialUrl")
    }

    /**
     * Prompts the Android OS package installer to install the downloaded APK.
     *
     * Logs the FileProvider URI and the resolved installer, so the end-to-end
     * suite can prove the install hand-off works (a bad authority or
     * file_paths.xml throws on getUriForFile, which is logged as an error).
     */
    fun installApk(context: Context, apkFile: File) {
        try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )
            Logx.i("UPDATE_INSTALL_URI uri=$uri bytes=${apkFile.length()}")

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val handler = intent.resolveActivity(context.packageManager)
            Logx.i("UPDATE_INSTALL_INTENT handler=${handler?.packageName ?: "none"}")
            // Always attempt the launch: resolveActivity() is subject to package
            // visibility filtering, so a null result does not mean no installer
            // exists. A genuinely unresolvable intent is caught below.
            context.startActivity(intent)
            Logx.i("UPDATE_INSTALL_LAUNCHED uri=$uri")
            Repo.postStatus("Opening the installer to update Internet TV Player")
        } catch (t: Throwable) {
            Logx.e("Failed to start installer intent", t)
            Repo.postStatus("Could not open installer: ${t.message}")
        }
    }
}
