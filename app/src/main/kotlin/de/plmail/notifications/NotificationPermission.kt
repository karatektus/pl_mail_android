package de.plmail.notifications

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * Asks for permission to notify, once a server is connected.
 *
 * Timing first, because it is the part that matters: this is deliberately **not** asked at launch.
 * A permission dialog that appears before the user has connected anything is one they have no way
 * to evaluate — they have not yet seen a single message, so "allow notifications?" is a question
 * about nothing. Composed only under `ConnectionState.Connected`, so the first time it is asked is
 * the first time there is mail it could be about.
 *
 * It is asked at most twice in the life of an install, and there is no persisted "already asked"
 * flag, on purpose:
 *
 * - Never asked before, and not granted → `shouldShowRequestPermissionRationale` is false, so this
 *   asks. That is the one prompt most people ever see.
 * - Asked once and denied → the platform starts returning true, and this stops. Re-prompting
 *   somebody who has already said no is how an app teaches people to deny it reflexively.
 * - Denied twice → the platform returns false again, this asks, and the system silently refuses
 *   without showing anything. No dialog, no noise, and nothing gained by keeping our own flag.
 *
 * Storing a flag of our own would add a settings key that has to be migrated and cleared, to
 * reproduce behaviour the platform already provides exactly.
 */
@Composable
fun RequestNotificationPermission() {
    // POST_NOTIFICATIONS is API 33. Below it the permission does not exist, is
    // granted implicitly by declaring it, and requesting it does nothing --
    // this app's floor is 31, so the branch is real rather than defensive.
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return

    val context = LocalContext.current
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            // Nothing to do either way. A refusal is a legitimate answer and
            // the app keeps working; what it costs is stated on the diagnostics
            // screen rather than nagged about here.
        }

    LaunchedEffect(Unit) {
        val granted =
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED

        if (granted) return@LaunchedEffect

        val activity = context as? Activity ?: return@LaunchedEffect
        val alreadyRefusedOnce =
            ActivityCompat.shouldShowRequestPermissionRationale(
                activity,
                Manifest.permission.POST_NOTIFICATIONS,
            )

        if (!alreadyRefusedOnce) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
