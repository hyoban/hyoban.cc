package cc.hyoban.moments.data.spec

import kotlinx.serialization.json.Json

// The data spec packages/data-spec (@hyoban/data-spec) defines: what the API serves and
// the app keeps a copy of. Every record keeps the fields it doesn't know (see
// PassthroughSerializer), so a newer spec travels through unharmed.

object Spec {
    const val SCHEMA_VERSION = 1

    /** Reads and writes records the way the site's scripts do: two-space pretty JSON. */
    val json: Json = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = true
    }
}
