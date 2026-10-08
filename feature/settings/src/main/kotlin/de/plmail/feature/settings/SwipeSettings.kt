package de.plmail.feature.settings

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.plmail.core.data.SwipeAction
import de.plmail.core.data.SwipeActions
import de.plmail.core.data.SwipeActionsRepository
import de.plmail.core.data.SwipeConfirm
import de.plmail.core.designsystem.PlMailTheme
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * What a swipe on a list row does.
 *
 * Its own view model for the reason [CalendarLauncherViewModel] gives: everything
 * [AppearanceViewModel] holds is an appearance that can follow the account, and this is a habit of
 * one hand on one device that must not.
 */
@HiltViewModel
class SwipeSettingsViewModel @Inject constructor(private val swipes: SwipeActionsRepository) :
    ViewModel() {

    val actions: StateFlow<SwipeActions> =
        swipes.actions.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = SwipeActions(),
        )

    fun setToEnd(action: SwipeAction) {
        viewModelScope.launch { swipes.setToEnd(action) }
    }

    fun setToStart(action: SwipeAction) {
        viewModelScope.launch { swipes.setToStart(action) }
    }

    fun setConfirm(confirm: SwipeConfirm) {
        viewModelScope.launch { swipes.setConfirm(confirm) }
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}

/**
 * Swipe actions, as a screen of their own.
 *
 * Not a section of Appearance, where they first went: what a swipe *does* is behaviour, and nobody
 * looking for it opens the screen about colours and fonts. The drawer's settings rows are one
 * subject each, and this is a subject.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SwipeScreen(onBack: () -> Unit, viewModel: SwipeSettingsViewModel = hiltViewModel()) {
    val actions by viewModel.actions.collectAsStateWithLifecycle()

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = PlMailTheme.colors.surface,
        topBar = {
            TopAppBar(
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = PlMailTheme.colors.surface,
                        scrolledContainerColor = PlMailTheme.colors.surface,
                        titleContentColor = PlMailTheme.colors.ink,
                        navigationIconContentColor = PlMailTheme.colors.inkSoft,
                        actionIconContentColor = PlMailTheme.colors.inkSoft,
                    ),
                title = { Text(stringResource(R.string.swipe_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
            )
        },
    ) { insets ->
        Column(
            modifier =
                Modifier.fillMaxSize()
                    .padding(insets)
                    .verticalScroll(rememberScrollState())
                    .padding(
                        horizontal = PlMailTheme.spacing.gutter,
                        vertical = PlMailTheme.spacing.medium,
                    ),
            verticalArrangement = Arrangement.spacedBy(PlMailTheme.spacing.large),
        ) {
            Directions(actions, onToEnd = viewModel::setToEnd, onToStart = viewModel::setToStart)
            Confirmation(actions.confirm, onChoose = viewModel::setConfirm)
        }
    }
}

/**
 * The two directions, each with its own menu.
 *
 * Menus rather than the row of options the confirmation below uses: there are six answers, and six
 * across a phone is "Mark read…" six times over. Named "right" and "left" for the way the thumb
 * moves, which is how anybody describes a swipe.
 */
@Composable
private fun Directions(
    actions: SwipeActions,
    onToEnd: (SwipeAction) -> Unit,
    onToStart: (SwipeAction) -> Unit,
) {
    Section(stringResource(R.string.swipe_directions)) {
        SwipeChoice(
            title = stringResource(R.string.swipe_to_end),
            chosen = actions.toEnd,
            onChoose = onToEnd,
        )
        SwipeChoice(
            title = stringResource(R.string.swipe_to_start),
            chosen = actions.toStart,
            onChoose = onToStart,
        )

        Text(
            text = stringResource(R.string.swipe_body),
            style = MaterialTheme.typography.bodySmall,
            color = PlMailTheme.colors.inkMuted,
        )
    }
}

/**
 * Whether a swipe asks first.
 *
 * The sentence underneath changes with the choice, because the three options are one word each and
 * the thing worth knowing about each is what it costs: "never" relies on the undo, "always" puts a
 * dialog between the thumb and every archive.
 */
@Composable
private fun Confirmation(chosen: SwipeConfirm, onChoose: (SwipeConfirm) -> Unit) {
    Section(stringResource(R.string.swipe_confirm)) {
        Choices(
            options = SwipeConfirm.entries,
            chosen = chosen,
            label = { stringResource(it.label()) },
            onChoose = onChoose,
        )

        Text(
            text = stringResource(chosen.body()),
            style = MaterialTheme.typography.bodySmall,
            color = PlMailTheme.colors.inkMuted,
        )
    }
}

private fun SwipeConfirm.label(): Int =
    when (this) {
        SwipeConfirm.NEVER -> R.string.swipe_confirm_never
        SwipeConfirm.TRASH -> R.string.swipe_confirm_trash
        SwipeConfirm.ALWAYS -> R.string.swipe_confirm_always
    }

private fun SwipeConfirm.body(): Int =
    when (this) {
        SwipeConfirm.NEVER -> R.string.swipe_confirm_never_body
        SwipeConfirm.TRASH -> R.string.swipe_confirm_trash_body
        SwipeConfirm.ALWAYS -> R.string.swipe_confirm_always_body
    }

@Composable
private fun SwipeChoice(title: String, chosen: SwipeAction, onChoose: (SwipeAction) -> Unit) {
    val theme = PlMailTheme.values
    val shape = RoundedCornerShape(theme.radii.control)

    var isOpen by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(theme.spacing.medium),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            color = theme.colors.inkSoft,
            modifier = Modifier.weight(1f),
        )

        Box {
            Row(
                modifier =
                    Modifier.clip(shape)
                        .clickable(role = Role.DropdownList) { isOpen = true }
                        .border(theme.spacing.hair, theme.colors.line, shape)
                        .heightIn(min = theme.spacing.touchTarget)
                        .padding(start = theme.spacing.medium, end = theme.spacing.small),
                horizontalArrangement = Arrangement.spacedBy(theme.spacing.tiny),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(chosen.label()),
                    style = MaterialTheme.typography.bodyMedium,
                    color = theme.colors.ink,
                )
                Icon(
                    imageVector = Icons.Filled.ArrowDropDown,
                    contentDescription = null,
                    tint = theme.colors.inkMuted,
                )
            }

            DropdownMenu(expanded = isOpen, onDismissRequest = { isOpen = false }) {
                SwipeAction.entries.forEach { action ->
                    DropdownMenuItem(
                        text = { Text(stringResource(action.label())) },
                        onClick = {
                            isOpen = false
                            onChoose(action)
                        },
                    )
                }
            }
        }
    }
}

private fun SwipeAction.label(): Int =
    when (this) {
        SwipeAction.NONE -> R.string.swipe_none
        SwipeAction.ARCHIVE -> R.string.swipe_archive
        SwipeAction.TRASH -> R.string.swipe_trash
        SwipeAction.READ -> R.string.swipe_read
        SwipeAction.SNOOZE -> R.string.swipe_snooze
        SwipeAction.MOVE -> R.string.swipe_move
    }
