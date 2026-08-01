package de.plmail.core.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.plmail.core.database.ThreadEntity
import de.plmail.core.designsystem.LocalPlMailTheme
import de.plmail.core.designsystem.PlMailAvatar
import de.plmail.core.designsystem.PlMailLabelChip
import java.time.LocalDate

/**
 * One conversation in the list.
 *
 * Everything drawn here comes off the row itself — the thread table is denormalised precisely so
 * that fifty of these can scroll at 120fps without a join or a lazy load per row.
 *
 * The hierarchy is built from **weight and colour, not size**. Sender, subject and preview are
 * within a few points of one another; what separates them is that the sender is medium-weight ink,
 * the subject is plain ink, and the preview is muted. Doing it with size instead makes the preview
 * the smallest line on the row, which is exactly backwards — it is the line people actually read to
 * decide whether to open the mail.
 *
 * **Unread changes weight and colour together, on two lines out of three.** Sender and subject go
 * bold and full ink; read rows step down to `inkSoft`, so the unread mail is the bright text on the
 * screen and the rest recedes behind it. The preview stays muted in both states — a row where all
 * three lines go bold is a row that shouts, and an inbox where half the rows shout says nothing.
 *
 * Weight alone was tried first and was not enough. Medium against SemiBold at the *same* colour is
 * a difference you can find when told to look for it and cannot see while scanning, which is the
 * only thing a mail list is for. The colour step is what does the work; the weight makes it
 * unambiguous.
 *
 * **Exactly one thing on this row is accented: the unread dot.** Getting to one took removing two
 * marks and putting one of them back somewhere better, so the history is worth keeping.
 *
 * The dot originally sat *inside* the marks row, beside the star, at icon size. That is the version
 * that did not work, and the slot was why: that row answers "what does this conversation carry" —
 * an attachment, a star the user put there. Unread is not carried by the conversation, it is
 * something the reader has not done. A starred unread thread had the two elbowing each other in the
 * same six pixels, and neither read at a glance.
 *
 * The accent then moved to the *date*, which inherited the problem rather than being freed of it.
 * Judged the only way it can be judged — `ThreadListScreenshotTest` renders fourteen rows in both
 * schemes, because one row cannot show how often a colour appears — and the verdict was clear. With
 * every row unread, which is a fresh account, an overnight batch or a Monday, the accent appeared
 * eleven times and distinguished nothing. In dark it was worse: the accent brightens to a mint that
 * also appears in the avatar ramp two hundred pixels to the left, so the same hue sat on both edges
 * of every row, meaning "unread" on one side and nothing on the other.
 *
 * The dot is back, on its own line below the marks, because it is genuinely wanted and because the
 * date going quiet is what made room for it. It now reads as a mark of its own rather than a
 * competitor to the star, and it is the only accent on the row — so a screen of unread mail carries
 * one small repeated dot in a column of its own, which scans, instead of eleven coloured dates,
 * which did not.
 *
 * Everything else uses the ink scale: hierarchy *within* a row is weight and ink, and the accent is
 * otherwise reserved for what the app offers rather than what the mail is — the compose button, the
 * label you are looking at, a link.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ThreadRow(
    thread: ThreadEntity,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    isSelected: Boolean = false,
    /**
     * The labels to draw, already resolved and already filtered.
     *
     * Names rather than ids, and chosen by the caller rather than derived here, because *which*
     * labels belong on a row is a question about the screen: the list you are looking at must not
     * chip every row with its own name, and a system role is where the mail is rather than
     * something the user put on it. `ThreadEntity.rowLabels` in `:core:data` owns those rules and
     * this module cannot see them — which is the module boundary working, not a gap.
     */
    labels: List<String> = emptyList(),
    /** How many more the row could not fit, drawn as a counter rather than silently dropped. */
    hiddenLabels: Int = 0,
    /**
     * What "today" means for the date column.
     *
     * Hoisted for the same reason [asListDate] hoists it one level down: the column's answer is
     * relative, so a screenshot baseline that reads the clock is a baseline that stops matching
     * overnight — and "time today, weekday this week, otherwise a date" is precisely the behaviour
     * a list of rows is worth looking at. Every caller in the app takes the default.
     */
    today: LocalDate = LocalDate.now(),
) {
    val theme = LocalPlMailTheme.current
    val colors = theme.colors
    val spacing = theme.spacing

    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .background(if (isSelected) colors.accentSoft else colors.surface)
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                // The row is never shorter than a touch target, whatever the
                // density: a compact list still has to be tappable by someone
                // walking.
                .heightIn(min = spacing.touchTarget)
                .padding(horizontal = spacing.gutter, vertical = spacing.medium)
                .clearAndSetSemantics {
                    contentDescription = thread.spoken(labels, hiddenLabels)
                },
        horizontalArrangement = Arrangement.spacedBy(spacing.medium),
        verticalAlignment = Alignment.Top,
    ) {
        val seed = thread.participantsAddress ?: thread.participantsSummary

        PlMailAvatar(seed = seed, label = avatarLetter(seed))

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(spacing.tiny / 2),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(spacing.small)) {
                Text(
                    text =
                        thread.participantsSummary.ifBlank { stringResource(R.string.no_sender) },
                    style = MaterialTheme.typography.bodyLarge,
                    // Bold *and* a full step up the ink scale. Weight alone was
                    // the mistake: at the same colour, Medium against SemiBold
                    // is a difference you can find when told to look for it and
                    // not one you can see across a list. Read rows step down to
                    // inkSoft so the unread ones are the bright text on screen.
                    fontWeight = if (thread.isUnread) FontWeight.Bold else FontWeight.Normal,
                    color = if (thread.isUnread) colors.ink else colors.inkSoft,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )

                if (thread.messageCount > 1) {
                    // The count sits with the sender rather than in the right
                    // column: it describes the conversation, and the right
                    // column is when-and-what-kind.
                    Text(
                        text = thread.messageCount.toString(),
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.inkMuted,
                    )
                }
            }

            Text(
                // "(no subject)" rather than an empty line: a blank row looks
                // like a rendering failure, and mail genuinely arrives without
                // a subject.
                text =
                    thread.subject?.takeIf { it.isNotBlank() }
                        ?: stringResource(R.string.no_subject),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (thread.isUnread) FontWeight.Bold else FontWeight.Normal,
                color = if (thread.isUnread) colors.ink else colors.inkSoft,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            // Chips share the snippet's line rather than taking one of their
            // own, and that is a decision about the *list* rather than about the
            // row. Their own line would make labelled conversations taller than
            // unlabelled ones, so a mailbox where some mail is labelled scrolls
            // as a ragged column -- and this row's height is already the thing
            // that makes fifty of them scroll predictably.
            //
            // The snippet is the line they join because it is the one that can
            // afford them: sender and subject are what the row is identified by,
            // and a bordered chip beside bold subject text competes with the
            // subject for the first look. The snippet is muted furniture already,
            // and it is the line that degrades most gracefully -- it simply says
            // less.
            //
            // **The snippet leads and the chips trail it.** The first version had
            // it the other way round, and it contradicted the reason the preview
            // is set at nearly the size of the subject: a bordered box in front
            // of the sentence pushed the preview into the middle of the row and
            // cut it to three words -- "E2E Label | Steuer | +1 | Hallo, anbei
            // die b..." -- so the one line people read to decide whether to open
            // the mail was mostly furniture. Trailing them keeps the preview
            // starting at the same left edge as the sender and the subject, and
            // puts what truncates at the end of the sentence where an ellipsis
            // belongs.
            Row(
                horizontalArrangement = Arrangement.spacedBy(spacing.tiny),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = thread.snippet,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.inkMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    // Weighted, so the chips are measured first and this takes
                    // what they leave. Compose places children in composition
                    // order regardless of the order it measured them in, which
                    // is what lets the snippet lead the line while still being
                    // the part that gives way -- without the weight it measures
                    // at the width of the whole paragraph and leaves the chips
                    // nowhere to be.
                    //
                    // Filling rather than `fill = false`, so the chips end flush
                    // against the date column instead of hugging the end of the
                    // preview. Hugging is what Gmail does and it looks unplaced
                    // here: a two-word snippet parks its chip in the middle of
                    // the row, and a screenful of those is chips scattered at
                    // eight different offsets rather than a column the eye can
                    // skip down.
                    modifier = Modifier.weight(1f),
                )

                if (labels.isNotEmpty() || hiddenLabels > 0) {
                    Row(
                        // A budget for the cluster, not just for each chip. Two
                        // long names -- "Wohnung/Nebenkosten", "Steuer 2025" --
                        // are each under the per-chip cap and together take two
                        // thirds of the line, which is the same defect the
                        // reorder above was fixing, arriving from the other
                        // direction. Fixed dp rather than a fraction of the row
                        // for the same reason the per-chip cap is: the thing
                        // being bounded is a piece of text at a fixed size, so a
                        // proportional budget would leave a short chip stranded
                        // in a wide empty box on a tablet.
                        modifier = Modifier.widthIn(max = CHIP_CLUSTER),
                        horizontalArrangement = Arrangement.spacedBy(spacing.tiny),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // The names share the budget equally, and `fill = false`
                        // is what keeps a short name short: a chip takes its own
                        // width and only ellipsises once the budget has genuinely
                        // run out, so "Work" does not get stretched into a
                        // lozenge by the space its neighbour did not need.
                        //
                        // First-come was tried instead — earlier chips take what
                        // they need, the last takes the remainder — on the theory
                        // that one readable label beats two truncated ones. Two
                        // long German names produced "Wohnung/Nebe…" beside a
                        // pill containing an ellipsis and nothing else: a mark
                        // that says a label is there and refuses to name it,
                        // which is worse than either name being short. An equal
                        // share can starve a chip down to about six characters
                        // and never below it.
                        labels.forEach { name ->
                            PlMailLabelChip(
                                text = name,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                        }

                        // Unweighted, so it is measured before the name that
                        // gives way and always at its full width: "+4"
                        // abbreviated to "+" would be a mark that says there is
                        // more without saying how much more.
                        if (hiddenLabels > 0) {
                            PlMailLabelChip(text = "+$hiddenLabels")
                        }
                    }
                }
            }
        }

        Column(
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(spacing.tiny),
        ) {
            Text(
                text = thread.latestReceivedAt.asListDate(today = today),
                style = MaterialTheme.typography.labelSmall,
                // Promoted up the ink scale and one weight step, rather than
                // accented. See the note on this composable for why the accent
                // came off it. Null rather than Normal for the read case: the
                // caption style is already Medium, and overriding it downward
                // would quietly make every date in the app lighter than the
                // scale says a caption is.
                fontWeight = if (thread.isUnread) FontWeight.SemiBold else null,
                color = if (thread.isUnread) colors.ink else colors.inkMuted,
            )

            Row(
                // Holds its height even when empty, so the dot below lands at
                // the same offset on every row. Without it the dot sits one
                // mark-height higher on a row carrying no star or paperclip,
                // and a screen of mixed rows gets a visibly ragged dot column --
                // the kind of thing nobody can name but everybody sees.
                modifier = Modifier.heightIn(min = AFFORDANCE),
                horizontalArrangement = Arrangement.spacedBy(spacing.tiny),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (thread.hasAttachment) {
                    Icon(
                        imageVector = Icons.Outlined.AttachFile,
                        contentDescription = null,
                        modifier = Modifier.size(AFFORDANCE),
                        tint = colors.inkFaint,
                    )
                }

                if (thread.isFlagged) {
                    Icon(
                        imageVector = Icons.Filled.Star,
                        contentDescription = null,
                        modifier = Modifier.size(AFFORDANCE),
                        tint = colors.warning,
                    )
                }
            }

            if (thread.isUnread) {
                // Centred in a slot the width of a mark, so the dot sits on the
                // same vertical axis as the star and paperclip above it. Placed
                // bare it would be flush to the column's right edge, which puts
                // its centre a few pixels further right than theirs -- close
                // enough to look like a mistake rather than a variation.
                Box(
                    modifier = Modifier.padding(top = spacing.tiny).size(AFFORDANCE),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(modifier = Modifier.size(DOT).clip(CircleShape).background(colors.accent))
                }
            }
        }
    }
}

private val AFFORDANCE = 15.dp

/**
 * How much of the snippet's line the chips may take between them.
 *
 * A ceiling rather than a reservation — it costs the preview nothing until it bites. Two ordinary
 * names ("Steuer", "Wohnung") come to about 110dp on their own and are never touched by it; the
 * text column on a 411dp phone is 243dp once the date has taken its share, so those rows keep the
 * larger part of the line for the preview, which is the whole point of the reorder above.
 *
 * The number is set by the case that decides it, which is two names *at* the cap rather than past
 * it: sharing 160dp gives each about ten characters, and ten is where ordinary label names —
 * "Rechnungen", "E2E Label", "Wohnung" — stop being truncated. 140dp was tried first and cut "E2E
 * Label" to "E2E La…", which is a chip that has stopped naming the thing it names.
 *
 * **The honest cost:** two genuinely long names — "Wohnung/Nebenkosten" and "Steuer 2025" on the
 * same conversation — do spend two thirds of the line and leave the preview about ten characters.
 * That case is bounded and both chips stay partly readable, which is the best any arrangement
 * manages on a phone row: the alternatives are two chips that name nothing, or dropping one of them
 * silently. `thread-row-labels-long-*.png` is the baseline that keeps it honest.
 *
 * Not scaled by density, deliberately: what is being bounded is text at a fixed point size, so a
 * compact layout does not make a label name any shorter, and a budget that shrank with the spacing
 * scale would ellipsise chips that fit perfectly well.
 */
private val CHIP_CLUSTER = 160.dp

/**
 * Smaller than the marks above it, because it is not one of them.
 *
 * Large enough to read as deliberate at arm's length, small enough that a screen of unread rows is
 * a column of punctuation rather than a column of buttons — which is what the previous version,
 * sitting at icon size inside the marks row, looked like.
 */
private val DOT = 8.dp

/**
 * What the screen reader says, as one sentence rather than eight disconnected fragments.
 *
 * The labels are in it because the row's semantics are cleared and replaced wholesale — a chip that
 * is not named here is a chip that does not exist for anyone using TalkBack, and "which labels does
 * this carry" is exactly the question the chips were added to answer.
 */
private fun ThreadEntity.spoken(labels: List<String>, hiddenLabels: Int): String = buildList {
    if (isUnread) add("Unread")
    add(participantsSummary.ifBlank { "Unknown sender" })
    add(subject?.takeIf { it.isNotBlank() } ?: "No subject")
    if (messageCount > 1) add("$messageCount messages")
    if (hasAttachment) add("has attachment")
    if (isFlagged) add("starred")
    if (labels.isNotEmpty()) add("labelled " + labels.joinToString(", "))
    if (hiddenLabels > 0) add("and $hiddenLabels more")
}
    .joinToString(", ")
