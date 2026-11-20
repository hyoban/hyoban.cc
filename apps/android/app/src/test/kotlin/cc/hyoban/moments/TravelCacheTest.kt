package cc.hyoban.moments

import cc.hyoban.moments.data.local.Fix
import cc.hyoban.moments.data.trips.KnownTravel
import cc.hyoban.moments.data.trips.Routers
import cc.hyoban.moments.data.trips.TravelCache
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The routers' answers kept past a restart, a way asked once. */
class TravelCacheTest {
    @Test
    fun aWayIsKeptPastARestart() = runBlocking {
        val file = kotlin.io.path.createTempFile("travel", ".json").toFile().also { it.delete() }
        val a = Fix(35.6895, 139.6917)
        val b = Fix(35.6586, 139.7454)
        TravelCache.open(file)
        assertNull(TravelCache.known(a, b, Routers.By.DRIVE))
        TravelCache.keep(a, b, Routers.By.DRIVE, Routers.Travel(1_320, 9_600))
        // That there is no way is kept too.
        TravelCache.keep(a, b, Routers.By.TRAIN, null)
        // As if the app started again: read back from the file.
        TravelCache.open(file)
        assertEquals(KnownTravel(Routers.Travel(1_320, 9_600)), TravelCache.known(a, b, Routers.By.DRIVE))
        assertEquals(KnownTravel(null), TravelCache.known(a, b, Routers.By.TRAIN))
        // A few metres off is the same way; another mode isn't.
        assertEquals(KnownTravel(Routers.Travel(1_320, 9_600)), TravelCache.known(Fix(35.68952, 139.69171), b, Routers.By.DRIVE))
        assertNull(TravelCache.known(a, b, Routers.By.WALK))
        file.delete()
        Unit
    }
}
