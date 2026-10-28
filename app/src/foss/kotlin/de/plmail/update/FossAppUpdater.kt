package de.plmail.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.FileProvider
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import de.plmail.BuildConfig
import de.plmail.core.data.AppUpdater
import de.plmail.core.data.AvailableUpdate
import de.plmail.core.data.GitHubReleases
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The `foss` build updating itself from the project's GitHub releases.
 *
 * The pattern NewPipe and most sideloaded apps use: find the newest release, download its APK, and
 * hand it to the **system** installer with `ACTION_VIEW`. Android then does the part that matters —
 * the user confirms, and an APK that is not the same package signed by the same key is refused. The
 * first time, Android also asks the user to allow plMail to install apps; its own dialog links to
 * the switch, so nothing here has to.
 *
 * `ACTION_VIEW` rather than a `PackageInstaller` session: one prompt per update either way for an
 * app that was first installed from a browser, and no session, receiver or status callback to keep.
 */
@Singleton
class FossAppUpdater @Inject constructor(@param:ApplicationContext private val context: Context) :
    AppUpdater {

    private val releases =
        GitHubReleases(
            repository = REPOSITORY,
            assetSuffix = ASSET_SUFFIX,
            installedVersionCode = BuildConfig.VERSION_CODE,
            directory = File(context.filesDir, "updates"),
        )

    /**
     * Whether this install is signed with the release key.
     *
     * Read once: a running app's signature does not change. A debug build, a fork's build and an
     * F-Droid-signed build all answer false, and for all three that is right — Android would refuse
     * the release over them, after a thirty-megabyte download.
     */
    override val isSupported: Boolean by lazy { signerDigests().contains(RELEASE_CERT_SHA256) }

    override suspend fun latest(): AvailableUpdate? = releases.latest()

    override suspend fun download(update: AvailableUpdate, onProgress: (Int) -> Unit): File =
        releases.download(update, onProgress)

    override fun install(apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)

        context.startActivity(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, APK_TYPE)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
    }

    /**
     * SHA-256 of every certificate this install is signed with, past ones included, so a key
     * rotation on the release side does not silently switch the updater off.
     */
    private fun signerDigests(): Set<String> = runCatching {
        val info =
            context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.GET_SIGNING_CERTIFICATES,
            )
        val signing = info.signingInfo ?: return@runCatching emptyList()

        val certificates =
            if (signing.hasMultipleSigners()) signing.apkContentsSigners
            else signing.signingCertificateHistory

        certificates.orEmpty().map { signature ->
            val digest = MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())
            digest.joinToString("") { "%02x".format(it) }
        }
    }
        .getOrDefault(emptyList())
        .toSet()

    private companion object {
        /** Where the releases this build installs are published. */
        const val REPOSITORY = "karatektus/pl_mail_android"

        const val ASSET_SUFFIX = "-foss.apk"

        /**
         * The release key's certificate, as `apksigner verify --print-certs` prints it for
         * `plmail-0.4.2-foss.apk` (`CN=plMail, O=plMail`). If the release key ever changes, this
         * changes with it — see docs/RELEASING.md.
         */
        const val RELEASE_CERT_SHA256 =
            "6076e3a32f3c06ca302ba40c5598b4bf47423c298d88f68b1abaa854fc8dd3b9"

        const val APK_TYPE = "application/vnd.android.package-archive"
    }
}

/** A FileProvider of its own; see the foss manifest for why it cannot be FileProvider itself. */
class UpdateFileProvider : FileProvider()

@Module
@InstallIn(SingletonComponent::class)
abstract class FossUpdateBindings {

    @Binds @Singleton abstract fun updater(real: FossAppUpdater): AppUpdater
}
