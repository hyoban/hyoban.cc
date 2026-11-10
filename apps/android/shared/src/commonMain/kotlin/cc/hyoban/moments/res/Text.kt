package cc.hyoban.moments.res

import java.util.Locale
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.PluralStringResource
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getPluralString
import org.jetbrains.compose.resources.getString

// The app's words outside composition: a notification, a message raised from a store, a widget.
// Inside it, `stringResource` reads the same resources. Reading one waits only for its file the
// first time, as `stringResource` does.

/** [resource] in the system's language, its placeholders filled from [args]. */
fun textOf(resource: StringResource, vararg args: Any): String =
    runBlocking { if (args.isEmpty()) getString(resource) else getString(resource, *args) }

/** [resource] in the form the language uses for [quantity], its placeholders filled from [args]. */
fun pluralTextOf(resource: PluralStringResource, quantity: Int, vararg args: Any): String =
    runBlocking { if (args.isEmpty()) getPluralString(resource, quantity) else getPluralString(resource, quantity, *args) }

/** [value] to [digits] decimal places as the language writes them, for a placeholder: the resources fill in words, not number formats. */
fun decimals(value: Number, digits: Int): String = String.format(Locale.getDefault(), "%.${digits}f", value.toDouble())
