package cc.hyoban.moments.ledger

import java.io.InputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler

/**
 * The first sheet of an .xlsx as rows of text, read with the platform's own zip and SAX
 * rather than a spreadsheet library the APK would carry: the shared strings, inline strings
 * and numbers as written, a cell left out of a row an empty one.
 */
object Xlsx {
    fun read(input: InputStream): List<List<String>> {
        var strings: ByteArray? = null
        val sheets = sortedMapOf<String, ByteArray>()
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                when {
                    entry.name == "xl/sharedStrings.xml" -> strings = zip.readBytes()
                    entry.name.startsWith("xl/worksheets/sheet") && entry.name.endsWith(".xml") -> sheets[entry.name] = zip.readBytes()
                }
            }
        }
        val sheet = sheets["xl/worksheets/sheet1.xml"] ?: sheets.values.firstOrNull() ?: error("No sheet in the workbook")
        val shared = strings?.let(::sharedStrings).orEmpty()
        return rows(sheet, shared)
    }

    private fun parse(bytes: ByteArray, handler: DefaultHandler) {
        val factory = SAXParserFactory.newInstance().apply { isNamespaceAware = false }
        factory.newSAXParser().parse(InputSource(bytes.inputStream()), handler)
    }

    private fun sharedStrings(bytes: ByteArray): List<String> {
        val out = mutableListOf<String>()
        parse(bytes, object : DefaultHandler() {
            val text = StringBuilder()
            var inText = false
            // The phonetic runs Japanese text carries are no part of the string.
            var inPhonetic = false

            override fun startElement(uri: String?, localName: String?, qName: String, attributes: Attributes?) {
                when (qName.substringAfter(':')) {
                    "si" -> text.setLength(0)
                    "t" -> inText = !inPhonetic
                    "rPh" -> inPhonetic = true
                }
            }

            override fun endElement(uri: String?, localName: String?, qName: String) {
                when (qName.substringAfter(':')) {
                    "si" -> out += text.toString()
                    "t" -> inText = false
                    "rPh" -> inPhonetic = false
                }
            }

            override fun characters(ch: CharArray, start: Int, length: Int) {
                if (inText) text.append(ch, start, length)
            }
        })
        return out
    }

    private fun rows(bytes: ByteArray, shared: List<String>): List<List<String>> {
        val out = mutableListOf<List<String>>()
        parse(bytes, object : DefaultHandler() {
            var row: MutableList<String>? = null
            var column = 0
            var type: String? = null
            val value = StringBuilder()
            var inValue = false

            override fun startElement(uri: String?, localName: String?, qName: String, attributes: Attributes) {
                when (qName.substringAfter(':')) {
                    "row" -> row = mutableListOf()
                    "c" -> {
                        column = attributes.getValue("r")?.let(::columnOf) ?: row?.size ?: 0
                        type = attributes.getValue("t")
                        value.setLength(0)
                    }
                    "v", "t" -> inValue = true
                }
            }

            override fun endElement(uri: String?, localName: String?, qName: String) {
                when (qName.substringAfter(':')) {
                    "v", "t" -> inValue = false
                    "c" -> {
                        val cells = row ?: return
                        val text = value.toString()
                        val cell = if (type == "s") text.trim().toIntOrNull()?.let(shared::getOrNull).orEmpty() else text
                        while (cells.size < column) cells += ""
                        if (cells.size == column) cells += cell else cells[column] = cell
                    }
                    "row" -> row?.let(out::add).also { row = null }
                }
            }

            override fun characters(ch: CharArray, start: Int, length: Int) {
                if (inValue) value.append(ch, start, length)
            }
        })
        return out
    }

    /** `C12` as its column, from 0. */
    internal fun columnOf(reference: String): Int {
        var column = 0
        for (char in reference) {
            if (!char.isLetter()) break
            column = column * 26 + (char.uppercaseChar() - 'A' + 1)
        }
        return column - 1
    }
}
