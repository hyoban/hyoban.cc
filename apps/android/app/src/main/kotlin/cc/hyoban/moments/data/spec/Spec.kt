package cc.hyoban.moments.data.spec

import kotlinx.serialization.json.Json

// The data spec the site's src/data/spec.ts defines: what the app stores, the bundle
// it exports and the receipt it takes back. Every record keeps the fields it doesn't
// know (see PassthroughSerializer), so a newer spec travels through unharmed.

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
