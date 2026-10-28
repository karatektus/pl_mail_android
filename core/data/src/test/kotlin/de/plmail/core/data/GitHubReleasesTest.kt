package de.plmail.core.data

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Reading a GitHub release into the one update this build would install, and checking the file.
 *
 * The fixture is the shape `/releases/latest` returned for v0.4.2, cut down to the fields read and
 * one field that is not, so a new key from GitHub is shown not to break the parse.
 */
class GitHubReleasesTest {

    private fun release(
        tag: String = "v0.4.2",
        draft: Boolean = false,
        prerelease: Boolean = false,
        assets: String = FOSS_AND_GOOGLE,
    ) =
        """
        {
          "html_url": "https://github.com/karatektus/pl_mail_android/releases/tag/$tag",
          "tag_name": "$tag",
          "draft": $draft,
          "prerelease": $prerelease,
          "body": "## What's Changed\n* Reader fixes",
          "mentions_count": 3,
          "assets": [$assets]
        }
        """
            .trimIndent()

    @Test
    fun `the foss asset is picked out of a release that carries every flavour`() {
        val update = assertNotNull(GitHubReleases.parseRelease(release(), "-foss.apk"))

        assertEquals("0.4.2", update.versionName)
        assertEquals(402, update.versionCode)
        assertEquals(FOSS_URL, update.apkUrl)
        assertEquals(32673902L, update.sizeBytes)
        assertEquals(FOSS_SHA, update.sha256)
        assertEquals("## What's Changed\n* Reader fixes", update.notes)
    }

    @Test
    fun `a release with no asset for this flavour offers nothing`() {
        val onlyGoogle = release(assets = GOOGLE_APK)

        assertNull(GitHubReleases.parseRelease(onlyGoogle, "-foss.apk"))
    }

    @Test
    fun `the AAB and the mapping file are never mistaken for the APK`() {
        val update = GitHubReleases.parseRelease(release(), "-foss.apk")

        assertEquals(FOSS_URL, update?.apkUrl)
    }

    @Test
    fun `drafts and pre-releases are not installable stable releases`() {
        assertNull(GitHubReleases.parseRelease(release(draft = true), "-foss.apk"))
        assertNull(
            GitHubReleases.parseRelease(release(tag = "v0.5.0-rc1", prerelease = true), "-foss.apk")
        )
    }

    @Test
    fun `a tag that is not a version offers nothing`() {
        assertNull(GitHubReleases.parseRelease(release(tag = "nightly"), "-foss.apk"))
    }

    /** The examples the convention plugin documents, so the two formulas cannot drift apart. */
    @Test
    fun `versionCode is computed the way the build computes it`() {
        assertEquals(100, GitHubReleases.versionCodeOf("0.1.0"))
        assertEquals(10203, GitHubReleases.versionCodeOf("1.2.3"))
        assertEquals(200, GitHubReleases.versionCodeOf("0.2.0-rc1"))
        assertNull(GitHubReleases.versionCodeOf("1.2"))
        assertNull(GitHubReleases.versionCodeOf("one.two.three"))
    }

    @Test
    fun `only a sha256 digest is taken as a checksum`() {
        assertEquals(FOSS_SHA, ApkCheck.hexOf("sha256:$FOSS_SHA"))
        assertEquals(FOSS_SHA, ApkCheck.hexOf("SHA256:${FOSS_SHA.uppercase()}"))
        assertNull(ApkCheck.hexOf("sha512:$FOSS_SHA"))
        assertNull(ApkCheck.hexOf("sha256:abc"))
        assertNull(ApkCheck.hexOf(null))
    }

    @Test
    fun `a downloaded file is accepted only when it is the release's file`() {
        val directory = Files.createTempDirectory("updates").toFile()
        val apk =
            File(directory, "plmail.apk").apply { writeBytes(byteArrayOf(0x50, 0x4b, 3, 4, 5)) }
        val sha = ApkCheck.sha256(apk)

        assertNull(ApkCheck.problem(apk, expectedBytes = 5, expectedSha256 = sha))
        assertNull(ApkCheck.problem(apk, expectedBytes = 0, expectedSha256 = null))
        assertNotNull(ApkCheck.problem(apk, expectedBytes = 6, expectedSha256 = sha))
        assertNotNull(ApkCheck.problem(apk, expectedBytes = 5, expectedSha256 = "0".repeat(64)))

        val page = File(directory, "error.apk").apply { writeText("<html>rate limited</html>") }
        assertNotNull(ApkCheck.problem(page, expectedBytes = 0, expectedSha256 = null))

        directory.deleteRecursively()
    }

    private companion object {
        const val FOSS_SHA = "39e571ef198ea621319fd363d132770cbf765f9024c1304d361e7df6a3104e52"
        const val FOSS_URL =
            "https://github.com/karatektus/pl_mail_android/releases/download/v0.4.2/plmail-0.4.2-foss.apk"

        const val GOOGLE_APK =
            """{"name": "plmail-0.4.2-google.apk", "size": 32879460,
                "digest": "sha256:80bbfb9d8c53e2e98d1c736c7fc56b54fd3883d985f2ffa58b46a0bde7211500",
                "browser_download_url": "https://example.invalid/google.apk"}"""

        const val FOSS_AND_GOOGLE =
            """{"name": "plmail-0.4.2-foss-mapping.txt", "size": 98179201,
                "browser_download_url": "https://example.invalid/mapping.txt"},
               {"name": "plmail-0.4.2-foss.aab", "size": 23306045,
                "browser_download_url": "https://example.invalid/foss.aab"},
               {"name": "plmail-0.4.2-foss.apk", "size": 32673902,
                "digest": "sha256:$FOSS_SHA",
                "browser_download_url": "$FOSS_URL"},
               $GOOGLE_APK"""
    }
}
