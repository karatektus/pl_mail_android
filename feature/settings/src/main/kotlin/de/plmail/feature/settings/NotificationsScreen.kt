package de.plmail.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.plmail.core.data.MailCategory
import de.plmail.core.data.NotifiableScope
import de.plmail.core.designsystem.PaneTone
import de.plmail.core.designsystem.PlMailDivider
import de.plmail.core.designsystem.PlMailPane
import de.plmail.core.designsystem.PlMailTheme

/**
 * Which mail is allowed to interrupt.
 *
 * **Off by default, with one exception, and the screen has to make that legible.** Primary is on
 * and everything else is off — Gmail's arrangement, and the reason somebody installs a mail client
 * and does not immediately uninstall it. A screen of switches that are nearly all off looks broken
 * unless it says why, which is what the note at the top is for.
 *
 * Two groups, because the app already has two kinds of list and they answer different questions.
 * The categories narrow the inbox; the labels are the user's own filing, and switching one on
 * covers mail a server-side rule filed there without ever putting it in the inbox. A message under
 * both still arrives once — that is settled in `:core:data` rather than here, because a rule about
 * how many notifications an email produces should not be able to change when somebody redraws a
 * list.
 *
 * The category group is absent on a server that does not classify mail, exactly as the sidebar's
 * is: five switches that can never match anything are worse than none. The default still holds
 * there, because everything in the inbox counts as Primary when nothing has been classified.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationsScreen(onBack: () -> Unit, viewModel: NotificationsViewModel = hiltViewModel()) {
    val scopes by viewModel.scopes.collectAsStateWithLifecycle()

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
                title = { Text(stringResource(R.string.notifications_title)) },
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
            Note(stringResource(R.string.notifications_explains), PaneTone.INFO)

            if (scopes.categories.isNotEmpty()) {
                Section(stringResource(R.string.notifications_categories)) {
                    Text(
                        text = stringResource(R.string.notifications_categories_explains),
                        style = MaterialTheme.typography.bodySmall,
                        color = PlMailTheme.colors.inkMuted,
                    )
                }

                scopes.categories.forEachIndexed { index, scope ->
                    ScopeRow(
                        name = scope.category.displayName(),
                        detail = scope.category.explains(),
                        isEnabled = scope.isEnabled,
                        onEnabled = { viewModel.setEnabled(scope.key, it) },
                    )

                    if (index != scopes.categories.lastIndex) PlMailDivider()
                }
            }

            Section(stringResource(R.string.notifications_labels)) {
                Text(
                    text = stringResource(R.string.notifications_labels_explains),
                    style = MaterialTheme.typography.bodySmall,
                    color = PlMailTheme.colors.inkMuted,
                )
            }

            if (scopes.labels.isEmpty()) {
                Text(
                    text = stringResource(R.string.notifications_no_labels),
                    style = MaterialTheme.typography.bodyMedium,
                    color = PlMailTheme.colors.inkMuted,
                )
            }

            scopes.labels.forEachIndexed { index, scope ->
                LabelRow(scope) { viewModel.setEnabled(scope.key, it) }

                if (index != scopes.labels.lastIndex) PlMailDivider()
            }
        }
    }
}

/**
 * One label's switch.
 *
 * The **path** rather than the leaf name, because two labels called "Invoices" under two parents
 * are two rows that would otherwise be indistinguishable — and this is a screen where picking the
 * wrong one means silence rather than a visible mistake.
 */
@Composable
private fun LabelRow(scope: NotifiableScope.Labelled, onEnabled: (Boolean) -> Unit) {
    ScopeRow(
        name = scope.label.path,
        detail = null,
        isEnabled = scope.isEnabled,
        onEnabled = onEnabled,
    )
}

/**
 * A name, an optional sentence, and the switch.
 *
 * The switch carries the whole row's semantics and the label is cleared out of the accessibility
 * tree, exactly as the accounts screen does it: leaving both makes TalkBack read the same setting
 * twice and offers a swipe stop that does nothing. The switch's own description names the scope,
 * which is the part that differs between rows.
 */
@Composable
private fun ScopeRow(
    name: String,
    detail: String?,
    isEnabled: Boolean,
    onEnabled: (Boolean) -> Unit,
) {
    // Read out of composition, because a semantics block is not a composable
    // scope and cannot reach a string resource from inside itself.
    val description = stringResource(R.string.notifications_scope_a11y, name)

    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = PlMailTheme.spacing.small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).clearAndSetSemantics {}) {
            Text(
                text = name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = PlMailTheme.colors.ink,
            )

            detail?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = PlMailTheme.colors.inkMuted,
                )
            }
        }

        Switch(
            checked = isEnabled,
            onCheckedChange = onEnabled,
            colors =
                SwitchDefaults.colors(
                    checkedThumbColor = PlMailTheme.colors.onAccent,
                    checkedTrackColor = PlMailTheme.colors.accent,
                    uncheckedTrackColor = PlMailTheme.colors.sunken,
                    uncheckedBorderColor = PlMailTheme.colors.line,
                ),
            modifier = Modifier.semantics { contentDescription = description },
        )
    }
}

/**
 * The categories, named in the user's language.
 *
 * Named here rather than carried on [MailCategory], which holds the *wire* token and must keep
 * holding only that: the token is compared against what the server sent, and a display name on the
 * same enum is how somebody eventually translates one.
 */
@Composable
private fun MailCategory.displayName(): String =
    when (this) {
        MailCategory.PRIMARY -> stringResource(R.string.notifications_category_primary)
        MailCategory.SOCIAL -> stringResource(R.string.notifications_category_social)
        MailCategory.PROMOTIONS -> stringResource(R.string.notifications_category_promotions)
        MailCategory.UPDATES -> stringResource(R.string.notifications_category_updates)
        MailCategory.FORUMS -> stringResource(R.string.notifications_category_forums)
    }

/** Only Primary has anything to add, and what it has to add is the thing people get wrong. */
@Composable
private fun MailCategory.explains(): String? =
    when (this) {
        MailCategory.PRIMARY -> stringResource(R.string.notifications_category_primary_explains)
        else -> null
    }

/** A short block of prose carrying a tone. Local twin of the accounts screen's, same shape. */
@Composable
private fun Note(text: String, tone: PaneTone) {
    PlMailPane(modifier = Modifier.fillMaxWidth(), tone = tone) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = PlMailTheme.colors.inkSoft,
            modifier = Modifier.padding(PlMailTheme.spacing.small),
        )
    }
}
