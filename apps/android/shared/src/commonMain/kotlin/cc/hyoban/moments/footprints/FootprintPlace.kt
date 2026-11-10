package cc.hyoban.moments.footprints

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import cc.hyoban.moments.ledger.Txn

/** How the device names where a bill was from its footprints; nothing where it keeps none. */
val LocalFootprintPlace = staticCompositionLocalOf<@Composable (Txn) -> String?> { { null } }

/** The name of where [txn] was, by the device's footprints, for a row of bills. */
@Composable
fun footprintPlace(txn: Txn): String? = LocalFootprintPlace.current(txn)
