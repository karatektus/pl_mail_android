package de.plmail.feature.mail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.plmail.core.data.ActionOutcome
import de.plmail.core.data.ActionTarget
import de.plmail.core.data.CategoryDigest
import de.plmail.core.data.Label
import de.plmail.core.data.LabelRepository
import de.plmail.core.data.MailAction
import de.plmail.core.data.MailActions
import de.plmail.core.data.MailCategory
import de.plmail.core.data.MailRepository
import de.plmail.core.data.SidebarSections
import de.plmail.core.data.SidebarSettings
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The label list the navigation draws, straight from the cache. */
@HiltViewModel
class SidebarViewModel
@Inject
constructor(
    labels: LabelRepository,
    accounts: MailRepository,
    digest: CategoryDigest,
    private val sidebar: SidebarSettings,
) : ViewModel() {

    /**
     * Recomputed when either the mailboxes or the accounts change.
     *
     * The account list is in here because a label row is a sum across accounts: an account
     * disappearing has to take its share of the unread count with it, and a list keyed only on
     * mailboxes would keep showing the total until something else happened to touch a mailbox row.
     */
    val labels: StateFlow<List<Label>> =
        combine(labels.observeLabels(), accounts.observeAccounts()) { all, _ -> all }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                initialValue = emptyList(),
            )

    /**
     * The same labels, split into the groups the drawer draws.
     *
     * Derived here rather than in the composable because the split depends on a preference, and a
     * `LazyColumn` that partitioned its own items would re-partition on every recomposition of a
     * list whose whole job is to be cheap to scroll.
     */
    val sections: StateFlow<SidebarSections> =
        sidebar
            .sections(this.labels)
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                initialValue =
                    SidebarSections(inbox = null, important = emptyList(), other = emptyList()),
            )

    /** Moves a label between the two groups. */
    fun setImportant(label: Label, important: Boolean) {
        viewModelScope.launch { sidebar.setImportant(label, important) }
    }

    /**
     * Whether this server classifies mail, so the drawer knows whether to offer the category rows.
     *
     * False to start with rather than true: the rows appear once there is evidence for them, and a
     * group that flashes in and out at every launch — before the first conversation has been read
     * back off disk — is worse than one that arrives a frame late.
     */
    val hasCategories: StateFlow<Boolean> =
        labels
            .observeHasCategories()
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                initialValue = false,
            )

    /**
     * Which categories are worth a row, as [CategoryDigest.populated] decides.
     *
     * Primary alone to begin with, which is the set that is always true: it is drawn whatever the
     * cache holds, so the drawer never opens on a gap where the inbox should be and then fills it
     * in.
     */
    val populatedCategories: StateFlow<Set<MailCategory>> =
        digest.populated.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = setOf(MailCategory.PRIMARY),
        )

    /** Which categories carry a new-mail dot. */
    val newCategories: StateFlow<Set<MailCategory>> =
        digest.hasNew.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            // Empty rather than unknown: a dot that appears a frame late is a
            // dot arriving with its evidence, and one that appears and vanishes
            // teaches people not to trust it.
            initialValue = emptySet(),
        )

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}

/** What the label dialog is doing, and what the server said about it. */
data class LabelEditorState(
    val isWorking: Boolean = false,
    val isDone: Boolean = false,
    val deleted: Label? = null,
    val error: String? = null,
)

/**
 * Create, rename and delete, against the server rather than against the cache.
 *
 * Not local-first, unlike every mail action. A created label has no id until the server assigns
 * one, and the id is what every subsequent apply is addressed to — a locally invented label could
 * be ticked onto a conversation before it existed, and there would be nothing to send.
 */
@HiltViewModel
class LabelEditorViewModel
@Inject
constructor(private val labels: LabelRepository, private val actions: MailActions) : ViewModel() {

    private val _state = MutableStateFlow(LabelEditorState())
    val state: StateFlow<LabelEditorState> = _state.asStateFlow()

    /**
     * Creates a label — and, when the dialog was opened from "Label as", puts it on the mail that
     * was being labelled.
     *
     * Somebody who taps "New label" under a list of labels they were choosing from is still
     * labelling that mail; a dialog that made the label and went back to the list having applied
     * nothing left them to open the sheet again and find the thing they had just made.
     *
     * Created in the account the mail is in rather than in the primary one, because a label is
     * applied through its binding and a label made somewhere else has none there. A selection
     * across two accounts gets it in both, one account at a time, and each account's mail gets the
     * label that account made — which is one label wherever the server collapses them, and two
     * correctly-applied ones where it does not.
     *
     * A refusal to apply is reported in the dialog like any other failure, though the label then
     * exists: the user asked for two things and should hear that one of them did not happen.
     */
    fun create(name: String, color: String?, applyTo: List<ActionTarget> = emptyList()) = work {
        if (applyTo.isEmpty()) {
            labels.create(name.trim(), color)

            return@work
        }

        applyTo
            .groupBy { it.accountKey }
            .forEach { (accountKey, targets) ->
                val created = labels.createIn(accountKey, name.trim(), color)
                val label =
                    labels.observeLabels().first().firstOrNull { label ->
                        label.bindings.any {
                            it.accountKey == accountKey && it.mailboxId == created.value
                        }
                    } ?: error("The label was created but has not reached this device yet.")

                val outcome = actions.apply(MailAction.SetLabel(label, applied = true), targets)

                if (outcome is ActionOutcome.Rejected) error(outcome.reason)
            }
    }

    /**
     * Rename and recolour in one patch.
     *
     * The name is withheld for a label the server will not rename. Colour is the *one* property
     * `Mailbox/set` accepts on a system label — Inbox may be recoloured, never renamed — and
     * sending an unchanged `name` alongside it would be refused with `forbidden` for the whole
     * patch, silently taking the colour with it.
     */
    fun save(label: Label, name: String, color: String?) = work {
        labels.update(label, name.trim().takeIf { label.mayRename }, color)
    }

    fun delete(label: Label, all: List<Label>) = work(deleted = label) { labels.delete(label, all) }

    fun acknowledge() {
        _state.value = LabelEditorState()
    }

    private fun work(deleted: Label? = null, block: suspend () -> Unit) {
        _state.value = LabelEditorState(isWorking = true)

        viewModelScope.launch {
            runCatching { block() }
                .onSuccess { _state.value = LabelEditorState(isDone = true, deleted = deleted) }
                .onFailure {
                    _state.value = LabelEditorState(error = it.message ?: "That did not work.")
                }
        }
    }
}
