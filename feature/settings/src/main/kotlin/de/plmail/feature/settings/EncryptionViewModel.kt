package de.plmail.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.plmail.core.data.EncryptionRepository
import de.plmail.core.data.EncryptionStatus
import de.plmail.core.data.UnlockResult
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The end-to-end encryption screen (#9): whether this device holds keys, and getting them here.
 *
 * The status is read when the screen opens and after every change rather than observed, because it
 * is a question for the server — which address keys exist, which the keys here open — and the
 * server says nothing when the web app changes them.
 */
@HiltViewModel
class EncryptionViewModel @Inject constructor(private val encryption: EncryptionRepository) :
    ViewModel() {

    private val _state = MutableStateFlow(EncryptionUiState())
    val state: StateFlow<EncryptionUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(isWorking = true) }
            val status = encryption.status()
            _state.update { it.copy(status = status, isWorking = false) }
        }
    }

    fun secretChanged(secret: String) {
        _state.update { it.copy(secret = secret, problem = null) }
    }

    /**
     * Opens the keyring with what was typed.
     *
     * The field is cleared whatever the outcome: a password left in a text field is a password left
     * in memory and on the screen, and typing it again after a mistake costs a second.
     */
    fun unlock() {
        val secret = _state.value.secret
        if (secret.isEmpty() || _state.value.isWorking) return

        viewModelScope.launch {
            _state.update { it.copy(isWorking = true, secret = "", problem = null) }

            val problem =
                when (val result = encryption.unlock(secret)) {
                    UnlockResult.Unlocked -> null
                    UnlockResult.WrongSecret -> UnlockProblem.WrongSecret
                    UnlockResult.NotSetUp -> UnlockProblem.NotSetUp
                    is UnlockResult.Failed -> UnlockProblem.Unreachable
                }

            val status = encryption.status()
            _state.update { it.copy(status = status, isWorking = false, problem = problem) }
        }
    }

    fun forget() {
        viewModelScope.launch {
            _state.update { it.copy(isWorking = true) }
            encryption.forget()
            val status = encryption.status()
            _state.update { it.copy(status = status, isWorking = false) }
        }
    }
}

data class EncryptionUiState(
    /** Null until the first answer. */
    val status: EncryptionStatus? = null,
    val isWorking: Boolean = false,
    /** What is being typed into the password field. Never logged, cleared on every attempt. */
    val secret: String = "",
    val problem: UnlockProblem? = null,
) {
    override fun toString(): String =
        "EncryptionUiState(status=$status, isWorking=$isWorking, problem=$problem)"
}

enum class UnlockProblem {
    WrongSecret,
    NotSetUp,
    Unreachable,
}
