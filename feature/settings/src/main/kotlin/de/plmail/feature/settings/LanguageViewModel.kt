package de.plmail.feature.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import de.plmail.core.data.AppLanguage
import de.plmail.core.data.AppLanguages
import javax.inject.Inject

/**
 * The language the app is drawn in.
 *
 * **Its own view model rather than a field on [AppearanceViewModel]**, and the reason is the same
 * one [CalendarLauncherViewModel] gives: everything the appearance view model holds is an account
 * preference that arrives from the server and goes back to every device, and this is a fact about
 * *this* phone that is never sent anywhere. Putting a value that cannot sync inside the object
 * whose whole contract is that it does is how the two eventually get confused for each other.
 *
 * **Nothing is stored here and there is no flow**, for the reason [AppLanguages] gives. From API 33
 * the per-app language belongs to the platform and is editable from Android's own settings, so the
 * honest shape is a value read on demand plus a [refresh] the screen calls when it comes back into
 * view — the same shape, and for the same reason, as the calendar launcher icon.
 */
@HiltViewModel
class LanguageViewModel @Inject constructor(private val languages: AppLanguages) : ViewModel() {

    /**
     * Whether the platform re-creates the screen itself once a language is chosen.
     *
     * Read once: it is a question about this device's API level and cannot change while the process
     * lives. The screen needs it because below API 33 nothing else will apply the choice — see
     * [Language].
     */
    val isAppliedBySystem: Boolean = languages.isAppliedBySystem

    /**
     * Read at construction rather than on first composition, so the control is right on the frame
     * it appears on rather than drawing a default and then correcting itself.
     *
     * Null is a language this build does not ship, and the control then shows nothing selected. See
     * [AppLanguage.of] for why that is the truth rather than a gap.
     */
    var chosen by mutableStateOf(languages.current())
        private set

    /**
     * Re-asks the platform.
     *
     * Called when the screen resumes, because from API 33 this app is not the only thing that can
     * change the answer: Settings → Apps → plMail → Language writes the same value, and a user who
     * came back from there would otherwise find this control still showing what they had left.
     */
    fun refresh() {
        chosen = languages.current()
    }

    /**
     * Writes and re-reads, rather than assuming the write took.
     *
     * The same rule the appearance controls follow — write immediately, no apply button — with the
     * difference [CalendarLauncherViewModel.setEnabled] names: what is written goes to the system,
     * so what is shown afterwards is the system's answer and not the request.
     */
    fun choose(language: AppLanguage) {
        languages.choose(language)
        refresh()
    }
}
