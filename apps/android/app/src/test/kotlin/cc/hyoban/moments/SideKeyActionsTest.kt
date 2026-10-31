package cc.hyoban.moments

import cc.hyoban.moments.sidekey.SideKeyActions
import org.junit.Assert.*
import org.junit.Test

class SideKeyActionsTest {
    @Test fun acceptsOnlyExecutableActions() {
        listOf("none", "ledger:capture", "media:85", "nav:8", "app:org.example.player", "uri:https://example.invalid/path", "uri:moments://ledger")
            .forEach { assertTrue(it, SideKeyActions.valid(it)) }
        listOf("app", "uri", "media:999", "nav:999", "app:", "app:org.example;reboot", "unknown:123")
            .forEach { assertFalse(it, SideKeyActions.valid(it)) }
    }

    @Test fun rejectsFileAndExecutableUrisAndControlCharacters() {
        listOf("", "example.invalid", "javascript:alert(1)", "FILE:/sdcard/private", "content://private/data", "intent://launch", "https://example.invalid/\npath")
            .forEach { assertFalse(it, SideKeyActions.validLink(it)) }
        assertTrue(SideKeyActions.validLink("example-app://page/1?q=value"))
    }
}
