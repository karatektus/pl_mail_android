package de.plmail.feature.compose

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import de.plmail.core.designsystem.LocalPlMailTheme
import de.plmail.core.designsystem.PlMailDivider
import de.plmail.jmap.mail.EmailAddress

/**
 * One address line: chips for what is already there, a field for what is being typed.
 *
 * The rule that matters is **when a typed address becomes a chip**. Only committing on Enter loses
 * whatever is half-typed when the user taps Send, which is the single most common way a mail goes
 * to nobody; committing on every keystroke makes the field impossible to correct. So it commits on
 * Enter, on a separator the user typed, and on losing focus — the last of which covers tapping
 * straight from this field to Send.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RecipientField(
    label: String,
    addresses: List<EmailAddress>,
    onChanged: (List<EmailAddress>) -> Unit,
    suggestions: List<EmailAddress>,
    onQueryChanged: (String) -> Unit,
    modifier: Modifier = Modifier,
    /**
     * A handle on the input, for a caller that has to put the cursor here.
     *
     * The collapsed header uses it: tapping the summary opens the fields and moves the cursor into
     * To in the same gesture, so nobody has to hunt for the field they just asked to see.
     */
    focusRequester: FocusRequester = remember { FocusRequester() },
    /**
     * What sits at the end of the input line — the Cc/Bcc affordance, on the To line.
     *
     * A slot rather than a boolean, because this component has no business knowing what Cc means;
     * it knows it has a line with an end to it. See `CopyFieldsButton` for why that end is where
     * the affordance belongs.
     */
    trailing: (@Composable () -> Unit)? = null,
    /** What a chip shows about its address's key (#9); null shows nothing. */
    lockOf: (EmailAddress) -> RecipientLock? = { null },
    /**
     * Text that was meant as a recipient and is not an address, for the composer to report.
     *
     * The text itself stays in the field, so it can be corrected; this is only the telling. The
     * first piece of several, as on the web: one name is enough to find the line.
     */
    onRejected: (String) -> Unit = {},
) {
    var typed by remember { mutableStateOf("") }
    var isFocused by remember { mutableStateOf(false) }

    // What the suggestion list is measured against: it hangs under this field
    // and has to be exactly as wide as it, which is what makes it read as
    // belonging to the field rather than as a panel that happened to appear.
    var fieldWidthPx by remember { mutableIntStateOf(0) }

    fun apply(edit: RecipientEdit.Commit) {
        if (edit.added.isNotEmpty()) onChanged(addresses + edit.added)

        typed = edit.remaining
        onQueryChanged("")

        // What is not an address stays in the field and is said out loud. It
        // used to be dropped without a word, and the mail went out without
        // whoever it was meant for.
        edit.rejected.firstOrNull()?.let(onRejected)
    }

    /**
     * Enter, or the cursor leaving the line.
     *
     * Only Enter reports text with no address in it at all. Leaving the line with a half-typed name
     * is a search abandoned, not a mistake to point out; pressing Enter on it is asking for a chip
     * that cannot be made.
     */
    fun commit(isExplicit: Boolean) {
        when (val edit = typed.commitRecipients(addresses)) {
            is RecipientEdit.Commit -> apply(edit)
            is RecipientEdit.Typing ->
                if (isExplicit && typed.isNotBlank()) onRejected(typed.trim())
        }
    }

    /**
     * Takes a suggestion whole, name included.
     *
     * The previous version handed the address back through [commit], which re-parsed a bare address
     * and produced a chip with no name on it — the contact list had just supplied the name and it
     * was thrown away one line later.
     */
    fun pick(suggestion: EmailAddress) {
        if (addresses.none { it.identity == suggestion.identity }) {
            onChanged(addresses + suggestion)
        }

        typed = ""
        onQueryChanged("")
    }

    val theme = LocalPlMailTheme.current

    // The Box is the suggestion list's anchor: the list hangs under the whole
    // field, as wide as it, and the line being typed is always its last line.
    Box(modifier = modifier.fillMaxWidth().onSizeChanged { fieldWidthPx = it.width }) {
        // One flow for the label, the chips and the input, so a line with room
        // for them is one line. The chips had a row of their own above the
        // input, which made every reply's To two rows tall for one address.
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = theme.spacing.gutter),
            verticalAlignment = Alignment.Top,
        ) {
            FlowRow(
                modifier =
                    Modifier.weight(1f)
                        .heightIn(min = LINE_HEIGHT)
                        // A tap anywhere on the line types into it, which the input
                        // alone cannot promise once it has shrunk beside the chips.
                        .clickable(interactionSource = null, indication = null) {
                            focusRequester.requestFocus()
                        }
                        .padding(vertical = theme.spacing.tiny),
                horizontalArrangement = Arrangement.spacedBy(theme.spacing.tiny),
                verticalArrangement = Arrangement.spacedBy(theme.spacing.tiny),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyLarge,
                    color = theme.colors.fieldPlaceholder,
                    modifier = Modifier.padding(end = theme.spacing.tiny),
                )

                addresses.forEach { address ->
                    InputChip(
                        selected = false,
                        onClick = { onChanged(addresses - address) },
                        label = { Text(address.display) },
                        leadingIcon = lockOf(address)?.let { lock -> { RecipientLockIcon(lock) } },
                        trailingIcon = {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription =
                                    stringResource(
                                        R.string.compose_remove_recipient,
                                        address.display,
                                    ),
                            )
                        },
                    )
                }

                // As wide as what is typed, and room for an address while it has the
                // cursor; until then nothing, so an empty input never pushes the
                // chips onto a second line. Not weighted to fill the line: a weighted
                // item is measured for its share before the chips are, and they
                // wrapped early to leave it room.
                BasicTextField(
                    value = typed,
                    onValueChange = { value ->
                        // A separator the user typed, or a pasted list, is a commit;
                        // see recipientEdit for which is which.
                        when (val edit = recipientEdit(typed, value, addresses)) {
                            is RecipientEdit.Commit -> apply(edit)
                            is RecipientEdit.Typing -> {
                                typed = edit.text
                                onQueryChanged(edit.text)
                            }
                        }
                    },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = theme.colors.ink),
                    cursorBrush = SolidColor(theme.colors.accent),
                    keyboardOptions =
                        KeyboardOptions(
                            keyboardType = KeyboardType.Email,
                            imeAction = ImeAction.Done,
                        ),
                    keyboardActions = KeyboardActions(onDone = { commit(isExplicit = true) }),
                    modifier =
                        Modifier.then(
                                // Exactly, rather than at least: a text field has
                                // a minimum size of its own that "at least 1dp"
                                // does not override, and it took a second line.
                                // A point tall as well, for a line whose last chip
                                // fills it and pushes even a point onto the next.
                                if (isFocused || typed.isNotEmpty())
                                    Modifier.widthIn(min = MIN_INPUT_WIDTH)
                                        .padding(vertical = theme.spacing.small)
                                else Modifier.size(1.dp)
                            )
                            .focusRequester(focusRequester)
                            .onFocusChanged { focus ->
                                isFocused = focus.isFocused

                                // Tapping Send from inside this field is the case
                                // this exists for: without it the address being
                                // typed is silently dropped and the mail goes to
                                // whoever was already chipped, or to nobody.
                                if (!focus.isFocused) commit(isExplicit = false)
                            },
                )
            }

            // Beside the flow rather than in it, at the height of its first line:
            // in it, Cc / Bcc was one more item to wrap and took a line of its own.
            trailing?.let {
                Box(
                    modifier =
                        Modifier.padding(vertical = theme.spacing.tiny)
                            .heightIn(min = LINE_HEIGHT - theme.spacing.tiny * 2)
                            .padding(end = theme.spacing.small),
                    contentAlignment = Alignment.Center,
                ) {
                    it()
                }
            }
        }

        // Only while something is being typed into a line that still has the
        // cursor. A suggestion list that stays open over the subject field is
        // in the way rather than helpful.
        if (isFocused && typed.isNotBlank() && suggestions.isNotEmpty()) {
            SuggestionList(
                suggestions = suggestions,
                width = with(LocalDensity.current) { fieldWidthPx.toDp() },
                onPicked = ::pick,
            )
        }
    }

    // Cleared when the field goes away, so a stale list cannot reappear over the
    // next one.
    LaunchedEffect(Unit) { onQueryChanged("") }
}

/**
 * The matching contacts, as a menu hanging under the field.
 *
 * Three things were wrong with the list this replaces, and each one is a line of this.
 *
 * **It was part of the page.** An ordinary `Column` under the field, so eight matches made the
 * composer eight rows taller and pushed the subject and the message down the screen while somebody
 * was still typing an address — the list grew the window instead of covering it. A [Popup] is its
 * own window: nothing behind it moves, whatever it contains.
 *
 * **It had nothing to say it was floating.** Drawn flush on the same surface as the field, with the
 * same hairlines between rows the header uses, it read as four more fields rather than as a
 * transient list. So it is on [de.plmail.core.designsystem.PlMailColors.raised] behind an
 * unconditional hairline — unconditional because this floats over another surface, and the flat
 * layout's answer of "separate with a line on the page" has no page to draw on here. The same
 * argument `ComposeHost` makes for the dialog's border, one level down.
 *
 * **It was capped by nothing.** Now [MAX_HEIGHT] with a lazy list inside it, so a query matching
 * forty people is a list you scroll rather than a list that reaches the bottom of the screen.
 *
 * The radius is the *control* one rather than `pane` or `floating`. `pane` is zero in the flat
 * layout — right for a section of the page, and here it would take away half of what makes this
 * read as a separate object — and `floating`'s 18dp is a dialog's corner, far too generous on
 * something 36dp per row.
 */
@Composable
private fun SuggestionList(
    suggestions: List<EmailAddress>,
    width: Dp,
    onPicked: (EmailAddress) -> Unit,
) {
    val theme = LocalPlMailTheme.current
    val density = LocalDensity.current
    val shape = RoundedCornerShape(theme.radii.control)
    val gap = with(density) { theme.spacing.tiny.roundToPx() }

    // Read here rather than inside the popup, because a popup is its own window
    // and asks the system about its own insets. What the position needs to know
    // is how much of *this* window the keyboard has taken.
    val keyboard = WindowInsets.ime.getBottom(density)

    Popup(
        popupPositionProvider = remember(gap, keyboard) { UnderTheField(gap, keyboard) },
        // Not focusable, and this is load-bearing rather than a default left
        // alone: a focusable popup takes window focus the moment it opens, which
        // puts the keyboard away and drops the cursor out of the field the user
        // is still typing an address into. Touches reach a non-focusable popup
        // regardless, so the rows are as tappable as they ever were.
        properties = PopupProperties(focusable = false),
    ) {
        // Measured rather than assumed. A row is a line of text between two
        // spacing tokens, so its height moves with the density *and* with the
        // app's own font scale -- and a flat cap in dp therefore cuts the list
        // at an arbitrary point. The reported symptom was the giveaway: a half
        // row with no text in it, which is the cap landing in the padding under
        // the last line it could fit.
        var rowHeight by remember { mutableStateOf(0.dp) }
        val density = LocalDensity.current

        val listState = rememberLazyListState()

        // A whole number of rows, plus a deliberate slice of the next one.
        //
        // The slice is what says "there is more", and it has to cut through the
        // *text* to say it -- a gap of padding says the list has ended untidily.
        // Zero until the first row has been measured, which is one frame, and
        // the fallback for that frame is the flat cap this replaced.
        val cap =
            if (rowHeight <= 0.dp) MAX_HEIGHT
            else {
                // The pitch, not the row. Every row after the first carries a
                // hairline above it, and `onSizeChanged` measures the row alone
                // -- so an arithmetic in bare row heights drifts by a divider
                // per row and the peek ends up a quarter of what was asked for,
                // which is how this landed back in the padding the first time.
                val pitch = rowHeight + theme.spacing.hair
                val peek = pitch * ROW_PEEK
                val whole = ((MAX_HEIGHT - peek) / pitch).toInt().coerceAtLeast(1)

                // Minus one hairline, because the first row has no divider over
                // it and `whole` pitches have counted one for it.
                pitch * whole - theme.spacing.hair + peek
            }

        Box(
            modifier =
                Modifier.width(width)
                    .heightIn(max = cap)
                    .clip(shape)
                    .background(theme.colors.raised, shape)
                    .border(theme.spacing.hair, theme.colors.lineStrong, shape)
        ) {
            LazyColumn(state = listState) {
                itemsIndexed(suggestions) { index, suggestion ->
                    if (index > 0) PlMailDivider()

                    SuggestionRow(
                        suggestion = suggestion,
                        onClick = { onPicked(suggestion) },
                        // Only the first, because they are all the same height
                        // and a callback on every row would rewrite the cap
                        // while the list is being scrolled.
                        onMeasured =
                            if (index != 0) null
                            else ({ height -> rowHeight = with(density) { height.toDp() } }),
                    )
                }
            }

            ScrollHint(state = listState)
        }
    }
}

/**
 * A slim thumb on the trailing edge, drawn only while there is something to scroll to.
 *
 * Android draws no scrollbar for a `LazyColumn` unless one is asked for, and a floating list with a
 * clipped row at the bottom and nothing on its edge does not read as scrollable — the report that
 * prompted this was exactly that. A partial row alone is too quiet a signal when the thing it is
 * partial *in* is a small panel that could plausibly just be short.
 *
 * Derived from the layout rather than from an item count, so it stays honest if the rows ever stop
 * being a uniform height: the thumb is as long a fraction of the track as the viewport is of the
 * whole content, positioned by how far through that content the viewport has got.
 *
 * Not draggable. It is an indicator, not a control — the list is scrolled by touching the list, and
 * a 4dp target would be a control nobody could hit.
 */
@Composable
private fun BoxScope.ScrollHint(state: LazyListState) {
    val theme = LocalPlMailTheme.current
    val colour = theme.colors.lineStrong
    val density = LocalDensity.current
    val thumbWidth = with(density) { THUMB_WIDTH.toPx() }
    val inset = with(density) { theme.spacing.tiny.toPx() }

    // Everything is read inside the draw lambda, which is the point of drawing
    // it rather than laying it out. `layoutInfo` and `firstVisibleItemScrollOffset`
    // change on every frame of a fling, and reading them in composition
    // recomposes this subtree that often -- Compose's own lint fails the build
    // for it, correctly. In the draw phase the same reads cost a repaint of one
    // rectangle.
    Canvas(modifier = Modifier.matchParentSize()) {
        val info = state.layoutInfo
        val items = info.visibleItemsInfo
        if (items.isEmpty()) return@Canvas

        val viewport = (info.viewportEndOffset - info.viewportStartOffset).toFloat()
        if (viewport <= 0f) return@Canvas

        // The average is what makes this work for rows that are not all the same
        // height: with uniform rows it is exact, and with mixed ones the thumb
        // breathes a little rather than lying.
        val averageRow = items.sumOf { it.size }.toFloat() / items.size
        val content = averageRow * info.totalItemsCount

        // Everything fits, so there is nothing to hint at.
        if (content <= viewport) return@Canvas

        val fraction = (viewport / content).coerceIn(MIN_THUMB, 1f)
        val track = size.height - 2 * inset
        val thumb = (track * fraction).coerceAtLeast(thumbWidth)

        val scrolled = state.firstVisibleItemIndex * averageRow + state.firstVisibleItemScrollOffset
        val progress = (scrolled / (content - viewport)).coerceIn(0f, 1f)

        drawRoundRect(
            color = colour,
            topLeft = Offset(size.width - inset - thumbWidth, inset + progress * (track - thumb)),
            size = Size(thumbWidth, thumb),
            cornerRadius = CornerRadius(thumbWidth / 2f),
        )
    }
}

/**
 * One match, on one line where the data allows it.
 *
 * These were `ListItem`s, which is a 56dp row for a single line and 72dp for two — so a screen with
 * the keyboard up showed three matches. Name and address share a line here, at about 36dp at the
 * comfortable density and less at the others, which roughly doubles what fits without making
 * anything smaller to read: the padding is the app's spacing tokens, so the row packs with
 * everything else rather than being compact only where somebody typed a number.
 *
 * The address is dropped entirely when it *is* the name — a contact with no display name would
 * otherwise have its address printed twice on one line, which looks like a duplicate result.
 */
@Composable
private fun SuggestionRow(
    suggestion: EmailAddress,
    onClick: () -> Unit,
    onMeasured: ((Int) -> Unit)? = null,
) {
    val theme = LocalPlMailTheme.current
    val email = suggestion.email.orEmpty()
    val name = suggestion.name?.takeIf { it.isNotBlank() && it != email }

    Row(
        modifier =
            Modifier.fillMaxWidth()
                .then(
                    if (onMeasured == null) Modifier
                    else Modifier.onSizeChanged { onMeasured(it.height) }
                )
                .clickable(onClick = onClick)
                .padding(
                    horizontal = theme.spacing.medium,
                    vertical = theme.spacing.small,
                ),
        horizontalArrangement = Arrangement.spacedBy(theme.spacing.small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (name != null) {
            Text(
                text = name,
                style = MaterialTheme.typography.bodyMedium,
                color = theme.colors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                // fill = false so a short name takes only what it needs and the
                // address starts right after it; a name given half the row would
                // leave a gap in the middle of every result.
                modifier = Modifier.weight(1f, fill = false),
            )
        }

        Text(
            text = email,
            style =
                if (name == null) MaterialTheme.typography.bodyMedium
                else MaterialTheme.typography.bodySmall,
            color = if (name == null) theme.colors.ink else theme.colors.inkMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * Under the field, unless the keyboard is where "under" would put it.
 *
 * The usual answer is simply below the anchor. The flip matters for the Cc and Bcc lines, which sit
 * lower down a composer that has been scrolled: there, "below" can be behind the keys. [keyboard]
 * is how much of the window the IME has taken — the window itself does not shrink under
 * edge-to-edge, so `windowSize` alone would report room that is covered.
 */
private class UnderTheField(private val gap: Int, private val keyboard: Int) :
    PopupPositionProvider {

    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val below = anchorBounds.bottom + gap
        val above = anchorBounds.top - popupContentSize.height - gap
        val floor = windowSize.height - keyboard

        // Above only when it actually fits there. A list that does not fit
        // either way stays below, where at least its first rows are visible.
        val y = if (below + popupContentSize.height <= floor || above < 0) below else above

        return IntOffset(anchorBounds.left, y)
    }
}

/**
 * How tall the suggestion list may get.
 *
 * Six or seven of these rows at the comfortable density, which is more matches than a query worth
 * refining produces and few enough that the list never becomes the screen. Fixed rather than a
 * fraction of the window on purpose: what this bounds is how much of the message the list is
 * allowed to cover, and that is the same amount on a phone and on a tablet.
 */
private val MAX_HEIGHT = 240.dp

/** A line's height with nothing on it: the 48dp touch target, as the From row and subject have. */
private val LINE_HEIGHT = 48.dp

/** The narrowest the input gets before it takes a line of its own: room for a short address. */
private val MIN_INPUT_WIDTH = 96.dp

/**
 * How much of the row beyond the last whole one is left showing.
 *
 * Enough to cut through the text rather than through the padding under it. A row is a line of type
 * between two `small` gaps, so six tenths of it is the gap plus most of the glyphs — visibly a row
 * that continues, which is the whole job. Half was the accidental value before this and landed in
 * the padding, which is why the list looked like it ended in a blank strip.
 */
private const val ROW_PEEK = 0.6f

private val THUMB_WIDTH = 3.dp

/** The thumb never shrinks past this, or a long list gives it nothing to draw. */
private const val MIN_THUMB = 0.12f

/**
 * Turns a list of addresses that arrived as text into addresses — from a share intent or a
 * `mailto:` link, one entry per comma, semicolon or line.
 *
 * Accepts the `Name <address>` form as well as a bare address, because that is what other apps and
 * clients hand over. Anything that is not an address is dropped rather than sent: an address the
 * server would reject arrives back as a submission failure minutes later, by which point the
 * composer is long closed.
 *
 * **A space does not separate here**, unlike in [recipientPieces]. These lists were written by a
 * program, not typed, and a space inside one entry means the entry is broken — `URLDecoder` turning
 * `anna+lists@example.org` into `anna lists@example.org` is the case [Mailto] documents. Splitting
 * on it would turn that into a mail to `lists@example.org`, a stranger, where refusing it loses a
 * recipient visibly.
 */
internal fun String.parseAddresses(): List<EmailAddress> =
    splitAddresses()
        .map { it.trim().trim(' ', ',', ';') }
        .filter { it.isNotEmpty() }
        .mapNotNull { it.asRecipient() }

/**
 * One recipient as somebody wrote it, and what it means.
 *
 * [address] is null for a piece that is not an address. The piece is kept either way, because a
 * piece that is not an address is still something the user wrote, and it goes back into the field
 * for them to correct rather than disappearing.
 */
internal data class RecipientPiece(val text: String, val address: EmailAddress?)

/**
 * Picks the recipients out of typed or pasted text, the way the web composer does (pl_mail#379).
 *
 * **Each recipient is found, rather than the text being cut on separators.** A recipient is either
 * a name followed by `<address>` — the name may hold spaces, and commas inside quotes — or a run
 * with no space in it. So `;`, `,`, a new line, a space and a tab all separate addresses, while
 * `Jean Dupont <jd@x.fr>` and `"Doe, Jane" <jane@doe.fr>` each stay one recipient. Cutting on the
 * separators cannot do both: a space has to split `a@x.fr b@y.fr` and must not split `Jean Dupont`.
 *
 * **A name has no `@` outside quotes.** Without that rule the name runs back over whatever precedes
 * it, and `jo@jo.fr Jean Dupont <jd@x.fr>` became one recipient called "jo@jo.fr Jean Dupont" —
 * jo@jo.fr was never written to at all.
 *
 * Characters that belong to no recipient — the separators, a stray quote, a `<` that never closes —
 * are skipped, as on the web.
 */
internal fun String.recipientPieces(): List<RecipientPiece> =
    RECIPIENT.findAll(this)
        .map { it.value.trim() }
        .filter { it.isNotEmpty() }
        .map { piece -> RecipientPiece(text = piece, address = piece.asRecipient()) }
        .toList()

/** What a change to the address line means. */
internal sealed interface RecipientEdit {

    /** Nothing to make chips of: the text stays as it is, and is looked up among contacts. */
    data class Typing(val text: String) : RecipientEdit

    /**
     * Chips to add, and what the field keeps.
     *
     * [rejected] is every piece that is not an address, for the field to report; [remaining] is the
     * same pieces, back in the field for the user to correct.
     */
    data class Commit(
        val added: List<EmailAddress>,
        val remaining: String,
        val rejected: List<String>,
    ) : RecipientEdit
}

/**
 * What changing the line from [previous] to [value] means, with [existing] already chipped.
 *
 * **A typed character commits only when it ends a recipient** — a separator or a space. Committing
 * on anything else would chip `jo@x` while the user is still typing `jo@x.fr`, because `jo@x` is
 * already an address. **A paste commits at once**, because nothing more is coming: a list copied
 * out of another client rarely ends in a separator, and used to sit in the field as text until
 * Enter. A paste is told apart from typing by size, which is all a text field reports: more than
 * one character arriving at once.
 *
 * **Text with no address in it is never a commit.** `Jean ` is somebody typing a name to look up;
 * committing it found nothing and returned without keeping the space, so a two-word name could not
 * be searched for at all. A single plain address pasted on its own is left as text too, as it
 * always was, so the suggestions still open on it.
 */
internal fun recipientEdit(
    previous: String,
    value: String,
    existing: List<EmailAddress>,
): RecipientEdit {
    // Deleting never commits: backspacing to just after a space would chip
    // what is in front of it.
    if (value.length <= previous.length) return RecipientEdit.Typing(value)

    val isPaste = value.length - previous.length > 1
    val endsPiece = value.last().let { it == ',' || it == ';' || it.isWhitespace() }

    if (!isPaste && !endsPiece) return RecipientEdit.Typing(value)

    // An angle-address or a quoted name still being written by hand: the
    // space in `Jean <jd@x` must not cut it into three pieces.
    if (
        value.count { it == '<' } > value.count { it == '>' } || value.count { it == '"' } % 2 == 1
    ) {
        return RecipientEdit.Typing(value)
    }

    val pieces = value.recipientPieces()
    val single = pieces.singleOrNull()

    if (!endsPiece && single != null && single.text == single.address?.email) {
        return RecipientEdit.Typing(value)
    }

    return value.commitRecipients(existing)
}

/**
 * Makes chips of every address in [this]: what Enter, losing focus and a pasted list all do.
 *
 * Duplicates are dropped without regard to case, against [existing] and within the text itself: the
 * same person twice is sent two copies, which reads as a bug in the sender's client rather than a
 * slip. Text with no address in it at all is left exactly as typed — re-joining its pieces would
 * turn `Jean Dupont` into `Jean; Dupont`.
 */
internal fun String.commitRecipients(existing: List<EmailAddress>): RecipientEdit {
    val pieces = recipientPieces()

    if (pieces.none { it.address != null }) return RecipientEdit.Typing(this)

    val taken = existing.mapNotNull { it.email?.lowercase() }.toMutableSet()
    val added =
        pieces.mapNotNull { it.address }.filter { taken.add(it.email.orEmpty().lowercase()) }
    val rejected = pieces.filter { it.address == null }.map { it.text }

    return RecipientEdit.Commit(
        added = added,
        remaining = rejected.joinToString("; "),
        rejected = rejected,
    )
}

/**
 * The web composer's pattern, unchanged: `(?:"[^"]*"|[^<>"@;,\r\n])*<[^<>]*>|[^\s<>;,"]+`.
 *
 * The two alternatives of the name part cannot match the same character — one starts with a quote,
 * the other excludes it — so a failed match costs a pass over the run, not a combinatorial search.
 */
private val RECIPIENT = Regex("""(?:"[^"]*"|[^<>"@;,\r\n])*<[^<>]*>|[^\s<>;,"]+""")

private val ANGLED = Regex("""^(.*?)<([^<>]*)>$""")

private val MAILTO = Regex("""^mailto:""", RegexOption.IGNORE_CASE)

/**
 * One entry — `Name <address>`, `<address>` or a bare address — as an address, or null.
 *
 * `mailto:` in front is dropped: it is what a link copied out of a web page carries, and the
 * address under it is perfectly good.
 *
 * **What counts as an address is deliberately loose**: one `@`, something on each side, no space.
 * The web composer also wants a dot in the domain, and this does not: plMail is self-hosted, and
 * `anna@nas` on a home network is an address its owner really uses. Anything stricter is the
 * server's to say, and it does.
 *
 * **Loose, but not punctuation from around it.** `Jane Doe (jane@doe.fr)` and an address at the end
 * of a sentence, `jo@x.fr.`, both reach here as one run with no space in it. Taken as they are, they
 * became chips that looked right and failed only at submission, minutes later. Refused, they stay
 * in the field to be corrected, with the message saying which.
 */
private fun String.asRecipient(): EmailAddress? {
    val angled = ANGLED.find(this)

    val name = angled?.groupValues?.get(1)?.trim()?.trim('"')?.trim()?.takeIf { it.isNotEmpty() }
    val address = (angled?.groupValues?.get(2) ?: this).trim().replace(MAILTO, "")

    if (address.count { it == '@' } != 1) return null
    if (address.startsWith('@') || address.endsWith('@')) return null
    if (address.any { it.isWhitespace() || it in NOT_IN_ADDRESS }) return null

    // A dot that begins or ends either half, or two in a row, is never part
    // of an address: it is the full stop of the sentence it was copied from.
    if (address.split('@').any { it.startsWith('.') || it.endsWith('.') } || ".." in address) {
        return null
    }

    return EmailAddress(name = name, email = address)
}

/** Characters an unquoted address cannot hold: what surrounds one in prose and in headers. */
private const val NOT_IN_ADDRESS = "()[]<>\",;:\\"

/**
 * Splits on separators that are not inside a quoted display name or an angle-address.
 *
 * `"Meyer, Anna" <anna@example.org>` is an ordinary Outlook address and a plain `split(',')` tears
 * it in half — the second half has no `@` and is dropped, so the recipient becomes "Meyer" and
 * disappears. Someone with a comma in their name is not an edge case; it is how half of Europe's
 * corporate directories spell one.
 */
private fun String.splitAddresses(): List<String> {
    val parts = mutableListOf<String>()
    val current = StringBuilder()
    var inQuotes = false
    var inAngles = false

    forEach { character ->
        when {
            character == '"' -> {
                inQuotes = !inQuotes
                current.append(character)
            }
            character == '<' && !inQuotes -> {
                inAngles = true
                current.append(character)
            }
            character == '>' && !inQuotes -> {
                inAngles = false
                current.append(character)
            }
            (character == ',' || character == ';' || character == '\n') &&
                !inQuotes &&
                !inAngles -> {
                parts += current.toString()
                current.clear()
            }
            else -> current.append(character)
        }
    }

    parts += current.toString()

    return parts
}
