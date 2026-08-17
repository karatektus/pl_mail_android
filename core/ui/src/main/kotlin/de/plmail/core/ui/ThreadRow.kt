package de.plmail.core.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import de.plmail.core.database.ThreadEntity
import de.plmail.core.designsystem.LocalPlMailTheme
import de.plmail.core.designsystem.PlMailAvatar
import de.plmail.core.designsystem.PlMailLabelChip
import de.plmail.core.designsystem.PlMailLabelColor
import de.plmail.core.designsystem.PlMailSurface
import de.plmail.core.designsystem.PlMailSurfaceKind
import de.plmail.core.designsystem.PlMailUnreadEmphasis
import de.plmail.core.designsystem.avatarIndex
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
 *
 * ---
 *
 * **Four of the appearance settings land here, and every one of them is read off the theme rather
 * than passed in.** `LocalPlMailTheme` already reaches this composable and the alternative — four
 * more parameters — would have to be threaded through `SwipeableThreadRow`, the pane and the list,
 * none of which have anything to say about them. The one thing genuinely *not* the theme's to
 * answer is [showsAccount]; see its own note.
 *
 * **Standard is byte-identical to what this row has always drawn.** Not approximately: an install
 * that has never opened the appearance screen renders exactly the pixels the checked-in baselines
 * hold, which is the only honest way to add a setting to something people are already looking at.
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
     * Names and colours rather than ids, and chosen by the caller rather than derived here, because
     * *which* labels belong on a row is a question about the screen: the list you are looking at
     * must not chip every row with its own name, and a system role is where the mail is rather than
     * something the user put on it. `ThreadEntity.rowLabels` in `:core:data` owns those rules and
     * this module cannot see them — which is the module boundary working, not a gap.
     */
    labels: List<RowChip> = emptyList(),
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
    /**
     * Whether this row is in a list showing more than one account at once.
     *
     * The caller's answer and not the theme's, and the split is exactly where the knowledge is: the
     * appearance setting says whether an account mark is *wanted*, and only the screen drawing the
     * list knows whether there is more than one account for it to distinguish. A row that decided
     * for itself would mark every conversation in a single-account inbox with the same colour,
     * which is a decoration rather than information.
     *
     * Defaulting to false means every caller in the app today gets no mark, which is correct:
     * `MailScreen` has not been taught to say yes yet. See the report accompanying this change.
     */
    showsAccount: Boolean = false,
) {
    PlMailSurface(PlMailSurfaceKind.LIST) {
        ThreadRowContent(
            thread = thread,
            onClick = onClick,
            modifier = modifier,
            onLongClick = onLongClick,
            isSelected = isSelected,
            labels = labels,
            hiddenLabels = hiddenLabels,
            today = today,
            showsAccount = showsAccount,
        )
    }
}

/**
 * The row proper, inside the list's own density.
 *
 * Split from [ThreadRow] only so that `spacing` is read *after* [PlMailSurface] has re-provided the
 * theme. Reading it in the same function would read the app-wide density and then draw the row with
 * it, which is the whole setting quietly not working.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
@Suppress("LongParameterList", "LongMethod")
private fun ThreadRowContent(
    thread: ThreadEntity,
    onClick: () -> Unit,
    modifier: Modifier,
    onLongClick: (() -> Unit)?,
    isSelected: Boolean,
    labels: List<RowChip>,
    hiddenLabels: Int,
    today: LocalDate,
    showsAccount: Boolean,
) {
    val theme = LocalPlMailTheme.current
    val colors = theme.colors
    val spacing = theme.spacing
    val list = theme.list
    val spoken = thread.spoken(labels, hiddenLabels)

    // Subtle takes the ink promotion off an unread row and leaves the weight
    // alone; Standard and Strong keep both. See PlMailUnreadEmphasis for why the
    // three are not symmetric -- this row signals unread with ink where the web
    // signals it with a tint, so there is nothing here for Subtle to turn down
    // except the colour.
    val isQuiet = list.unreadEmphasis == PlMailUnreadEmphasis.SUBTLE
    val unreadInk = if (isQuiet) colors.inkSoft else colors.ink
    val unreadMeta = if (isQuiet) colors.inkMuted else colors.ink

    // Strong stands in for the web's row tint with a lift toward `raised`,
    // because that is the one neutral this palette guarantees is a step off the
    // page in every theme -- a fixed alpha would be a slab on Solar's cream and
    // invisible on Dusk. Selection outranks it: a selected row is already
    // saying something louder.
    val background =
        when {
            isSelected -> colors.accentSoft
            thread.isUnread && list.unreadEmphasis == PlMailUnreadEmphasis.STRONG ->
                lerp(colors.surface, colors.raised, STRONG_TINT)
            else -> colors.surface
        }

    // The web's Strong draws a 3px accent bar down the leading edge and this is
    // the same mark. It is the one part of the setting that survives a
    // translucent pane over a photograph, which is why it is the part worth
    // copying exactly rather than reinterpreting.
    val bar =
        colors.accent.takeIf {
            thread.isUnread && list.unreadEmphasis == PlMailUnreadEmphasis.STRONG
        }

    // Seeded from the account rather than assigned, and from the same ramp and
    // the same hash the avatars use: two accounts open side by side have to be
    // told apart at a glance, and a colour that agreed with nothing else on the
    // row would be a second vocabulary to learn.
    val accountMark =
        if (showsAccount && list.accountCorner) {
            colors.avatars[avatarIndex(thread.accountKey, colors.avatars.size)]
        } else {
            null
        }

    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .background(background)
                // Both marks are drawn rather than laid out, which is what keeps
                // them free: `ThreadRowLayoutTest` asserts that every row in a
                // list is the same height, and a bar or a corner that took part
                // in measurement would make an unread row taller than a read one
                // at one setting and not at another.
                .marks(bar = bar, corner = accountMark)
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                // The row is never shorter than a touch target, whatever the
                // density: a compact list still has to be tappable by someone
                // walking.
                .heightIn(min = spacing.touchTarget)
                .padding(horizontal = spacing.gutter, vertical = spacing.medium)
                // Read out of composition rather than built inside the semantics
                // block, which is not a composable scope and cannot reach a
                // resource from within itself.
                .clearAndSetSemantics { contentDescription = spoken },
        horizontalArrangement = Arrangement.spacedBy(spacing.medium),
        verticalAlignment = Alignment.Top,
    ) {
        val seed = thread.participantsAddress ?: thread.participantsSummary

        // Dropped outright rather than replaced with a spacer. The avatar is a
        // colour and a letter, so a placeholder holding its width would leave a
        // 40dp hole that says nothing -- and somebody who turned avatars off did
        // it to get the width back for the subject.
        if (list.avatars) {
            PlMailAvatar(seed = seed, label = avatarLetter(seed))
        }

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
                    color = if (thread.isUnread) unreadInk else colors.inkSoft,
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
                color = if (thread.isUnread) unreadInk else colors.inkSoft,
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
                // Hidden rather than removed at zero preview lines, and the
                // weighted spacer is what "hidden" has to mean here: the chips
                // share this line, so a line that stopped existing would put them
                // hard against the sender's left edge on one setting and flush
                // right on every other. The row keeps the same skeleton at all
                // three settings and only the sentence comes and goes.
                if (list.previewLines == 0) {
                    Spacer(modifier = Modifier.weight(1f))
                } else {
                    Text(
                        text = thread.snippet,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.inkMuted,
                        maxLines = list.previewLines,
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
                }

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
                        //
                        // The floor underneath it is what a fixed cap alone
                        // cannot express, and it was missing. On a 320dp phone
                        // the text column is about 188dp, so 160dp of chips left
                        // the preview roughly three characters -- "On Aug 1,…"
                        // -- which is the very defect the chips were moved
                        // *behind* the snippet to avoid, arriving again from a
                        // narrower screen. Seen on the device at 320dp in German,
                        // not in a baseline: every screenshot until then was
                        // taken at 411dp, where the cap never bites.
                        //
                        // `Modifier.layout` rather than `BoxWithConstraints`,
                        // and that is deliberate: this is fifty rows scrolling,
                        // and a subcomposition per row to learn a width the
                        // measure pass is already being handed is a cost paid on
                        // every frame. The cluster is the unweighted child here,
                        // so the constraint it receives is the whole line.
                        modifier =
                            Modifier.layout { measurable, constraints ->
                                val budget = chipBudget(constraints.maxWidth.toDp()).roundToPx()

                                val placed =
                                    measurable.measure(
                                        constraints.copy(minWidth = 0, maxWidth = budget)
                                    )

                                layout(placed.width, placed.height) { placed.place(0, 0) }
                            },
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
                        labels.forEach { chip ->
                            PlMailLabelChip(
                                text = chip.name,
                                color = chip.color,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                        }

                        // Unweighted, so it is measured before the name that
                        // gives way and always at its full width: "+4"
                        // abbreviated to "+" would be a mark that says there is
                        // more without saying how much more.
                        // Uncoloured, always. It stands for several labels of
                        // possibly several colours, and borrowing one of them
                        // would say the hidden labels are all that colour.
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
                color = if (thread.isUnread) unreadMeta else colors.inkMuted,
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

/**
 * One chip on a row: its name, and the colour its label carries.
 *
 * A type of its own rather than `:core:data`'s `RowLabel`, and the reason is the module graph
 * rather than taste. `RowLabel` holds the server's raw token because `:core:data` cannot see a
 * colour; `PlMailLabelColor` is the resolved vocabulary and lives in `:core:designsystem`, which
 * this module depends on and that one does not. A single shared type would mean one of those two
 * modules depending on something it has no business knowing about — `:core:ui` on repositories and
 * Hilt, or `:core:data` on Compose. The map across is one line, in the feature that already sees
 * both.
 */
data class RowChip(val name: String, val color: PlMailLabelColor? = null)

/**
 * The two marks that are painted rather than laid out.
 *
 * One modifier for both, because both have the same reason for existing in the draw phase instead
 * of the layout one: `ThreadRowLayoutTest` holds every row in a list to the same height, and a mark
 * that occupied space would break that at one appearance setting and not at another — the exact
 * class of defect that test was written after. Painting them also means the default, where both are
 * null, touches nothing at all.
 *
 * [bar] runs the full height of the leading edge; [corner] is a triangle in the leading top corner,
 * a shape rather than a dot because the row already spends its dots on unread and this mark must
 * not be mistaken for one.
 *
 * Neither is mirrored for right-to-left. `drawBehind` works in raw pixels with no layout direction
 * to consult, and a mark on the wrong edge in Arabic is a real defect — worth naming here rather
 * than discovering, since the app has no RTL locale today and the fix belongs with the one that
 * adds it.
 */
private fun Modifier.marks(bar: Color?, corner: Color?): Modifier =
    if (bar == null && corner == null) this
    else
        drawBehind {
            bar?.let { drawRect(color = it, size = Size(UNREAD_BAR.toPx(), size.height)) }

            corner?.let {
                val side = ACCOUNT_CORNER.toPx()
                val triangle =
                    Path().apply {
                        moveTo(0f, 0f)
                        lineTo(side, 0f)
                        lineTo(0f, side)
                        close()
                    }

                drawPath(triangle, color = it)
            }
        }

private val AFFORDANCE = 15.dp

/**
 * The web's `--unread-bar-w` at Strong, in dp rather than px.
 *
 * The same number and not a conversion of it: 3 CSS pixels and 3dp are both "a mark you can see and
 * not a stripe", and matching the browser's *appearance* matters more here than matching its
 * physical width on a 420dpi phone — where 3px would be under a millimetre.
 */
private val UNREAD_BAR = 3.dp

/**
 * Small enough to be a corner and not a wedge.
 *
 * It sits over the top-left of the avatar's own margin, so anything much larger starts reading as a
 * second avatar rather than as a mark on the row.
 */
private val ACCOUNT_CORNER = 10.dp

/**
 * How far Strong lifts an unread row off the page.
 *
 * The web scales the theme's own unread tint by 1.6; there is no such tint here, so the equivalent
 * is a fraction of the way toward `raised` — the one neutral every palette guarantees is a visible
 * step off `surface`. 0.55 was chosen against Solar, where `surface` and `raised` are eight points
 * apart and the full lift is almost invisible, rather than against Dark, where a smaller number
 * would have done.
 */
private const val STRONG_TINT = 0.55f

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
 * How much of the line the preview keeps, whatever the labels want.
 *
 * The cap above answers "how much may chips take"; this answers "how little may the preview be left
 * with", and only one of those two questions had been asked. They agree on a 411dp phone, where the
 * text column is 243dp and 160dp of chips still leaves 83dp — and they disagree completely at
 * 320dp, where the same 160dp left about three characters of preview. A cap alone cannot know that,
 * because the number it is capping is not the one that matters.
 *
 * 104dp is roughly fifteen characters of `bodySmall`, which is where a preview stops being a
 * sentence and becomes a hint that one exists. Below the floor the chips give way, not the preview:
 * the preview is the line people read to decide whether to open the mail, and a label they can also
 * see in the sidebar is not worth it.
 */
private val SNIPPET_FLOOR = 104.dp

/**
 * How wide the chip cluster may be on a line this wide.
 *
 * Extracted rather than inlined into the measure block because the measure block is the one place
 * this cannot be tested: the row clears its own semantics and replaces them with a single spoken
 * sentence, so nothing inside it can carry a test tag and no test can ask how wide the preview came
 * out. The arithmetic is the whole defect, and this is the shape of it that a test can hold.
 */
internal fun chipBudget(lineWidth: Dp): Dp =
    (lineWidth - SNIPPET_FLOOR).coerceIn(0.dp, CHIP_CLUSTER)

/**
 * How many chips a row can carry in a pane this wide.
 *
 * A composition-time decision, unlike the width budget above, because "one name or a counter" is a
 * question about *what to draw* and cannot be answered during measurement. It belongs to the list
 * rather than to the row: the list knows its own pane width — which on a tablet is not the window's
 * — and asking once per list costs one subcomposition instead of one per row.
 *
 * Below the threshold a row falls to a single slot, and the counter takes it as soon as there is
 * more than one label, so a narrow row says "+2" rather than picking one of two names arbitrarily.
 * That is `ROW_LABEL_LIMIT`'s own rule applied at a smaller number, not a second rule.
 *
 * 400dp rather than the compact/medium breakpoint, because the breakpoint is 600dp and every phone
 * in portrait is below it — including the 411dp one where two chips are perfectly comfortable. The
 * number that matters here is where a *second* chip stops being readable, and that is a property of
 * the row's own geometry.
 */
fun rowLabelSlots(paneWidth: Dp): Int = if (paneWidth >= TWO_CHIP_WIDTH) 2 else 1

private val TWO_CHIP_WIDTH = 400.dp

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
@Composable
private fun ThreadEntity.spoken(labels: List<RowChip>, hiddenLabels: Int): String {
    val separator = stringResource(R.string.a11y_separator)

    return buildList {
            if (isUnread) add(stringResource(R.string.a11y_unread))
            add(participantsSummary.ifBlank { stringResource(R.string.no_sender) })
            add(subject?.takeIf { it.isNotBlank() } ?: stringResource(R.string.no_subject))
            if (messageCount > 1)
                add(pluralStringResource(R.plurals.a11y_message_count, messageCount, messageCount))
            if (hasAttachment) add(stringResource(R.string.a11y_has_attachment))
            if (isFlagged) add(stringResource(R.string.a11y_starred))
            // The names only. A colour is not something a screen reader can
            // usefully say — "Work, blue" describes the chip rather than the
            // conversation — and the same label is the same label whatever it
            // is tinted.
            if (labels.isNotEmpty())
                add(
                    stringResource(
                        R.string.a11y_labelled,
                        labels.joinToString(separator) { it.name },
                    )
                )
            if (hiddenLabels > 0)
                add(pluralStringResource(R.plurals.a11y_more_labels, hiddenLabels, hiddenLabels))
        }
        .joinToString(separator)
}
