package de.plmail.core.data

import java.io.File

/**
 * A release newer than the installed build, with the one asset this build would install.
 *
 * [sha256] is GitHub's own digest of the asset, as bare lowercase hex. Null only for a release
 * whose API answer carries none, in which case the size is the only thing a download is checked
 * against — Android's signature check at install time is the guarantee either way.
 */
data class AvailableUpdate(
    val versionName: String,
    val versionCode: Int,
    val apkUrl: String,
    val sizeBytes: Long,
    val sha256: String?,
    /** The release notes, as written on GitHub. Markdown, shown as plain text. */
    val notes: String,
    /** The release page, for somebody who would rather download it themselves. */
    val pageUrl: String,
)

/**
 * The app updating itself from the project's GitHub releases.
 *
 * An interface because only one build can do it, and it is the same seam [FcmSupport] is: the
 * `foss` flavour binds the real one, the `google` flavour binds [NoAppUpdater]. Google Play forbids
 * an app updating itself outside Play, and `REQUEST_INSTALL_PACKAGES` is a restricted permission
 * there, so the Play build must contain neither the code nor the permission — and the flavour is
 * what makes that true rather than configurable.
 *
 * Even in `foss`, [isSupported] is false for any build not signed with the release key: a debug
 * build, a fork's build, or one F-Droid signed with its own key. Android refuses an update signed
 * by a different key, so offering one there would be a download that ends in an error dialog.
 */
interface AppUpdater {

    /** Whether this build can install the project's releases over itself. */
    val isSupported: Boolean

    /**
     * The newest stable release when it is newer than this build, null when this build is current.
     *
     * Throws on any failure — no network, GitHub rate-limiting, a release with no APK — so the
     * screen can say why. The background check swallows it; a launch never blocks on GitHub.
     */
    suspend fun latest(): AvailableUpdate?

    /**
     * The update's APK on disk, checked against the release's size and digest.
     *
     * A file that already passed the check is reused rather than fetched again: the install prompt
     * is Android's, and it vanishes when the screen locks — the next tap should go straight back to
     * it, not re-download thirty megabytes.
     */
    suspend fun download(update: AvailableUpdate, onProgress: (Int) -> Unit): File

    /** Hands [apk] to the system installer, which confirms with the user and checks the key. */
    fun install(apk: File)
}

/** The `google` flavour's updater, and any build that has nothing to update from. */
object NoAppUpdater : AppUpdater {
    override val isSupported: Boolean = false

    override suspend fun latest(): AvailableUpdate? = null

    override suspend fun download(update: AvailableUpdate, onProgress: (Int) -> Unit): File =
        error("This build does not update itself.")

    override fun install(apk: File) = Unit
}

/**
 * Told when the background check finds a release nobody has been told about yet.
 *
 * The same shape as [ReminderListener], for the same reason: this module decides that there is
 * something to say, `:core:notifications` decides how it looks in the shade.
 */
interface UpdateListener {
    suspend fun onUpdateAvailable(update: AvailableUpdate)
}
