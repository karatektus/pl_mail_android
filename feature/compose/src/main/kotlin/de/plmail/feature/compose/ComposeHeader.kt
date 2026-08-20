package de.plmail.feature.compose

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import de.plmail.core.data.SendIdentity
import de.plmail.core.designsystem.PlMailDivider
import de.plmail.core.designsystem.PlMailTheme
import de.plmail.jmap.mail.EmailAddress

/**
 * Who the message is from, who it is going to, and what it is about.
 *
 * Two shapes, chosen by [isExpanded]. Expanded is the form the composer has always had — a From
 * row, the address lines and the subject, each on its own rule-separated line. Collapsed is a
 * single line summarising all of them, which is what the screen wears once the keyboard is up: on a
 * 411dp phone the four header rows and the IME together left about three lines of the message
 * visible, so people were writing into a slot rather than reading what they had written.
 *
 * **Nothing here decides when to fold.** [ComposeScreen] does, and it folds only once the body has
 * focus *and* there is a recipient to name — a composer whose To line is still empty has nothing to
 * summarise, and hiding the one field that has still to be filled in would be the worst possible
 * moment to save four rows. Tapping the summary opens it again and puts the cursor straight into
 * To, so coming back to the addresses is one tap with the keyboard still up rather than a dismiss,
 * a scroll and a second tap.
 */
@Composable
internal fun ComposeHeader(
    state: ComposeUiState,
    isExpanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onIdentity: (SendIdentity) -> Unit,
    onTo: (List<EmailAddress>) -> Unit,
    onCc: (List<EmailAddress>) -> Unit,
    onBcc: (List<EmailAddress>) -> Unit,
    onSubject: (String) -> Unit,
    onShowCopyFields: () -> Unit,
    onQueryChanged: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val toField = remember { FocusRequester() }

    // Set by a tap on the summary and by nothing else. Expanding is also the
    // state the composer opens in, and focusing To there would raise the
    // keyboard over a reply whose addresses nobody asked to edit.
    var focusToOnExpand by remember { mutableStateOf(false) }

    LaunchedEffect(isExpanded, focusToOnExpand) {
        if (!isExpanded || !focusToOnExpand) return@LaunchedEffect

        focusToOnExpand = false

        // One frame first. The field being asked for is composed by the same
        // change that set this flag, and a FocusRequester whose node has not
        // been placed yet throws rather than remembering the request -- so the
        // tap that opens the header would take the app down instead of moving
        // the cursor.
        withFrameNanos {}
        toField.requestFocus()
    }

    Column(modifier = modifier.fillMaxWidth()) {
        if (!isExpanded) {
            // No rule under it: the screen draws one below this header either
            // way, and two hairlines a dp apart read as a rendering fault.
            SummaryRow(
                state = state,
                onExpand = {
                    focusToOnExpand = true
                    onExpandedChange(true)
                },
            )

            return@Column
        }

        FromRow(state = state, onSelected = onIdentity)
        PlMailDivider()

        RecipientField(
            label = stringResource(R.string.compose_to),
            addresses = state.draft.to,
            onChanged = onTo,
            suggestions = state.suggestions,
            onQueryChanged = onQueryChanged,
            focusRequester = toField,
            // At the end of the To line rather than on a row of its own beneath
            // it. It is an affordance about this line, and a whole row for one
            // small button was a row of header the message did not get.
            trailing =
                if (state.isShowingCopyFields) {
                    null
                } else {
                    { CopyFieldsButton(onClick = onShowCopyFields) }
                },
        )

        if (state.isShowingCopyFields) {
            RecipientField(
                label = stringResource(R.string.compose_cc),
                addresses = state.draft.cc,
                onChanged = onCc,
                suggestions = state.suggestions,
                onQueryChanged = onQueryChanged,
            )

            RecipientField(
                label = stringResource(R.string.compose_bcc),
                addresses = state.draft.bcc,
                onChanged = onBcc,
                suggestions = state.suggestions,
                onQueryChanged = onQueryChanged,
            )
        }

        PlMailDivider()

        TextField(
            value = state.draft.subject,
            onValueChange = onSubject,
            placeholder = { Text(stringResource(R.string.compose_subject)) },
            singleLine = true,
            colors = flatFieldColors(),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * Cc and Bcc, opened from the end of the To line.
 *
 * **It cannot squash the chips**, which is the constraint that decided where it goes. The
 * recipients already on the line are a `FlowRow` *above* the input, free to wrap onto as many rows
 * as they need; this rides the input row only, as the field's trailing slot. So a narrow window
 * costs the half-typed address its width and never a chip its name — and the moment it is tapped it
 * is gone, replaced by the two lines it opened.
 */
@Composable
private fun CopyFieldsButton(onClick: () -> Unit) {
    val spacing = PlMailTheme.spacing

    TextButton(
        onClick = onClick,
        // Tighter than a button's default padding, because this one sits inside
        // a field rather than beside one: Material's own ends would take a
        // sixth of a phone's To line for whitespace. The button keeps its 40dp
        // minimum height, so the target is still a target.
        contentPadding = PaddingValues(horizontal = spacing.small, vertical = spacing.tiny),
    ) {
        Text(stringResource(R.string.compose_show_copy_fields))
    }
}

/**
 * The whole header as one line, with a chevron saying it opens.
 *
 * **Three facts and about fifty characters to put them in**, which is the constraint the layout is
 * built around rather than one it discovers. The first version drew the whole thing as a single
 * `Text` and the subject fell off the end of every message — the summary named who it was going to
 * and then hid the only part the writer could not reconstruct from memory. So the addressing is
 * capped at a little over half the row and elides there, and the subject takes everything that is
 * left: whichever half is long, both are still readable.
 *
 * The addressing is [de.plmail.core.designsystem.PlMailColors.inkMuted] and the subject is `ink`,
 * because that is the hierarchy — the subject is what the message *is*, and who it is from is a
 * fact about it. Smaller type than the fields it replaces, for the same reason a caption is: this
 * is the line you glance at, not the one you read.
 *
 * TalkBack reads it as a single item: the two children are merged behind one description that
 * spells out what the glyphs stand for — "From … to … , subject …" — and the click carries a label,
 * so the announcement ends with what a double tap will do rather than leaving the user to find out.
 */
@Composable
private fun SummaryRow(state: ComposeUiState, onExpand: () -> Unit) {
    val theme = PlMailTheme.values
    val draft = state.draft

    val recipients =
        remember(draft.to, draft.cc, draft.bcc) {
            summariseRecipients(draft.to, draft.cc, draft.bcc)
        }

    // The address rather than `label`, and rather than Gmail's "me": the address
    // is the part that actually differs between two aliases whose display name
    // is the same account's.
    //
    // Blank where naming the sender would say nothing — see `summaryNamesSender`.
    // The first version of this line always drew it, and on a phone the result
    // was "jan@plmail.example › Katrin Voge… +1 · Re: die Neben…": the one fact
    // that could not change had eaten the two that could.
    val from =
        if (!state.summaryNamesSender) ""
        else state.identity?.email ?: stringResource(R.string.compose_from_unknown)
    val noSubject = stringResource(R.string.compose_no_subject)
    val summary = composeSummary(from, recipients, draft.subject, noSubject)

    val spokenRecipients =
        if (recipients.more == 0) {
            recipients.names.joinToString(", ")
        } else {
            recipients.names.joinToString(", ") +
                ", " +
                pluralStringResource(
                    R.plurals.compose_summary_more,
                    recipients.more,
                    recipients.more,
                )
        }

    val description =
        stringResource(
            R.string.compose_summary_description,
            from,
            spokenRecipients,
            summary.subject,
        )

    Row(
        modifier =
            Modifier.fillMaxWidth()
                .clickable(
                    onClickLabel = stringResource(R.string.compose_summary_expand),
                    onClick = onExpand,
                )
                .padding(horizontal = theme.spacing.gutter, vertical = theme.spacing.small)
                .semantics(mergeDescendants = true) { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Two lines rather than one, and the subject gets its own.
        //
        // The first version put everything on a single row and had to ration
        // the width between the addressing and the subject -- a little over half
        // each -- which meant both were usually elided and neither could be read
        // at a glance. That is not a shortage of room but a category error: who
        // it is going to and what it is about are two facts, and the reason to
        // fold the header at all is that they are the two worth keeping. A line
        // apiece costs one row of a compact style and gives each of them the
        // whole width.
        Column(
            modifier = Modifier.weight(1f),
            // A real gap rather than a hairline. Two lines a hairline apart read
            // as one wrapped sentence, which is the opposite of what splitting
            // them was for -- the whole point is that these are two facts.
            verticalArrangement = Arrangement.spacedBy(theme.spacing.tiny),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(theme.spacing.small),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = summary.addressing,
                    style = MaterialTheme.typography.bodySmall,
                    color = theme.colors.inkMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )

                // Its own element, and that is the point of it. Inside the
                // addressing it was the last thing on a string that elides, so
                // the one part of the line a reader cannot reconstruct -- that
                // this is going to more people than it names -- was the first
                // part to disappear. Unbounded and unelidable here, so "+2"
                // survives whatever the names cost.
                if (summary.more.isNotEmpty()) {
                    Text(
                        text = summary.more,
                        style = MaterialTheme.typography.bodySmall,
                        color = theme.colors.inkMuted,
                        maxLines = 1,
                    )
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(theme.spacing.tiny),
                verticalAlignment = Alignment.Bottom,
            ) {
                // Named, because on its own a second line of text is just more
                // text: "Re: die Nebenkostenabrechnung" under a row of names
                // could be read as another recipient at a glance. The word is
                // the same one the expanded field uses, so the folded header is
                // the open one in shorthand rather than a second design.
                //
                // Faint and small: it is a signpost, and a label that competed
                // with what it labels would be worse than none. The colon lives
                // in the string rather than in the layout, because where it goes
                // — and whether it takes a space before it — is a question about
                // the language.
                Text(
                    text = stringResource(R.string.compose_summary_subject),
                    style = MaterialTheme.typography.labelMedium,
                    color = theme.colors.inkFaint,
                    maxLines = 1,
                )

                // The brighter of the two lines, because it is what the message
                // *is*. On the single-line version this had to compete with the
                // addressing for the same run of pixels and lost about half of
                // itself; here it has the row.
                Text(
                    text = summary.subject,
                    style = MaterialTheme.typography.bodyMedium,
                    color = theme.colors.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        Icon(
            imageVector = Icons.Filled.ExpandMore,
            contentDescription = null,
            tint = theme.colors.inkMuted,
        )
    }
}

/**
 * Who the message comes from.
 *
 * Always visible while the header is open, never hidden behind a menu — plMail accounts can hold
 * several sendable addresses and one credential reaches several accounts, so "which of me is this
 * from" is a real question at the moment of writing rather than a setting.
 *
 * **One entry per alias**, which it could not be until recently: `EmailSubmission/set` ignored
 * `identityId` and sent everything as the account's own address, so the picker collapsed the list
 * to one entry per account rather than promising something the send would not honour. The server
 * now resolves the id through the same list `Identity/get` publishes and refuses an id it did not
 * publish with `forbiddenFrom`, so every entry here is an address the mail will genuinely leave as.
 *
 * One limit worth knowing and not worth pretending about: the server sets the From *address* only.
 * The display name still comes from the account, on the web path too — so an alias with its own
 * name shows that name here and the mail goes out under the account's.
 */
@Composable
private fun FromRow(state: ComposeUiState, onSelected: (SendIdentity) -> Unit) {
    var isOpen by remember { mutableStateOf(false) }
    val theme = PlMailTheme.values
    val choices = state.identities

    Row(
        modifier =
            Modifier.fillMaxWidth()
                .clickable(enabled = choices.size > 1) { isOpen = true }
                .padding(horizontal = theme.spacing.gutter, vertical = theme.spacing.medium),
        horizontalArrangement = Arrangement.spacedBy(theme.spacing.small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.compose_from),
            style = MaterialTheme.typography.labelLarge,
            color = theme.colors.inkMuted,
        )

        Text(
            text = state.identity?.label ?: stringResource(R.string.compose_from_unknown),
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )

        if (choices.size > 1) {
            Icon(
                imageVector = Icons.Filled.MoreHoriz,
                contentDescription = stringResource(R.string.compose_choose_sender),
            )
        }

        DropdownMenu(expanded = isOpen, onDismissRequest = { isOpen = false }) {
            choices.forEach { identity ->
                DropdownMenuItem(
                    text = { Text(identity.label) },
                    // Only when there is more than one account in the list.
                    // Several aliases of one mailbox do not need its name
                    // repeated under each of them.
                    trailingIcon =
                        if (state.showsAccountNames) {
                            {
                                Text(
                                    text = identity.accountName,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = theme.colors.inkMuted,
                                )
                            }
                        } else {
                            null
                        },
                    onClick = {
                        onSelected(identity)
                        isOpen = false
                    },
                )
            }
        }
    }
}

/** The people a one-line summary can name, and how many it had to leave out. */
internal data class SummaryRecipients(val names: List<String>, val more: Int)

/**
 * Who this message is going to, in at most [shown] names.
 *
 * **Cc and Bcc count**, and that is the honest half of this. The line has room for two names, so
 * the interesting number is the one after the `+` — and a summary reading "+1" over a message going
 * to nine people because six of them are on Cc would be the composer lying about the send. They are
 * named in To, Cc, Bcc order, so the people the message is actually addressed to are the ones that
 * survive the cut.
 *
 * De-duplicated by address, because somebody on both To and Cc is one person and counting them
 * twice would inflate the overflow for no reason a reader could work out.
 */
internal fun summariseRecipients(
    to: List<EmailAddress>,
    cc: List<EmailAddress> = emptyList(),
    bcc: List<EmailAddress> = emptyList(),
    shown: Int = MAX_SUMMARY_NAMES,
): SummaryRecipients {
    val everyone = (to + cc + bcc).distinctBy { it.identity }

    return SummaryRecipients(
        names = everyone.take(shown).map { it.display },
        more = (everyone.size - shown).coerceAtLeast(0),
    )
}

/**
 * The collapsed header, as the pieces the row draws.
 *
 * Three rather than one string because they are drawn in three places and elide differently:
 * [addressing] takes the first line and gives way to [more], which gives way never, and [subject]
 * has the second line to itself. [more] is empty when everybody fits.
 */
internal data class ComposeSummary(
    val addressing: String,
    val more: String,
    val subject: String,
)

/**
 * The collapsed header, as text.
 *
 * Pure, and separate from the composable that draws it, because the interesting cases are all about
 * strings and none of them is about Compose: nobody named, one person, more people than fit, a
 * message with no subject yet.
 *
 * **Three pieces rather than one string**, because the header draws them on two lines and elides
 * them independently — see [SummaryRow]. Within [ComposeSummary.addressing] the mark is `›`, which
 * reads as direction: this address is sending to those people. It also keeps the two halves of the
 * addressing from running into one another, where a comma would look like one more recipient.
 *
 * [noSubject] is passed in rather than resolved here so this stays free of resources — the
 * placeholder has to be a translated string, and this function has to be testable on the JVM.
 */
internal fun composeSummary(
    from: String,
    recipients: SummaryRecipients,
    subject: String,
    noSubject: String,
): ComposeSummary {
    val people = recipients.names.joinToString(", ")

    return ComposeSummary(
        // The mark is drawn only with something on both sides of it. Either half
        // can be absent — a draft addressed to nobody, or a sender the line has
        // no reason to name — and an addressing reading "› Katrin" or "me › "
        // would be a rendering fault rather than a summary.
        addressing =
            when {
                from.isEmpty() -> people
                people.isEmpty() -> from
                else -> "$from $RECIPIENT_MARK $people"
            },
        more = if (recipients.more > 0) "+${recipients.more}" else "",
        subject = subject.ifBlank { noSubject },
    )
}

/**
 * How many recipients the line names before it starts counting.
 *
 * Two, and the count beside them says how many more there are — which together are what tell a
 * reply-all from a reply, the mistake this line exists to make visible. Three names leaves nothing
 * for the subject on a phone, and the third name is worth less than the number.
 */
internal const val MAX_SUMMARY_NAMES = 2

private const val RECIPIENT_MARK = "›"
