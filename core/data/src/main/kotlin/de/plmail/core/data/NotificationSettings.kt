package de.plmail.core.data

import de.plmail.core.datastore.NotificationPrefsStore
import de.plmail.jmap.methods.NotificationSettingsGet
import de.plmail.jmap.methods.NotificationSettingsSet
import de.plmail.jmap.protocol.Capability
import de.plmail.jmap.protocol.RequestBuilder
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * One switch on the notification screen.
 *
 * Two shapes rather than a single row type with nullable halves, because the screen genuinely draws
 * them differently: a category is named by this app in the user's language and has no colour, and a
 * label is named by the server, carries the user's colour token, and may be nested. Flattening them
 * would put a `Label` with no bindings in front of code that assumes one — the mistake [MailView]
 * documents at length and this file has no reason to repeat.
 */
sealed interface NotifiableScope {

    val key: String
    val isEnabled: Boolean

    data class Category(val category: MailCategory, override val isEnabled: Boolean) :
        NotifiableScope {
        override val key: String
            get() = NotifyScope.Category(category).key
    }

    /**
     * One of the inbox tabs the reader made on the web, drawn among the categories in the strip's
     * own order and named by the name they gave it. See [NotifyScope.Tab] for why it starts off.
     */
    data class Tab(val tab: MailTab, override val isEnabled: Boolean) : NotifiableScope {
        override val key: String
            get() = NotifyScope.Tab(tab.key).key
    }

    data class Labelled(val label: Label, override val isEnabled: Boolean) : NotifiableScope {
        override val key: String
            get() = NotifyScope.Labelled(label.key).key
    }
}

/**
 * What the notification screen draws, in the order it draws it.
 *
 * [categories] is empty on a server that does not classify mail, which is the same signal the
 * sidebar uses to decide whether the category group exists at all — see
 * [LabelRepository.observeHasCategories]. Five permanently meaningless switches would be worse than
 * none, and on such a server the default still works: everything in the inbox counts as Primary, so
 * the Inbox label switch is the honest control and the categories say nothing they cannot keep.
 *
 * The two are **mutually exclusive**, and that is what stops the screen contradicting itself. The
 * categories and the Inbox label describe one body of mail partitioned two ways, and scopes are
 * matched with `any` — so offering both put a switch on the screen that silently overrode the five
 * above it, and turning Promotions off could not take back what turning Inbox on had granted. Where
 * the categories are drawn they are the inbox's controls and Inbox is not listed; where they are
 * not, Inbox is.
 */
data class NotifiableScopes(
    /**
     * The inbox's switches, one per tab in the strip's order: the built-in categories the reader
     * has on, and their own tabs among them. A built-in they switched off on the web is not listed
     * — its mail is Primary's there, and so it is here.
     */
    val categories: List<NotifiableScope> = emptyList(),
    val labels: List<NotifiableScope.Labelled> = emptyList(),
)

/**
 * Which lists of mail may interrupt, as the settings screen sees them.
 *
 * The join of three sources — the labels this device has synced, whether the server classifies mail
 * at all, and what the user has switched — and the only place that knows the defaults are not
 * uniform. Everything downstream reads a plain `isEnabled`, so no screen and no test has to
 * remember that Primary starts on and a label created yesterday starts off.
 */
@Singleton
class NotificationSettingsRepository
@Inject
constructor(
    private val labels: LabelRepository,
    private val store: NotificationPrefsStore,
    private val tabs: InboxTabRepository,
    private val clients: AccountClients,
) {

    val scopes: Flow<NotifiableScopes> =
        combine(labels.observeLabels(), labels.observeHasCategories(), tabs.strip, store.prefs) {
            all,
            hasCategories,
            strip,
            prefs ->
            NotifiableScopes(
                // `hasCategories` is false on one list as well as on a server
                // that classifies nothing, and both get the Inbox switch instead.
                categories =
                    if (!hasCategories) emptyList()
                    else
                        strip.map { tab ->
                            val enabled = prefs.allows(NotifyScope.ofTab(tab.key).key)

                            tab.builtIn?.let { NotifiableScope.Category(it, enabled) }
                                ?: NotifiableScope.Tab(tab, enabled)
                        },
                labels =
                    all.filter { it.role !in NEVER_NOTIFIABLE_ROLES }
                        // The inbox is covered by the five switches above it
                        // wherever those are drawn, and a scope the announcing
                        // path no longer raises must not keep a switch here: a
                        // control that does nothing is worse than a missing one.
                        .filter { !(hasCategories && it.role == INBOX_ROLE) }
                        .map {
                            NotifiableScope.Labelled(
                                label = it,
                                isEnabled = prefs.allows(NotifyScope.Labelled(it.key).key),
                            )
                        },
            )
        }

    /**
     * Records one switch.
     *
     * Takes the key rather than the scope, because the screen already holds it and round-tripping a
     * `Label` through here would mean this method having an opinion about a label that was deleted
     * between the draw and the tap.
     *
     * Where the server keeps the switches (pl_mail#369) the change is written there, for every
     * device of the user, and stored here only once the server has taken it: a switch that showed
     * on while the server still had it off would be a phone that disagrees with the browser it was
     * meant to match. A write that fails leaves the switch where it was, and the screen shows that.
     */
    suspend fun setEnabled(key: String, enabled: Boolean) {
        val server = store.current().server ?: return store.setEnabled(key, enabled)

        val scopes = if (enabled) server + key else server - key

        try {
            val client = clients.current() ?: return
            val request = RequestBuilder(using = Capability.USING_NOTIFICATIONS)
            val set = request.add(NotificationSettingsSet(scopes.toList()))

            if (client.send(request).result(set).refusal == null) store.setServer(scopes)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            // See the docblock: the switch stays where the server has it.
        }
    }

    /**
     * Reads the user's switches from the server, where it keeps them.
     *
     * Called beside [AppearanceRepository.refresh] — on sync and on coming to the foreground — and
     * when the notification screen opens, for the same reason: the list has no push and no
     * `/changes`, so a box ticked in a browser reaches the phone the next time it looks. Failures
     * are swallowed; the last list read stays in force, which is the right answer for a phone that
     * is offline.
     *
     * A server without the extension clears the stored list, and the phone's own switches apply
     * again exactly as they were.
     */
    suspend fun refresh() {
        try {
            val client = clients.current() ?: return

            if (!client.session().hasNotificationSettings) {
                store.clearServer()
                return
            }

            val request = RequestBuilder(using = Capability.USING_NOTIFICATIONS)
            val get = request.add(NotificationSettingsGet())

            client.send(request).result(get).settings?.let { store.setServer(it.scopes) }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            // See the docblock.
        }
    }

    private companion object {
        const val INBOX_ROLE = "inbox"
    }
}
