package de.plmail.feature.mail.reader

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import de.plmail.core.designsystem.PlMailTheme
import de.plmail.feature.mail.R

/**
 * One message exactly as it arrived.
 *
 * A full-screen dialog rather than a navigation destination: the source is a thing you glance at
 * and dismiss, and making it a route would put it in the back stack between the conversation and
 * the list. It is also the only screen in this app that deliberately does not wrap — headers are
 * line-structured and folding them at the device width turns a `Received:` chain into something
 * nobody can follow, which is the one thing anybody opens this view for.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MessageSourceSheet(source: MessageSource, onClose: () -> Unit, onShare: () -> Unit) {
    Dialog(
        onDismissRequest = onClose,
        properties =
            DialogProperties(
                // No platform width limit: a dialog Android sizes for a message
                // box would show forty characters of a header line.
                usePlatformDefaultWidth = false,
                // And no inset fitting either, or the dialog window stops short
                // of the system bars and the scrim behind it shows through as a
                // grey band at the top and bottom of an otherwise full screen.
                // The Scaffold below applies the insets to the content instead,
                // which is where they belong.
                decorFitsSystemWindows = false,
            ),
    ) {
        // A dialog gets its own Window, and it does not inherit the activity's
        // status-bar appearance. Without this the icons stay in whatever the
        // activity last set and the light scheme draws white glyphs on a
        // near-white bar -- invisible, and only in this one view, which is
        // exactly the kind of thing that ships.
        val view = LocalView.current
        val isDark = PlMailTheme.colors.isDark

        LaunchedEffect(view, isDark) {
            (view.parent as? DialogWindowProvider)?.window?.let { window ->
                WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !isDark
            }
        }

        Surface(color = PlMailTheme.colors.surface, modifier = Modifier.fillMaxSize()) {
            Scaffold(
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
                        title = {
                            Text(
                                text = source.title,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        navigationIcon = {
                            IconButton(onClick = onClose) {
                                Icon(
                                    imageVector = Icons.Outlined.Close,
                                    contentDescription = stringResource(R.string.source_close),
                                )
                            }
                        },
                        actions = {
                            // Only once there is something to share. A share
                            // sheet over a download that has not finished hands
                            // the other app an empty file.
                            if (source.text != null) {
                                IconButton(onClick = onShare) {
                                    Icon(
                                        imageVector = Icons.Outlined.Share,
                                        contentDescription = stringResource(R.string.source_share),
                                    )
                                }
                            }
                        },
                    )
                },
            ) { insets ->
                Box(modifier = Modifier.fillMaxSize().padding(insets)) {
                    when (val text = source.text) {
                        null ->
                            Column(
                                modifier = Modifier.fillMaxSize(),
                                verticalArrangement =
                                    Arrangement.spacedBy(0.dp, Alignment.CenterVertically),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                CircularProgressIndicator(
                                    color = PlMailTheme.colors.accent,
                                    strokeWidth = 2.dp,
                                    modifier = Modifier.size(24.dp),
                                )
                            }

                        else ->
                            Text(
                                text = text.ifBlank { stringResource(R.string.source_empty) },
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = PlMailTheme.colors.inkSoft,
                                softWrap = false,
                                modifier =
                                    Modifier.fillMaxWidth()
                                        .verticalScroll(rememberScrollState())
                                        .horizontalScroll(rememberScrollState())
                                        .padding(PlMailTheme.spacing.gutter),
                            )
                    }
                }
            }
        }
    }
}
