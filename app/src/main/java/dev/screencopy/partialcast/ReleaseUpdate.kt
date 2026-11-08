package dev.screencopy.partialcast

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.math.BigInteger
import java.net.HttpURLConnection
import java.net.URL

internal data class ReleaseUpdate(val version: String, val page: String)

internal interface UpdateSource {
    fun check(installedVersion: String): ReleaseUpdate?
    fun cancel()
}

/** Metadata only: no authentication, APK download, installer or capture data. */
internal class GitHubUpdateSource(
    private val open: () -> HttpURLConnection = {
        URL(ENDPOINT).openConnection() as? HttpURLConnection
            ?: throw IOException("Invalid release metadata connection")
    }
) : UpdateSource {
    @Volatile private var cancelled = false
    @Volatile private var connection: HttpURLConnection? = null

    override fun cancel() {
        cancelled = true
        connection?.disconnect()
    }

    override fun check(installedVersion: String): ReleaseUpdate? {
        check(!cancelled) { "Update check cancelled" }
        if (installedVersion.endsWith("-debug")) {
            android.util.Log.i("PartialCast", "Debug build skips release-only update metadata")
            return null
        }
        if (System.currentTimeMillis() < retryAfter) throw IOException("GitHub update checks are cooling down")
        val current = open()
        connection = current
        try {
            check(!cancelled) { "Update check cancelled" }
            current.instanceFollowRedirects = false
            current.connectTimeout = 5000
            current.readTimeout = 5000
            current.setRequestProperty("Accept", "application/vnd.github+json")
            current.setRequestProperty("User-Agent", "PartialCast-Update-Check/1")
            current.setRequestProperty("Accept-Encoding", "identity")
            val status = current.responseCode
            if (status == 403 || status == 429) {
                val seconds = current.getHeaderField("Retry-After")?.toLongOrNull()
                    ?: current.getHeaderField("X-RateLimit-Reset")?.toLongOrNull()
                        ?.minus(System.currentTimeMillis() / 1000) ?: 3600L
                retryAfter = System.currentTimeMillis() + seconds.coerceIn(60, 86400) * 1000
                throw IOException("GitHub update request denied or rate limited (HTTP $status)")
            }
            if (status != 200) throw IOException("Public stable release unavailable (HTTP $status)")
            val expected = current.contentLengthLong
            require(expected <= METADATA_LIMIT) { "Release metadata too large" }
            val deadline = System.nanoTime() + 15_000_000_000L
            val output = ByteArrayOutputStream()
            current.inputStream.use { input ->
                val buffer = ByteArray(4096)
                while (true) {
                    check(!cancelled && System.nanoTime() < deadline) { "Update check cancelled or timed out" }
                    val count = input.read(buffer)
                    if (count == -1) break
                    require(output.size() + count <= METADATA_LIMIT) { "Release metadata too large" }
                    output.write(buffer, 0, count)
                }
            }
            check(!cancelled) { "Update check cancelled" }
            require(expected < 0 || output.size().toLong() == expected) { "Incomplete release metadata" }
            return parse(output.toString("UTF-8"), installedVersion)
        } finally {
            current.disconnect()
            connection = null
        }
    }

    companion object {
        const val ENDPOINT = "https://api.github.com/repos/arisewanggithub/PartialCast/releases/latest"
        const val METADATA_LIMIT = 128 * 1024
        private const val RELEASES = "https://github.com/arisewanggithub/PartialCast/releases"
        @Volatile private var retryAfter = 0L
        private val stable = Regex("([0-9]+)\\.([0-9]+)\\.([0-9]+)")

        fun parse(text: String, installedVersion: String): ReleaseUpdate? {
            require(text.toByteArray(Charsets.UTF_8).size <= METADATA_LIMIT) { "Release metadata too large" }
            val release = JSONObject(text)
            if (release.getBoolean("draft") || release.getBoolean("prerelease")) return null
            val tag = release.getString("tag_name")
            require(tag.length <= 80 && tag.startsWith("v")) { "Invalid stable release tag" }
            val version = tag.substring(1)
            val available = stable.matchEntire(version) ?: error("Invalid stable release version")
            val installed = stable.matchEntire(installedVersion)
                ?: error("Installed version is not a stable semantic version")
            val page = "$RELEASES/tag/$tag"
            require(release.getString("html_url") == page) { "Release page is outside the configured repository" }
            val assets = release.getJSONArray("assets")
            var found = false
            for (index in 0 until assets.length()) {
                val asset = assets.getJSONObject(index)
                val name = asset.getString("name")
                if (name == "PartialCast-$version.apk" || name == "PartialCast.apk") {
                    require(!found && asset.getString("state") == "uploaded" && asset.getLong("size") > 0) {
                        "Missing or ambiguous release APK"
                    }
                    require(asset.getString("browser_download_url") == "$RELEASES/download/$tag/$name") {
                        "Release APK is outside the configured repository"
                    }
                    found = true
                }
            }
            require(found) { "No completed PartialCast APK in release" }
            for (index in 1..3) {
                val comparison = BigInteger(available.groupValues[index]).compareTo(BigInteger(installed.groupValues[index]))
                if (comparison > 0) return ReleaseUpdate(version, page)
                if (comparison < 0) return null
            }
            return null
        }
    }
}
