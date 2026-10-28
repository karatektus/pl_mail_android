package de.plmail.core.data

import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Reads a GitHub repository's latest release and downloads its APK.
 *
 * `/releases/latest` and nothing else. GitHub answers it with the newest release that is neither a
 * draft nor a pre-release, which is exactly the stable channel — a `v0.5.0-rc1` tag is published as
 * a pre-release by the release workflow and is never offered. Unauthenticated, so GitHub allows
 * sixty requests an hour per address: a daily check and the odd button press are nowhere near it.
 *
 * Its own [OkHttpClient], not the one the mail transport derives per server. That one carries each
 * server's trust manager and timeouts sized for JMAP calls; a thirty-megabyte download needs no
 * call timeout at all, only a read timeout that notices a stalled connection.
 *
 * @param repository `owner/name`.
 * @param assetSuffix the end of the asset name this build installs, `-foss.apk`: the release
 *   workflow names every asset `plmail-<version>-<flavour>.<ext>`.
 * @param directory where downloads go. Emptied of anything that is not the current download.
 */
class GitHubReleases(
    private val repository: String,
    private val assetSuffix: String,
    private val installedVersionCode: Int,
    private val directory: File,
) {

    private val http: OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()

    suspend fun latest(): AvailableUpdate? =
        withContext(Dispatchers.IO) {
            val request =
                Request.Builder()
                    .url("https://api.github.com/repos/$repository/releases/latest")
                    .header("Accept", "application/vnd.github+json")
                    .build()

            val body =
                http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful)
                        throw IOException("GitHub answered ${response.code}")
                    response.body.string()
                }

            val release =
                parseRelease(body, assetSuffix)
                    ?: throw IOException("The latest release has no *$assetSuffix asset")

            release.takeIf { it.versionCode > installedVersionCode }
        }

    suspend fun download(update: AvailableUpdate, onProgress: (Int) -> Unit): File =
        withContext(Dispatchers.IO) {
            directory.mkdirs()

            val apk = File(directory, "plmail-${update.versionCode}.apk")
            val partial = File(directory, "${apk.name}.part")

            // One update on disk at a time. Anything else here is an older
            // release or a download that was abandoned.
            directory.listFiles()?.forEach { if (it != apk) it.delete() }

            if (apk.exists()) {
                if (ApkCheck.problem(apk, update.sizeBytes, update.sha256) == null) {
                    onProgress(100)
                    return@withContext apk
                }
                apk.delete()
            }

            try {
                fetch(update, partial, onProgress)

                ApkCheck.problem(partial, update.sizeBytes, update.sha256)?.let {
                    throw IOException(it)
                }

                if (!partial.renameTo(apk)) throw IOException("Could not keep the download")

                apk
            } catch (failure: Throwable) {
                partial.delete()
                throw failure
            }
        }

    private suspend fun fetch(update: AvailableUpdate, into: File, onProgress: (Int) -> Unit) {
        val request = Request.Builder().url(update.apkUrl).build()

        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Download answered ${response.code}")

            val total = response.body.contentLength().takeIf { it > 0 } ?: update.sizeBytes
            var read = 0L
            var reported = -1

            response.body.byteStream().use { input ->
                into.outputStream().use { output ->
                    val buffer = ByteArray(BUFFER_BYTES)

                    while (true) {
                        // Leaving the screen cancels the scope; without this
                        // the loop would carry on writing a file nobody wants.
                        currentCoroutineContext().ensureActive()

                        val count = input.read(buffer)
                        if (count < 0) break

                        output.write(buffer, 0, count)
                        read += count

                        if (total > 0) {
                            val percent = (100 * read / total).toInt()
                            if (percent != reported) {
                                reported = percent
                                onProgress(percent)
                            }
                        }
                    }
                }
            }
        }
    }

    companion object {
        private const val TIMEOUT_SECONDS = 30L
        private const val BUFFER_BYTES = 64 * 1024

        private val JSON = Json { ignoreUnknownKeys = true }

        /**
         * One release from the GitHub API, or null when it carries no asset ending in [assetSuffix]
         * or a tag that is not a version.
         *
         * A draft or a pre-release is refused here too, although `/releases/latest` never returns
         * either: the function is the definition of "an installable stable release", and it should
         * not depend on which endpoint fed it.
         */
        fun parseRelease(json: String, assetSuffix: String): AvailableUpdate? {
            val release = JSON.parseToJsonElement(json).jsonObject

            if (release.flag("draft") || release.flag("prerelease")) return null

            val versionName = release.text("tag_name")?.removePrefix("v") ?: return null
            val versionCode = versionCodeOf(versionName) ?: return null

            val asset =
                release["assets"]
                    ?.jsonArray
                    ?.map { it.jsonObject }
                    ?.firstOrNull {
                        it.text("name")?.endsWith(assetSuffix) == true
                    } ?: return null

            return AvailableUpdate(
                versionName = versionName,
                versionCode = versionCode,
                apkUrl = asset.text("browser_download_url") ?: return null,
                sizeBytes = asset["size"]?.jsonPrimitive?.longOrNull ?: 0L,
                sha256 = ApkCheck.hexOf(asset.text("digest")),
                notes = release.text("body").orEmpty(),
                pageUrl = release.text("html_url").orEmpty(),
            )
        }

        /**
         * The versionCode a release with this versionName was built with.
         *
         * The same arithmetic as `versionCodeOf` in `AndroidApplicationConventionPlugin`, which is
         * where the installed build's code came from — `0.4.2` is 402, `1.2.3` is 10203, and a
         * pre-release suffix is dropped. Repeated rather than shared because build logic cannot be
         * a runtime dependency; `GitHubReleasesTest` pins the two together on the same examples.
         */
        fun versionCodeOf(versionName: String): Int? {
            val parts = versionName.substringBefore('-').split('.')
            if (parts.size != 3) return null

            val (major, minor, patch) = parts.map { it.toIntOrNull() ?: return null }

            return major * 10_000 + minor * 100 + patch
        }

        private fun JsonObject.text(key: String): String? = get(key)?.jsonPrimitive?.contentOrNull

        private fun JsonObject.flag(key: String): Boolean =
            get(key)?.jsonPrimitive?.booleanOrNull == true
    }
}

/** Whether a downloaded file is the asset the release describes. */
internal object ApkCheck {

    /**
     * GitHub's asset `digest`, `sha256:<hex>`, as bare lowercase hex — or null for anything else.
     */
    fun hexOf(digest: String?): String? =
        digest
            ?.trim()
            ?.lowercase()
            ?.takeIf { it.startsWith("sha256:") }
            ?.removePrefix("sha256:")
            ?.takeIf { hex -> hex.length == 64 && hex.all { it in '0'..'9' || it in 'a'..'f' } }

    /**
     * What is wrong with [file], or null when it checks out.
     *
     * Cheapest first: an APK is a zip and starts `PK`, which catches an HTML error page saved under
     * the APK's name; then the size the release gives; then the digest, which reads the whole file.
     */
    fun problem(file: File, expectedBytes: Long, expectedSha256: String?): String? {
        if (!file.exists() || file.length() < 4) return "The download is empty"

        val magic = ByteArray(2)
        file.inputStream().use { it.read(magic) }
        if (magic[0] != 'P'.code.toByte() || magic[1] != 'K'.code.toByte()) {
            return "The download is not an APK"
        }

        if (expectedBytes > 0 && file.length() != expectedBytes) {
            return "The download is ${file.length()} bytes, the release says $expectedBytes"
        }

        if (expectedSha256 != null && sha256(file) != expectedSha256) {
            return "The download does not match the release's checksum"
        }

        return null
    }

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")

        file.inputStream().use { input ->
            val buffer = ByteArray(256 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }

        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
