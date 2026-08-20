package de.plmail.feature.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
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
import androidx.compose.ui.graphics.Color
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
) {
    var typed by remember { mutableStateOf("") }
    var isFocused by remember { mutableStateOf(false) }

    // What the suggestion list is measured against: it hangs under this field
    // and has to be exactly as wide as it, which is what makes it read as
    // belonging to the field rather than as a panel that happened to appear.
    var fieldWidthPx by remember { mutableIntStateOf(0) }

    fun commit(text: String = typed) {
        val parsed = text.parseAddresses()
        if (parsed.isEmpty()) return

        // De-duplicated against what is already on the line: adding the same
        // person twice sends them two copies and reads as a bug in the sender's
        // client rather than a slip.
        val existing = addresses.mapNotNull { it.email?.lowercase() }.toSet()

        onChanged(addresses + parsed.filter { it.email?.lowercase() !in existing })
        typed = ""
        onQueryChanged("")
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

    Column(modifier = modifier.fillMaxWidth()) {
        FlowRow(
            modifier = Modifier.fillMaxWidth().padding(horizontal = theme.spacing.gutter),
            horizontalArrangement = Arrangement.spacedBy(theme.spacing.tiny),
        ) {
            addresses.forEach { address ->
                InputChip(
                    selected = false,
                    onClick = { onChanged(addresses - address) },
                    label = { Text(address.display) },
                    trailingIcon = {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription =
                                stringResource(R.string.compose_remove_recipient, address.display),
                        )
                    },
                )
            }
        }

        // The Box is the suggestion list's anchor. It wraps the input only, not
        // the chips above it, so the list hangs off the line being typed into
        // rather than off the bottom of a field that has grown three rows of
        // recipients tall.
        Box(modifier = Modifier.fillMaxWidth()) {
            TextField(
                value = typed,
                onValueChange = { value ->
                    // A separator the user typed is a commit. Pasting "a@x, b@y"
                    // therefore lands as two chips rather than one unusable string.
                    if (value.endsWith(",") || value.endsWith(";") || value.endsWith(" ")) {
                        commit(value)
                    } else {
                        typed = value
                        onQueryChanged(value)
                    }
                },
                // A placeholder rather than a floating label: the label animating
                // up and shrinking on focus is a form's idiom, and this is a line
                // of an address book.
                //
                // One of the two, never both. The prefix appears once the line has
                // chips on it, so a line with a recipient and nothing typed drew
                // the prefix *and* the placeholder and read "To To" -- visible in
                // the first screenshot this component was ever captured in.
                placeholder = if (addresses.isEmpty()) ({ Text(label) }) else null,
                prefix = if (addresses.isEmpty()) null else ({ Text("$label ") }),
                trailingIcon = trailing,
                singleLine = true,
                keyboardOptions =
                    KeyboardOptions(
                        keyboardType = KeyboardType.Email,
                        imeAction = ImeAction.Done,
                    ),
                keyboardActions = KeyboardActions(onDone = { commit() }),
                colors =
                    TextFieldDefaults.colors(
                        focusedContainerColor = theme.colors.surface,
                        unfocusedContainerColor = theme.colors.surface,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        cursorColor = theme.colors.accent,
                        focusedPlaceholderColor = theme.colors.fieldPlaceholder,
                        unfocusedPlaceholderColor = theme.colors.fieldPlaceholder,
                    ),
                modifier =
                    Modifier.fillMaxWidth()
                        .onSizeChanged { fieldWidthPx = it.width }
                        .focusRequester(focusRequester)
                        .onFocusChanged { focus ->
                            isFocused = focus.isFocused

                            // Tapping Send from inside this field is the case
                            // this exists for: without it the address being
                            // typed is silently dropped and the mail goes to
                            // whoever was already chipped, or to nobody.
                            if (!focus.isFocused) commit()
                        },
            )

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
        Box(
            modifier =
                Modifier.width(width)
                    .heightIn(max = MAX_HEIGHT)
                    .clip(shape)
                    .background(theme.colors.raised, shape)
                    .border(theme.spacing.hair, theme.colors.lineStrong, shape)
        ) {
            LazyColumn {
                itemsIndexed(suggestions) { index, suggestion ->
                    if (index > 0) PlMailDivider()

                    SuggestionRow(suggestion = suggestion, onClick = { onPicked(suggestion) })
                }
            }
        }
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
private fun SuggestionRow(suggestion: EmailAddress, onClick: () -> Unit) {
    val theme = LocalPlMailTheme.current
    val email = suggestion.email.orEmpty()
    val name = suggestion.name?.takeIf { it.isNotBlank() && it != email }

    Row(
        modifier =
            Modifier.fillMaxWidth()
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

/**
 * Turns typed text into addresses.
 *
 * Accepts the `Name <address>` form as well as a bare address, because that is what pasting from
 * another mail client gives you. Anything with no `@` at all is dropped rather than sent: an
 * address the server would reject arrives back as a submission failure minutes later, by which
 * point the composer is long closed.
 */
internal fun String.parseAddresses(): List<EmailAddress> =
    splitAddresses()
        .map { it.trim().trim(' ', ',', ';') }
        .filter { it.isNotEmpty() }
        .mapNotNull { entry ->
            val angled = Regex("""^(.*?)<([^>]+)>$""").find(entry)

            val name = angled?.groupValues?.get(1)?.trim()?.trim('"')?.takeIf { it.isNotEmpty() }
            val address = (angled?.groupValues?.get(2) ?: entry).trim()

            if (!address.contains('@') || address.count { it == '@' } != 1) return@mapNotNull null
            if (address.startsWith('@') || address.endsWith('@')) return@mapNotNull null

            EmailAddress(name = name, email = address)
        }

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
