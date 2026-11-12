package com.banoku.oplus.autobrightness.util

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import com.banoku.oplus.autobrightness.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

object UpdateChecker {

    private const val GITHUB_REPO = "utaocheshir/AutoBrightnessGuard"
    private const val RELEASES_API_URL = "https://api.github.com/repos/$GITHUB_REPO/releases"
    const val GITHUB_REPO_URL = "https://github.com/$GITHUB_REPO"

    suspend fun checkUpdate(): UpdateResult = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            val url = URL(RELEASES_API_URL)
            connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/vnd.github.v3+json")
            connection.setRequestProperty("User-Agent", "AutoBrightnessGuard-App")
            connection.connectTimeout = 8000
            connection.readTimeout = 8000

            val responseCode = connection.responseCode
            if (responseCode == 200) {
                val response = BufferedReader(InputStreamReader(connection.inputStream)).use { it.readText() }
                val releases = JSONArray(response)

                val currentVersion = BuildConfig.VERSION_NAME
                var newestRelease: ReleaseInfo? = null

                for (i in 0 until releases.length()) {
                    val json = releases.getJSONObject(i)
                    if (json.optBoolean("draft", false)) continue

                    val rawTag = json.optString("tag_name", "").trim()
                    val releaseName = json.optString("name", "Новая версия")
                    val releaseNotes = json.optString("body", "Нет описания изменений.")
                    val htmlUrl = json.optString("html_url", GITHUB_REPO_URL)

                    var apkDownloadUrl: String? = null
                    var apkSize: Long = 0

                    val assets = json.optJSONArray("assets")
                    if (assets != null) {
                        for (j in 0 until assets.length()) {
                            val asset = assets.getJSONObject(j)
                            val assetName = asset.optString("name", "")
                            if (assetName.endsWith(".apk", ignoreCase = true)) {
                                apkDownloadUrl = asset.optString("browser_download_url")
                                apkSize = asset.optLong("size", 0)
                                break
                            }
                        }
                    }

                    if (isVersionNewer(rawTag, currentVersion)) {
                        val candidate = ReleaseInfo(
                            version = rawTag.removePrefix("v").removePrefix("V"),
                            title = releaseName,
                            changelog = releaseNotes,
                            htmlUrl = htmlUrl,
                            apkUrl = apkDownloadUrl,
                            apkSize = apkSize
                        )
                        if (newestRelease == null || isVersionNewer(candidate.version, newestRelease.version)) {
                            newestRelease = candidate
                        }
                    }
                }

                if (newestRelease != null) {
                    UpdateResult.UpdateAvailable(newestRelease)
                } else {
                    UpdateResult.UpToDate(currentVersion)
                }
            } else if (responseCode == 404) {
                UpdateResult.UpToDate("${BuildConfig.VERSION_NAME} (релизы на GitHub ещё не найдены)")
            } else {
                UpdateResult.Error("HTTP $responseCode: " + connection.responseMessage)
            }
        } catch (t: Throwable) {
            UpdateResult.Error("Не удалось связаться с GitHub: ${t.localizedMessage ?: t.message}")
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * Accurately compares versions, handling:
     * - Case insensitivity ('V1.3b' vs '1.3b' -> false)
     * - Numerical components comparison via regex
     * - Suffix handling (e.g. '1.3' stable is newer than '1.3b' beta)
     */
    fun isVersionNewer(remote: String, current: String): Boolean {
        if (remote.isBlank() || current.isBlank()) return false
        if (remote.equals(current, ignoreCase = true)) return false

        // Extract all numeric components
        val rParts = Regex("\\d+").findAll(remote).mapNotNull { it.value.toIntOrNull() }.toList()
        val cParts = Regex("\\d+").findAll(current).mapNotNull { it.value.toIntOrNull() }.toList()

        val maxLen = maxOf(rParts.size, cParts.size)
        for (i in 0 until maxLen) {
            val r = if (i < rParts.size) rParts[i] else 0
            val c = if (i < cParts.size) cParts[i] else 0
            if (r > c) return true
            if (r < c) return false
        }

        // If numeric parts are equal, check suffixes (e.g. 'b' vs '')
        val rSuffix = Regex("[^a-zA-Z]").replace(remote.trimStart('v', 'V'), "").lowercase()
        val cSuffix = Regex("[^a-zA-Z]").replace(current.trimStart('v', 'V'), "").lowercase()

        if (rSuffix == cSuffix) return false

        // Stable (empty suffix e.g. 1.3) is newer than beta (suffix e.g. 1.3b)
        if (rSuffix.isEmpty() && cSuffix.isNotEmpty()) return true
        if (rSuffix.isNotEmpty() && cSuffix.isEmpty()) return false

        return rSuffix > cSuffix
    }

    fun downloadApk(context: Context, downloadUrl: String, fileName: String = "AutoBrightnessGuard-Update.apk") {
        try {
            val request = DownloadManager.Request(Uri.parse(downloadUrl)).apply {
                setTitle("Auto Brightness Guard Update")
                setDescription("Загрузка новой версии APK...")
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                setMimeType("application/vnd.android.package-archive")
            }
            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            dm.enqueue(request)
        } catch (t: Throwable) {
            openInBrowser(context, downloadUrl)
        }
    }

    fun openInBrowser(context: Context, url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (ignored: Exception) {
        }
    }
}

sealed class UpdateResult {
    data class UpdateAvailable(val info: ReleaseInfo) : UpdateResult()
    data class UpToDate(val version: String) : UpdateResult()
    data class Error(val message: String) : UpdateResult()
}

data class ReleaseInfo(
    val version: String,
    val title: String,
    val changelog: String,
    val htmlUrl: String,
    val apkUrl: String?,
    val apkSize: Long
)
