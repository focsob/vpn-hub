package com.vpnhub.app.data

import com.vpnhub.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.net.HttpURLConnection
import java.net.URL

/** Checks GitHub Releases for a newer APK when the app is opened. */
object UpdateChecker {

    data class Update(val tag: String, val name: String, val notes: String, val apkUrl: String, val pageUrl: String)

    @Serializable
    private data class Release(
        @SerialName("tag_name") val tag: String = "",
        val name: String? = null,
        val body: String? = null,
        @SerialName("html_url") val htmlUrl: String = "",
        val assets: List<Asset> = emptyList(),
    )

    @Serializable
    private data class Asset(
        val name: String = "",
        @SerialName("browser_download_url") val url: String = "",
    )

    /** Release tags are "apk-<build number>"; the build number is also this app's versionCode. */
    private fun buildNumber(tag: String): Int = tag.removePrefix("apk-").toIntOrNull() ?: -1

    suspend fun check(): Update? = withContext(Dispatchers.IO) {
        if (BuildConfig.REPO.isBlank()) return@withContext null
        runCatching {
            val conn = URL("https://api.github.com/repos/${BuildConfig.REPO}/releases/latest")
                .openConnection() as HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            try {
                if (conn.responseCode != 200) return@runCatching null
                val release = json.decodeFromString(Release.serializer(), conn.inputStream.bufferedReader().readText())
                if (buildNumber(release.tag) <= BuildConfig.VERSION_CODE) return@runCatching null
                val apk = release.assets.firstOrNull { it.name.endsWith(".apk") } ?: return@runCatching null
                Update(
                    tag = release.tag,
                    name = release.name ?: release.tag,
                    notes = cleanNotes(release.body.orEmpty()),
                    apkUrl = apk.url,
                    pageUrl = release.htmlUrl,
                )
            } finally {
                conn.disconnect()
            }
        }.getOrNull()
    }

    /** Keep the readable part of the release body (drop the collapsed technical change list and markdown marks). */
    private fun cleanNotes(body: String): String = body
        .substringBefore("<details>")
        .lines()
        .filterNot { it.contains("下載下面嘅") }
        .joinToString("\n") { it.removePrefix("## ").replace("`", "") }
        .trim()
}
