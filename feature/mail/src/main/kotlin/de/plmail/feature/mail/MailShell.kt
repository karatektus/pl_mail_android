package de.plmail.feature.mail

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.PermanentDrawerSheet
import androidx.compose.material3.PermanentNavigationDrawer
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.window.core.layout.WindowSizeClass
import de.plmail.core.data.MailView
import de.plmail.core.designsystem.PlMailTheme
import kotlinx.coroutines.launch

/**
 * The app's navigation frame: the label list beside, or behind, the mail.
 *
 * A drawer rather than a bottom bar, and that changed with M9. Three destinations fitted a bottom
 * bar; a label list does not — it is as long as the user made it, and it grows. The presentation
 * still adapts: modal and reached from the app bar where the window is narrow, permanently open
 * where there is room for it beside two panes.
 *
 * Inside it sits [MailPane], which owns the list/detail split independently. The two adapt on
 * different axes and must not be conflated: a tablet shows the sidebar *and* both panes, a phone
 * shows a drawer over one pane at a time.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MailShell(
    onSearch: () -> Unit,
    onPush: () -> Unit,
    onNotifications: () -> Unit,
    onDiagnostics: () -> Unit,
    onAppearance: () -> Unit,
    onAccounts: () -> Unit,
    /** Null where this install has no calendar. See [LabelSidebar]. */
    onCalendar: (() -> Unit)?,
    onCompose: () -> Unit,
    onReply: (accountKey: String, emailId: String, all: Boolean) -> Unit,
    onForward: (accountKey: String, emailId: String) -> Unit,
    /** A conversation to open straight away, from a notification tap. */
    openThread: ThreadTarget? = null,
    onThreadOpened: () -> Unit = {},
    viewModel: SidebarViewModel = hiltViewModel(),
) {
    val labels by viewModel.labels.collectAsStateWithLifecycle()
    val hasCategories by viewModel.hasCategories.collectAsStateWithLifecycle()

    // The key rather than the MailView, because a Labelled view carries a Label,
    // and a Label carries its bindings and its counts -- both of which change
    // under it on every sync. Saving the key and resolving it back is what keeps
    // the selection through a process death pointing at the same destination
    // rather than at a stale copy of it.
    var selectedKey by rememberSaveable { mutableStateOf<String?>(null) }
    var editing by rememberSaveable(stateSaver = LabelEditorSaver) { mutableStateOf(null) }

    val selected = MailView.restore(selectedKey, labels)

    val isWide =
        currentWindowAdaptiveInfo()
            .windowSizeClass
            .isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND)

    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    val sidebar =
        @Composable {
            LabelSidebar(
                labels = labels,
                showCategories = hasCategories,
                selected = selected,
                onSelect = { view ->
                    selectedKey = view.toKey()
                    scope.launch { drawer.close() }
                },
                onCreate = {
                    editing = LabelEditorRequest.New
                    scope.launch { drawer.close() }
                },
                onPush = {
                    scope.launch { drawer.close() }
                    onPush()
                },
                onNotifications = {
                    scope.launch { drawer.close() }
                    onNotifications()
                },
                onDiagnostics = {
                    // Closed first, so returning from diagnostics does not come
                    // back to an open drawer over the mail the user was reading.
                    scope.launch { drawer.close() }
                    onDiagnostics()
                },
                onAppearance = {
                    scope.launch { drawer.close() }
                    onAppearance()
                },
                onAccounts = {
                    scope.launch { drawer.close() }
                    onAccounts()
                },
                onCalendar =
                    onCalendar?.let { open ->
                        {
                            scope.launch { drawer.close() }
                            open()
                        }
                    },
            )
        }

    val content =
        @Composable {
            MailPane(
                view = selected,
                // Null where the sidebar is already on screen: a hamburger that
                // opens something already open is a control that does nothing.
                onOpenSidebar = if (isWide) null else ({ scope.launch { drawer.open() } }),
                onEditLabel = { editing = LabelEditorRequest.Edit(it.key) },
                onCreateLabel = { editing = LabelEditorRequest.New },
                onSearch = onSearch,
                // The callback as it arrived, without the drawer-closing wrapper
                // the sidebar's copy carries: there is no drawer open when the
                // top bar is being tapped, and closing a closed drawer on a
                // tablet's permanent one is a request to hide the navigation.
                // Same destination, same mechanism -- :app flips one flag either
                // way, so back from the calendar lands on the list whichever
                // entry was used.
                onCalendar = onCalendar,
                onCompose = onCompose,
                onReply = onReply,
                onForward = onForward,
                openThread = openThread,
                onThreadOpened = onThreadOpened,
            )
        }

    if (isWide) {
        PermanentNavigationDrawer(
            drawerContent = {
                PermanentDrawerSheet(
                    drawerContainerColor = PlMailTheme.colors.surface,
                    modifier = Modifier.width(SIDEBAR_WIDTH).fillMaxHeight(),
                ) {
                    sidebar()
                }
            },
            content = content,
        )
    } else {
        // Closing the drawer is a navigation step of its own. Without this, back
        // on an open drawer leaves the screen entirely and the drawer is still
        // open when the user comes back.
        BackHandler(enabled = drawer.isOpen) { scope.launch { drawer.close() } }

        ModalNavigationDrawer(
            drawerState = drawer,
            drawerContent = {
                ModalDrawerSheet(
                    drawerState = drawer,
                    drawerContainerColor = PlMailTheme.colors.surface,
                    modifier = Modifier.width(SIDEBAR_WIDTH),
                ) {
                    sidebar()
                }
            },
            content = content,
        )
    }

    editing?.let { request ->
        LabelEditor(
            request = request,
            labels = labels,
            onDismiss = { editing = null },
            onDeleted = { deleted ->
                // Back to the inbox rather than to a label that no longer
                // exists -- otherwise the list keeps paging a mailbox the server
                // has forgotten and reports it as an unreachable account.
                if (selected == MailView.Labelled(deleted)) selectedKey = null
                editing = null
            },
        )
    }
}

/**
 * How wide the sidebar is.
 *
 * Fixed rather than a fraction. Material's default drawer is 360dp, which on a 1280dp tablet leaves
 * the list pane too narrow to hold a subject; 280dp is enough for `Work/Invoices` and a count.
 */
private val SIDEBAR_WIDTH = 280.dp
