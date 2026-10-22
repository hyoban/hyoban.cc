package cc.hyoban.moments

import cc.hyoban.moments.ui.map.FogOfWar
import cc.hyoban.moments.ui.map.buildFogOfWar
import cc.hyoban.moments.ui.map.footprintDistances
import cc.hyoban.moments.ui.map.parseFogRegions
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A city is lit by a place inside it, as on the site's map; the rest of the world never is. */
class FogOfWarTest {
    private fun square(west: Int, south: Int, east: Int, north: Int) =
        "[[$west,$south],[$east,$south],[$east,$north],[$west,$north],[$west,$south]]"

    private val regions = parseFogRegions(
        """
        {"type":"FeatureCollection","features":[
          {"type":"Feature","properties":{"id":"320100","name":"南京市"},
           "geometry":{"type":"Polygon","coordinates":[${square(0, 0, 10, 10)},${square(4, 4, 6, 6)}]}},
          {"type":"Feature","properties":{"id":"320200","name":"无锡市"},
           "geometry":{"type":"MultiPolygon","coordinates":[[${square(10, 0, 20, 10)}],[${square(30, 0, 31, 1)}]]}},
          {"type":"Feature","properties":{"id":"340100","name":"合肥市"},
           "geometry":{"type":"Polygon","coordinates":[${square(0, 10, 10, 20)}]}},
          {"type":"Feature","properties":{"fogged":true,"id":"outside","name":""},
           "geometry":{"type":"Polygon","coordinates":[${square(-180, -85, 180, 85)}]}}
        ]}
        """,
    )

    @Test
    fun `a place lights the city it is in, once however many are there`() {
        assertEquals(
            FogOfWar(listOf("320100", "320200"), cities = 2, provinces = 1),
            buildFogOfWar(regions, listOf(2.0 to 2.0, 3.0 to 3.0, 30.5 to 0.5)),
        )
    }

    @Test
    fun `a place just off a city lights it, one far off or in a hole nothing`() {
        assertEquals(listOf("320100"), buildFogOfWar(regions, listOf(-0.05 to 3.0)).lit)
        assertEquals(emptyList(), buildFogOfWar(regions, listOf(5.0 to 5.0, 50.0 to 50.0)).lit)
    }

    @Test
    fun `cities in two provinces count two`() {
        assertEquals(2, buildFogOfWar(regions, listOf(1.0 to 1.0, 1.0 to 11.0)).provinces)
    }

    @Test
    fun `the fog sweeps by how far each city is from the nearest one lit`() {
        val distances = footprintDistances(regions, listOf("320100"))!!
        assertEquals(0.0, distances["320100"])
        assertEquals(10.0, distances["340100"]!!, 1e-9)
        assertTrue(distances["320200"]!! < 15.5)
        assertNull(footprintDistances(regions, emptyList()))
    }

    @Test
    fun `the site's cities light Nanjing`() {
        val file = File("../../../src/data/boundaries/cities.json").takeIf { it.exists() } ?: return
        val fog = buildFogOfWar(parseFogRegions(file.readText()), listOf(118.7969 to 32.0603))
        assertEquals(listOf("320100"), fog.lit)
        assertTrue(parseFogRegions(file.readText()).any { it.fogged })
    }
}
