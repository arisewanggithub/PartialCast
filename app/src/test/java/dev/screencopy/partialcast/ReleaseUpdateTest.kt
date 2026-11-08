package dev.screencopy.partialcast

import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@ConscryptMode(ConscryptMode.Mode.OFF)
class ReleaseUpdateTest {
    @After fun resetCooldown() {
        ReflectionHelpers.setStaticField(GitHubUpdateSource::class.java, "retryAfter", 0L)
    }
    private fun metadata(version: String = "0.1.3", versioned: Boolean = true): JSONObject {
        val name = if (versioned) "PartialCast-$version.apk" else "PartialCast.apk"
        return JSONObject("""{"draft":false,"prerelease":false,"tag_name":"v$version",
            "html_url":"https://github.com/arisewanggithub/PartialCast/releases/tag/v$version",
            "assets":[{"name":"$name","state":"uploaded","size":1000,
            "browser_download_url":"https://github.com/arisewanggithub/PartialCast/releases/download/v$version/$name"}]}""")
    }
    private class Connection(private val body: ByteArray, private val status: Int = 200) :
        HttpURLConnection(URL(GitHubUpdateSource.ENDPOINT)) {
        var disconnected = false
        override fun connect() = Unit
        override fun disconnect() { disconnected = true }
        override fun usingProxy() = false
        override fun getResponseCode() = status
        override fun getInputStream() = ByteArrayInputStream(body)
        override fun getContentLengthLong() = body.size.toLong()
    }
    @Test fun versionedAndLegacyAssetNamesBothPermitNewerStablePrompt() {
        for (versioned in listOf(true, false)) {
            assertEquals("0.1.3", GitHubUpdateSource.parse(metadata(versioned = versioned).toString(), "0.1.2")?.version)
        }
        assertEquals("0.10.0", GitHubUpdateSource.parse(metadata("0.10.0").toString(), "0.9.0")?.version)
    }
    @Test fun debugBuildDoesNotRequestIncompatibleReleaseMetadata() {
        var connections = 0
        val source = GitHubUpdateSource {
            connections++
            Connection(metadata().toString().toByteArray())
        }
        assertNull(source.check("0.1.3-debug"))
        assertEquals(0, connections)
    }
    @Test fun sameOlderDraftAndPrereleaseNeverPrompt() {
        assertNull(GitHubUpdateSource.parse(metadata("0.1.2").toString(), "0.1.2"))
        assertNull(GitHubUpdateSource.parse(metadata("0.1.1").toString(), "0.1.2"))
        assertNull(GitHubUpdateSource.parse(metadata().put("draft", true).toString(), "0.1.2"))
        assertNull(GitHubUpdateSource.parse(metadata().put("prerelease", true).toString(), "0.1.2"))
    }
    @Test fun unsafeMissingAndAmbiguousAssetsCannotPrompt() {
        for (json in listOf(metadata().put("html_url", "https://example.com"),
            metadata().put("tag_name", "v../wrong"), metadata().put("assets", org.json.JSONArray()))) {
            assertThrows(Exception::class.java) { GitHubUpdateSource.parse(json.toString(), "0.1.2") }
        }
        val ambiguous = metadata()
        ambiguous.getJSONArray("assets").put(metadata(versioned = false).getJSONArray("assets").getJSONObject(0))
        assertThrows(IllegalArgumentException::class.java) { GitHubUpdateSource.parse(ambiguous.toString(), "0.1.2") }
    }
    @Test fun networkCheckIsBoundedAnonymousAndAlwaysDisconnects() {
        val connection = Connection(metadata().toString().toByteArray())
        val source = GitHubUpdateSource { connection }
        assertNotNull(source.check("0.1.2"))
        assertTrue(connection.disconnected)
        assertFalse(connection.instanceFollowRedirects)
        assertEquals(5000, connection.connectTimeout)
        assertNull(connection.getRequestProperty("Authorization"))
        assertEquals("PartialCast-Update-Check/1", connection.getRequestProperty("User-Agent"))
    }
    @Test fun privateUnauthorizedAndRedirectResponsesFailWithoutSuccessFallback() {
        for (status in listOf(404, 401, 302, 500)) {
            val connection = Connection(ByteArray(0), status)
            assertThrows(IOException::class.java) { GitHubUpdateSource { connection }.check("0.1.2") }
            assertTrue(connection.disconnected)
        }
    }
    @Test fun oversizedAndCancelledRequestsAreRejected() {
        val connection = Connection(ByteArray(GitHubUpdateSource.METADATA_LIMIT + 1))
        assertThrows(IllegalArgumentException::class.java) { GitHubUpdateSource { connection }.check("0.1.2") }
        assertTrue(connection.disconnected)
        val source = GitHubUpdateSource { error("Cancelled source must not open a connection") }
        source.cancel()
        assertThrows(IllegalStateException::class.java) { source.check("0.1.2") }
    }
    @Test fun rateLimitedFreshLaunchDoesNotMakeAnotherRequestDuringCooldown() {
        val rateLimited = Connection(ByteArray(0), 429)
        assertThrows(IOException::class.java) { GitHubUpdateSource { rateLimited }.check("0.1.2") }
        assertTrue(rateLimited.disconnected)
        val next = GitHubUpdateSource { error("Cooldown must prevent a new network request") }
        assertThrows(IOException::class.java) { next.check("0.1.2") }
    }
}
