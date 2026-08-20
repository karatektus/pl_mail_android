package de.plmail.feature.compose

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import de.plmail.core.designsystem.LocalPlMailTheme
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
            // the prefix *and* the placeholder and read "To To".
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
                Modifier.fillMaxWidth().focusRequester(focusRequester).onFocusChanged { focus ->
                    // Tapping Send from inside this field is the case this
                    // exists for: without it the address being typed is
                    // silently dropped and the mail goes to whoever was already
                    // chipped, or to nobody.
                    if (!focus.isFocused) commit()
                },
        )

        // Only while something is being typed. A suggestion list that stays open
        // over the subject field is in the way rather than helpful.
        if (typed.isNotBlank()) {
            suggestions.forEach { suggestion ->
                ListItem(
                    headlineContent = { Text(suggestion.name ?: suggestion.email.orEmpty()) },
                    supportingContent =
                        suggestion.name?.let { { Text(suggestion.email.orEmpty()) } },
                    modifier =
                        Modifier.fillMaxWidth().clickable {
                            commit(suggestion.email.orEmpty())
                        },
                )
            }
        }
    }

    // Cleared when the field goes away, so a stale list cannot reappear over the
    // next one.
    LaunchedEffect(Unit) { onQueryChanged("") }
}

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
