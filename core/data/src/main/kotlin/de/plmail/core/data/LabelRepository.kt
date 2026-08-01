package de.plmail.core.data

import de.plmail.core.database.PlMailDatabase
import de.plmail.jmap.methods.MailboxGet
import de.plmail.jmap.methods.MailboxPatch
import de.plmail.jmap.methods.MailboxSet
import de.plmail.jmap.methods.NewMailbox
import de.plmail.jmap.protocol.AccountId
import de.plmail.jmap.protocol.MailboxId
import de.plmail.jmap.protocol.RequestBuilder
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * The label list, and the four things that can be done to it.
 *
 * Reads come from the cache and are therefore instant and offline-capable; writes go straight to
 * the server and re-read that account's mailboxes afterwards. That asymmetry is deliberate and is
 * not the local-first pattern the mail actions use: creating a label is not an operation with an
 * obvious local answer, because the id the server assigns is what every subsequent apply has to be
 * addressed to. Guessing one and reconciling later would mean a label the user could tick before it
 * existed.
 */
@Singleton
class LabelRepository
@Inject
constructor(
    private val database: PlMailDatabase,
    private val clients: AccountClients,
    private val mail: MailRepository,
    private val accounts: AccountsRepository,
) : KnownLabels {

    /** Every label the user has, collapsed across accounts and in sidebar order. */
    fun observeLabels(): Flow<List<Label>> = database.mailboxes().observeAll().map { it.asLabels() }

    /**
     * Whether this server classifies mail into inbox categories, so far as this device can tell.
     *
     * There is no capability to ask for: the category is a plMail extension on `Thread` rather than
     * a `using` URN, so the only honest signal is whether any synced conversation carries one. That
     * makes the sidebar's category group appear on a server that has the feature and stay away on
     * one that does not, which is the behaviour that matters — five permanently empty destinations
     * would be worse than none, and this app has to keep working against a plMail older than the
     * extension.
     */
    fun observeHasCategories(): Flow<Boolean> = database.threads().observeHasCategories()

    /**
     * The same list, once, keyed for lookup.
     *
     * What the outbox resolves a stored label key against when it drains — see [KnownLabels] for
     * why the queue holds this one method rather than the whole repository.
     */
    override suspend fun byKey(): Map<String, Label> =
        observeLabels().first().associateBy { it.key }

    /**
     * Which of [labels] are on every one of [targets], and which are on some of them.
     *
     * Two sets rather than one, because the sheet has three states to draw and "on some" is the
     * interesting one: ticking a partially-applied label must add it everywhere rather than toggle
     * it off, and a two-state checkbox cannot express the difference between "none of these five
     * conversations" and "three of these five".
     */
    suspend fun appliedTo(labels: List<Label>, targets: List<ActionTarget>): LabelSelection {
        if (targets.isEmpty()) return LabelSelection()

        val perTarget = targets.map { target ->
            val bound =
                database
                    .emails()
                    .inThread(target.accountKey, target.threadId)
                    .flatMap { it.mailboxIds.split(",") }
                    .filter { it.isNotBlank() }
                    .toSet()

            labels
                .filter { label ->
                    label.bindings.any {
                        it.accountKey == target.accountKey && it.mailboxId in bound
                    }
                }
                .map { it.key }
                .toSet()
        }

        val all = perTarget.reduce { a, b -> a intersect b }
        val any = perTarget.reduce { a, b -> a union b }

        return LabelSelection(onAll = all, onSome = any - all)
    }

    /**
     * Creates a label in the first account.
     *
     * One account rather than all of them. A label is user-scoped on the server, so creating it
     * once is enough for it to exist — but the *binding* that makes it usable is per account, and a
     * client cannot create a binding it was not given. Fanning the create out across accounts
     * instead would risk creating several distinct labels that merely share a name, which is the
     * exact failure collapsing on `labelId` exists to prevent.
     *
     * The account is the first in the user's own order rather than one the dialog asks about,
     * because for almost everyone there is only one and the question would be noise. With several,
     * this is the limitation to revisit.
     */
    suspend fun create(name: String, color: String? = null, parent: Label? = null): MailboxId {
        // The user's order, not the session's. `sortIndex` is the server's
        // answer and the arrows on the accounts screen do not touch it — the
        // whole point of that screen is that "my main mailbox" is a decision
        // this device holds, and creating a label somewhere else would be the
        // most visible way of ignoring it.
        val accountKey = accounts.primary()?.uid ?: error(NO_ACCOUNT)

        return createIn(accountKey, name, color, parent)
    }

    suspend fun createIn(
        accountKey: String,
        name: String,
        color: String? = null,
        parent: Label? = null,
    ): MailboxId {
        val account = database.accounts().byUid(accountKey) ?: error(NO_ACCOUNT)
        val client = clients.forAccount(accountKey) ?: error(NO_ACCOUNT)

        val request = RequestBuilder()
        val handle =
            request.add(
                MailboxSet(
                    accountId = AccountId(account.accountId),
                    create =
                        mapOf(
                            CREATION_ID to
                                NewMailbox(
                                    name = name,
                                    parentId = parent?.bindings?.bindingIn(accountKey),
                                    // Refused with `invalidProperties` if the
                                    // server does not know the token, and the
                                    // label is then not created at all -- which
                                    // is why the picker offers the server's own
                                    // vocabulary rather than a colour wheel.
                                    color = color,
                                )
                        ),
                )
            )

        val result = client.send(request).result(handle)

        result.notCreated[CREATION_ID]?.let { error(it.description ?: it.type) }

        val created = result.created[CREATION_ID]?.id ?: error("The server created no label.")

        refresh(accountKey)

        return created
    }

    /**
     * Renames and recolours a label everywhere it is bound.
     *
     * Every binding, not just the first: the name lives on the label rather than on the binding, so
     * one call is enough on plMail — but a server that models it per binding would otherwise leave
     * the label renamed in one account and not the others, which is precisely the state that makes
     * collapsing on name fail.
     *
     * One patch for both, because the editor is one dialog with one save button: sending them as
     * two requests would mean a rename that lands while a colour is refused, and a dialog that
     * cannot say which half happened.
     *
     * [name] is null for a label the server will not let anyone rename — a system role — which is
     * the case colour exists for on such a label. Inbox may be recoloured and may not be renamed,
     * and sending `name` unchanged would be refused with `forbidden` for the whole patch, taking
     * the colour with it.
     */
    suspend fun update(label: Label, name: String?, color: String?) {
        label.bindings.forEach { binding ->
            val account = database.accounts().byUid(binding.accountKey) ?: return@forEach
            val client = clients.forAccount(binding.accountKey) ?: return@forEach

            val patch = MailboxPatch.build {
                name?.let { rename(it) }
                color(color)
            }

            val request = RequestBuilder()
            val handle =
                request.add(
                    MailboxSet(
                        accountId = AccountId(account.accountId),
                        update = mapOf(MailboxId(binding.mailboxId) to patch),
                    )
                )

            val result = client.send(request).result(handle)

            result.notUpdated.values.firstOrNull()?.let { error(it.description ?: it.type) }

            refresh(binding.accountKey)
        }
    }

    /**
     * Deletes a label. The label, not the mail in it.
     *
     * Children first, because the server refuses a parent that still has any — `mailboxHasChild`,
     * verified against the running instance. Deepest first means a two-level delete needs no retry
     * and no explanation to the user.
     */
    suspend fun delete(label: Label, allLabels: List<Label>) {
        val doomed = descendantsOf(label, allLabels) + label

        doomed.forEach { target ->
            target.bindings.forEach { binding ->
                val account = database.accounts().byUid(binding.accountKey) ?: return@forEach
                val client = clients.forAccount(binding.accountKey) ?: return@forEach

                val request = RequestBuilder()
                val handle =
                    request.add(
                        MailboxSet(
                            accountId = AccountId(account.accountId),
                            destroy = listOf(MailboxId(binding.mailboxId)),
                        )
                    )

                val result = client.send(request).result(handle)

                result.notDestroyed.values.firstOrNull()?.let { error(it.description ?: it.type) }

                refresh(binding.accountKey)
            }
        }
    }

    /**
     * Every label under this one, deepest first.
     *
     * By path prefix rather than by walking `parentId`, because [Label] is already the collapsed
     * view and its path is the only hierarchy left in it. The trailing separator matters: without
     * it "Work" would claim "Workshop" as a child and delete it.
     */
    private fun descendantsOf(label: Label, all: List<Label>): List<Label> =
        all.filter { it.key != label.key && it.path.startsWith(label.path + "/") }
            .sortedByDescending { it.path.count { char -> char == '/' } }

    /** Re-reads one account's mailboxes, so the sidebar shows what the server now has. */
    private suspend fun refresh(accountKey: String) {
        val account = database.accounts().byUid(accountKey) ?: return
        val client = clients.forAccount(accountKey) ?: return

        val request = RequestBuilder()
        val handle = request.add(MailboxGet(AccountId(account.accountId)))
        val mailboxes = client.send(request).result(handle).list

        mail.replaceMailboxes(accountKey, mailboxes)
    }

    private companion object {
        const val CREATION_ID = "c1"
        const val NO_ACCOUNT = "That account is not connected."
    }
}

/**
 * Which labels are on a set of conversations.
 *
 * [onSome] is what makes bulk labelling honest. Gmail draws it as a dash rather than a tick, and
 * the distinction matters at the moment of tapping: a label on three of five conversations must
 * become a label on five, never a label on none.
 */
data class LabelSelection(
    val onAll: Set<String> = emptySet(),
    val onSome: Set<String> = emptySet(),
)
