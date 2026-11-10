package cc.hyoban.moments

import cc.hyoban.moments.nas.NasProtocol
import cc.hyoban.moments.ui.picker.PhotoHeaps
import cc.hyoban.moments.ui.picker.Spot
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoHeapsTest {
    // Made-up points: three in one street, one across town, one in another city.
    private val street = listOf(Spot(1, 30.2500, 120.1600), Spot(2, 30.2502, 120.1603), Spot(3, 30.2498, 120.1598))
    private val town = Spot(4, 30.2900, 120.2100)
    private val far = Spot(5, 31.2300, 121.4700)
    private val all = street + town + far

    @Test
    fun heapsWhatStandsNearAndPartsItCloser() {
        // The whole country on screen: the two cities are 64 points apart or less, one heap.
        assertEquals(listOf(5), PhotoHeaps.at(all, 3.0, 64.0).map { it.count })
        // A province: the cities part, the town still with its street.
        assertEquals(listOf(4, 1), PhotoHeaps.at(all, 8.0, 64.0).map { it.count })
        // The town: the street is one heap, the far side of town its own.
        assertEquals(listOf(3, 1, 1), PhotoHeaps.at(all, 13.0, 64.0).map { it.count })
        // The street itself: every photo alone.
        assertEquals(5, PhotoHeaps.at(all, 20.0, 64.0).size)
    }

    @Test
    fun keepsEveryPhotoOnceWithItsFirstAsCover() {
        for (zoom in 0..20) {
            val heaps = PhotoHeaps.at(all, zoom.toDouble(), 64.0)
            assertEquals(all.map { it.id }.sorted(), heaps.flatMap { heap -> heap.spots.map { it.id } }.sorted())
        }
        val one = PhotoHeaps.at(street, 13.0, 64.0).single()
        assertEquals(1L, one.cover.id)
        assertTrue(one.latitude in 30.2498..30.2502 && one.longitude in 120.1598..120.1603)
    }

    @Test
    fun standsAHeapAmongItsPhotos() {
        for (zoom in listOf(2.5, 5.0, 9.0)) PhotoHeaps.at(all, zoom, 64.0).forEach { heap ->
            assertTrue("$zoom: ${heap.latitude}, ${heap.longitude}", heap.latitude in 30.2..31.3 && heap.longitude in 120.1..121.5)
        }
    }

    @Test
    fun joinsNeighboursAcrossACellsEdge() {
        // Two photos a few metres apart whatever the grid: never two heaps at a zoom where they'd overlap.
        val pair = listOf(Spot(1, 0.00001, 0.00001), Spot(2, -0.00001, -0.00001))
        for (zoom in 0..16) assertEquals(1, PhotoHeaps.at(pair, zoom.toDouble(), 64.0).size)
    }

    @Test
    fun heapsByTheHalfZoom() {
        assertEquals(12.5, PhotoHeaps.step(12.9), 0.0)
        assertEquals(12.0, PhotoHeaps.step(12.4), 0.0)
    }

    @Test
    fun readsTheNasMapsList() {
        val data = Json.parseToJsonElement(
            """{"list":[
                {"picture_id":11,"latitude":"30.25","longitude":"120.16","upload_time":1700000000},
                {"picture_id":12,"latitude":30.5,"longitude":120.5},
                {"picture_id":13,"latitude":"","longitude":""},
                {"picture_id":14,"latitude":"0","longitude":"0"},
                {"latitude":"1","longitude":"1"}
            ]}""",
        ).jsonObject
        val spots = NasProtocol.spots(data)
        assertEquals(listOf(11L, 12L), spots.map { it.id })
        assertEquals(30.25, spots[0].latitude, 0.0)
        assertEquals(120.16, spots[0].longitude, 0.0)
        assertEquals(1700000000L, spots[0].uploaded)
        assertEquals(0L, spots[1].uploaded)
    }
}
