package cc.hyoban.moments.data

import cc.hyoban.moments.data.trips.Routers
import cc.hyoban.moments.footprints.csvCells
import cc.hyoban.moments.ledger.Xlsx
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals

class ReadersTest {
    @Test
    fun aQuotedCellKeepsItsCommas() {
        assertEquals(listOf("1", "a, b", "say \"hi\"", ""), csvCells("1,\"a, b\",\"say \"\"hi\"\"\","))
    }

    @Test
    fun aPolylineCutShortKeepsThePointsBeforeIt() {
        // Google's example line at precision 5, read here as precision 6: three points.
        val whole = "_p~iF~ps|U_ulLnnqC_mqNvxq`@"
        assertEquals(3, Routers.decodePolyline(whole).size)
        assertEquals(2, Routers.decodePolyline(whole.dropLast(2)).size)
        assertEquals(0, Routers.decodePolyline("_").size)
    }

    @Test
    fun withoutSheet1TheLowestNumberedSheetIsRead() {
        val bytes = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { zip ->
                fun put(name: String, text: String) {
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(text.toByteArray())
                    zip.closeEntry()
                }
                put("xl/worksheets/sheet10.xml", sheet("ten"))
                put("xl/worksheets/sheet2.xml", sheet("two"))
            }
        }.toByteArray()
        assertEquals(listOf(listOf("two")), Xlsx.read(bytes.inputStream()))
    }

    private fun sheet(value: String) =
        """<worksheet><sheetData><row r="1"><c r="A1" t="inlineStr"><is><t>$value</t></is></c></row></sheetData></worksheet>"""
}
