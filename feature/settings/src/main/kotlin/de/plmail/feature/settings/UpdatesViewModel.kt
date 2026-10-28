package de.plmail.feature.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import de.plmail.core.data.AppUpdater
import de.plmail.core.data.AvailableUpdate
import de.plmail.core.datastore.UpdatePrefsStore
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Where the updater is, as the version section draws it. */
sealed interface UpdatePhase {
    /** Nothing asked yet. */
    data object Idle : UpdatePhase

    data object Checking : UpdatePhase

    data object UpToDate : UpdatePhase

    data class Available(val update: AvailableUpdate) : UpdatePhase

    data class Downloading(val update: AvailableUpdate, val percent: Int) : UpdatePhase

    /** Downloaded and checked; the system installer has been shown it at least once. */
    data class Ready(val update: AvailableUpdate, val apk: File) : UpdatePhase

    /** In the failure's own words, untranslated, for the reason diagnostics gives everywhere. */
    data class Failed(val message: String, val update: AvailableUpdate?) : UpdatePhase
}

data class UpdatesState(
    val installedVersion: String,
    val isSupported: Boolean,
    val isAutoCheckOn: Boolean = true,
    val phase: UpdatePhase = UpdatePhase.Idle,
)

/**
 * The version section of the diagnostics screen.
 *
 * Its own ViewModel rather than more state on [DiagnosticsViewModel]: that one is about the mail
 * server and this one only ever talks to GitHub, and the screen's promise that only "check now"
 * contacts your server stays true because the two never share a button.
 */
@HiltViewModel
class UpdatesViewModel
@Inject
constructor(
    @ApplicationContext context: Context,
    private val updater: AppUpdater,
    private val prefs: UpdatePrefsStore,
) : ViewModel() {

    private val installedVersion: String =
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }
            .getOrNull() ?: "?"

    private val phase = MutableStateFlow<UpdatePhase>(UpdatePhase.Idle)

    private var downloading: Job? = null

    val state: StateFlow<UpdatesState> =
        combine(prefs.isAutoCheckOn, phase) { isAutoCheckOn, phase ->
                UpdatesState(
                    installedVersion = installedVersion,
                    isSupported = updater.isSupported,
                    isAutoCheckOn = isAutoCheckOn,
                    phase = phase,
                )
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                initialValue = UpdatesState(installedVersion, isSupported = updater.isSupported),
            )

    init {
        // Asked on opening only when the daily check has already announced a
        // release, which is the case of somebody arriving from the
        // notification: they came to see it, and making them press a button
        // to find what the shade just told them would be a step for nothing.
        viewModelScope.launch {
            val announced = prefs.notifiedVersionCode() ?: return@launch
            val installed = installedCode(context)
            if (updater.isSupported && installed != null && announced > installed) check()
        }
    }

    fun check() {
        if (phase.value is UpdatePhase.Checking || downloading?.isActive == true) return

        phase.value = UpdatePhase.Checking

        viewModelScope.launch {
            phase.value =
                runCatching { updater.latest() }
                    .fold(
                        onSuccess = { update ->
                            update?.let { UpdatePhase.Available(it) } ?: UpdatePhase.UpToDate
                        },
                        onFailure = { UpdatePhase.Failed(it.describe(), update = null) },
                    )
        }
    }

    /**
     * Downloads, checks and opens the installer.
     *
     * On the ViewModel's scope, so a rotation does not restart thirty megabytes; leaving the screen
     * does cancel it, and the next tap resumes from nothing rather than from a half file — the
     * partial download is deleted, never trusted.
     */
    fun install(update: AvailableUpdate) {
        if (downloading?.isActive == true) return

        downloading = viewModelScope.launch {
            phase.value = UpdatePhase.Downloading(update, percent = 0)

            runCatching {
                updater.download(update) { percent ->
                    phase.value = UpdatePhase.Downloading(update, percent)
                }
            }
                .onSuccess { apk ->
                    phase.value = UpdatePhase.Ready(update, apk)
                    updater.install(apk)
                }
                .onFailure { phase.value = UpdatePhase.Failed(it.describe(), update) }
        }
    }

    /** The installer again, for somebody who dismissed it or locked the screen on it. */
    fun reopenInstaller(apk: File) {
        updater.install(apk)
    }

    fun setAutoCheck(isOn: Boolean) {
        viewModelScope.launch { prefs.setAutoCheck(isOn) }
    }

    private fun Throwable.describe(): String = message ?: javaClass.simpleName

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L

        fun installedCode(context: Context): Long? = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
        }
            .getOrNull()
    }
}
