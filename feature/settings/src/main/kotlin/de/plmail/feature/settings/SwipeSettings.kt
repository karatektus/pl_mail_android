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
import de.plmail.core.data.canBeConfirmed
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

    fun setConfirmToEnd(asks: Boolean) {
        viewModelScope.launch { swipes.setConfirmToEnd(asks) }
    }

    fun setConfirmToStart(asks: Boolean) {
        viewModelScope.launch { swipes.setConfirmToStart(asks) }
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
            Direction(
                title = stringResource(R.string.swipe_to_end),
                chosen = actions.toEnd,
                asks = actions.confirmToEnd,
                onChoose = viewModel::setToEnd,
                onAsk = viewModel::setConfirmToEnd,
            )
            Direction(
                title = stringResource(R.string.swipe_to_start),
                chosen = actions.toStart,
                asks = actions.confirmToStart,
                onChoose = viewModel::setToStart,
                onAsk = viewModel::setConfirmToStart,
            )

            Text(
                text = stringResource(R.string.swipe_body),
                style = MaterialTheme.typography.bodySmall,
                color = PlMailTheme.colors.inkMuted,
            )
        }
    }
}

/**
 * One direction: what it does, and whether it asks first.
 *
 * A menu for the action rather than a row of options: there are six answers, and six across a phone
 * is "Mark read…" six times over. Named "right" and "left" for the way the thumb moves, which is
 * how anybody describes a swipe.
 *
 * The switch belongs to the direction and not to the screen, because the two directions are not the
 * same gesture to the person making it — the side they archive with all day is not the side they
 * want a question on. It is offered for every action, snooze and move included: the list those open
 * is not a question to somebody who wants to be asked. Only "nothing" has nothing to ask, and there
 * the switch stays where it is and says why instead of vanishing — a control that comes and goes
 * with a menu above it reads as the screen being broken.
 */
@Composable
private fun Direction(
    title: String,
    chosen: SwipeAction,
    asks: Boolean,
    onChoose: (SwipeAction) -> Unit,
    onAsk: (Boolean) -> Unit,
) {
    Section(title) {
        SwipeChoice(
            title = stringResource(R.string.swipe_action),
            chosen = chosen,
            onChoose = onChoose,
        )

        Toggle(
            title = stringResource(R.string.swipe_confirm),
            body =
                stringResource(
                    when {
                        !chosen.canBeConfirmed -> R.string.swipe_confirm_not_needed
                        asks -> R.string.swipe_confirm_on_body
                        else -> R.string.swipe_confirm_off_body
                    }
                ),
            isOn = asks && chosen.canBeConfirmed,
            onChange = onAsk,
            isEnabled = chosen.canBeConfirmed,
        )
    }
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
