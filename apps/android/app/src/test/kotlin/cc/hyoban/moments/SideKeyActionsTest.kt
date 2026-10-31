package cc.hyoban.moments

import cc.hyoban.moments.sidekey.SideKeyActions
import cc.hyoban.moments.sidekey.SideKeyConfig
import cc.hyoban.moments.sidekey.SideKeyScene
import org.junit.Assert.*
import org.junit.Test

class SideKeyActionsTest {
    @Test fun acceptsOnlyExecutableActions() {
        listOf("none", "ledger:capture", "device:torch", "media:85", "nav:8", "app:org.example.player", "uri:https://example.invalid/path", "uri:moments://ledger")
            .forEach { assertTrue(it, SideKeyActions.valid(it)) }
        listOf("device:unknown", "app", "uri", "media:999", "nav:999", "app:", "app:org.example;reboot", "unknown:123")
            .forEach { assertFalse(it, SideKeyActions.valid(it)) }
    }

    @Test fun rejectsFileAndExecutableUrisAndControlCharacters() {
        listOf("", "example.invalid", "javascript:alert(1)", "FILE:/sdcard/private", "content://private/data", "intent://launch", "https://example.invalid/\npath")
            .forEach { assertFalse(it, SideKeyActions.validLink(it)) }
        assertTrue(SideKeyActions.validLink("example-app://page/1?q=value"))
    }

    @Test fun scenesComeBeforeDefaultsPaymentPageFirst() {
        val config = SideKeyConfig(
            actions = mapOf("single" to "device:torch", "double" to "none", "long" to "nav:8"),
            scenes = listOf(
                SideKeyScene(SideKeyScene.PAYMENT, actions = mapOf("single" to "ledger:capture")),
                SideKeyScene("app:com.tencent.mm", "WeChat", mapOf("single" to "nav:1", "double" to "media:85")),
            ),
        )
        assertEquals(null to "device:torch", config.resolve("single", false, "org.example.other"))
        assertEquals("ledger:capture", config.resolve("single", true, "com.tencent.mm").second)
        assertEquals("nav:1", config.resolve("single", false, "com.tencent.mm").second)
        // A gesture the payment page leaves unset goes on to the app's scene, then the default.
        assertEquals("media:85", config.resolve("double", true, "com.tencent.mm").second)
        assertEquals(null to "nav:8", config.resolve("long", true, "com.tencent.mm"))
        assertTrue(config.needsFront("double"))
        assertFalse(config.needsFront("long"))
    }
}
