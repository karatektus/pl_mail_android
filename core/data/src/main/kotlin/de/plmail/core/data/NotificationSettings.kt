package de.plmail.core.data

import de.plmail.core.datastore.NotificationPrefsStore
import javax.inject.Inject
import javax.inject.Singleton
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
 */
data class NotifiableScopes(
    val categories: List<NotifiableScope.Category> = emptyList(),
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
constructor(private val labels: LabelRepository, private val store: NotificationPrefsStore) {

    val scopes: Flow<NotifiableScopes> =
        combine(labels.observeLabels(), labels.observeHasCategories(), store.prefs) {
            all,
            hasCategories,
            prefs ->
            NotifiableScopes(
                categories =
                    if (!hasCategories) emptyList()
                    else
                        MailCategory.entries.map {
                            NotifiableScope.Category(
                                category = it,
                                isEnabled = prefs.allows(NotifyScope.Category(it).key),
                            )
                        },
                labels =
                    all.filter { it.role !in NEVER_NOTIFIABLE_ROLES }
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
     */
    suspend fun setEnabled(key: String, enabled: Boolean) {
        store.setEnabled(key, enabled)
    }
}
