package cc.hyoban.moments

import cc.hyoban.moments.data.spec.Module
import cc.hyoban.moments.ui.Feed
import cc.hyoban.moments.ui.FootprintSettings
import cc.hyoban.moments.ui.LedgerBill
import cc.hyoban.moments.ui.LedgerHome
import cc.hyoban.moments.ui.Moment
import cc.hyoban.moments.ui.NewMoment
import cc.hyoban.moments.ui.Things
import cc.hyoban.moments.ui.allowedRoute
import cc.hyoban.moments.ui.moduleOf
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
}
