package cc.hyoban.moments

import cc.hyoban.moments.data.spec.Module
import cc.hyoban.moments.plugins.Plugins
import cc.hyoban.moments.plugins.Plugins.allowedRoute
import cc.hyoban.moments.plugins.Plugins.moduleOf
import cc.hyoban.moments.ui.Feed
import cc.hyoban.moments.ui.FootprintSettings
import cc.hyoban.moments.ui.LedgerBill
import cc.hyoban.moments.ui.LedgerHome
import cc.hyoban.moments.ui.Moment
import cc.hyoban.moments.ui.NewMoment
import cc.hyoban.moments.ui.Things
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RoutesTest {
    @Test fun `a page is its module's, the car's the car's and a Moment's none`() {
        assertEquals(Module.LEDGER, moduleOf(LedgerBill("x")))
        assertEquals(Module.FOOTPRINTS, moduleOf(FootprintSettings))
        assertEquals(Module.THINGS, moduleOf(Things("devices")))
        assertEquals(Module.CAR, moduleOf(Things("car")))
        assertNull(moduleOf(Things()))
        assertNull(moduleOf(Moment("x")))
        assertNull(moduleOf(Feed))
    }

    @Test fun `a page of a module that is off opens the Moments instead`() {
        val off = setOf(Module.LEDGER)
        val isOn = { module: Module -> module !in off }
        assertEquals(Feed, allowedRoute(LedgerHome(1), isOn))
        assertEquals(NewMoment(), allowedRoute(NewMoment(bill = true), isOn))
        assertEquals(Things("car"), allowedRoute(Things("car"), isOn))
        assertEquals(Moment("x"), allowedRoute(Moment("x"), isOn))
        assertEquals(LedgerHome(1), allowedRoute(LedgerHome(1)) { true })
    }

    @Test fun `Xiaohongshu's sign-in is a page of 我's, its plugin's, of no module`() {
        Plugins.install(cc.hyoban.moments.plugins.PhonePlugins.all)
        assertNull(moduleOf(cc.hyoban.moments.ui.NoteSignIn))
        assertEquals(listOf(cc.hyoban.moments.ui.Preferences, cc.hyoban.moments.ui.NoteSignIn), cc.hyoban.moments.ui.pagesOf(cc.hyoban.moments.ui.NoteSignIn))
        assertEquals(cc.hyoban.moments.ui.NoteSignIn, allowedRoute(cc.hyoban.moments.ui.NoteSignIn) { false })
    }

    @Test fun `every module has one plugin`() {
        assertEquals(Module.entries.size, Plugins.registry.all(cc.hyoban.moments.plugins.Points.modules).size)
        assertEquals(Module.entries.toList(), Plugins.inSpecOrder.map { it.module })
    }
}
