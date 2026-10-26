package cc.hyoban.moments.footprints

import java.io.File
import java.io.IOException
import java.io.StringWriter
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FootprintDiagnosticsTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun exportDrainsPendingEventsAndPreservesJsonAndOrdering() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val log = FootprintDiagnostics(temp.newFolder(), scope)
            log.record("first", mapOf("reason" to "省电\n\"等待\"", "invalid" to Double.NaN))
            log.record("second", mapOf("stored" to true, "accuracy_m" to 12.5))
            val output = StringWriter()
            log.export(output)
            val rows = output.toString().lineSequence().filter { it.isNotBlank() }.map { Json.parseToJsonElement(it).jsonObject }.toList()
            assertEquals(listOf("first", "second"), rows.map { it.getValue("event").jsonPrimitive.content })
            assertEquals("省电\n\"等待\"", rows[0].getValue("reason").jsonPrimitive.content)
            assertFalse(rows[0].containsKey("invalid"))
            assertTrue(rows[1].getValue("stored").jsonPrimitive.boolean)
        } finally { scope.cancel() }
    }

    @Test fun rotationBoundsDiskUsageAndKeepsRecentEventsAcrossRestart() = runBlocking {
        val folder = temp.newFolder()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val log = FootprintDiagnostics(folder, scope, fileLimit = 512)
            for (i in 0..19) log.record("sample", mapOf("sequence" to i, "detail" to "x".repeat(90)))
            log.export(StringWriter())
        } finally { scope.cancel() }
        assertTrue(folder.listFiles()!!.sumOf { it.length() } <= 1024)
        assertEquals(2, folder.listFiles()!!.size)
        val nextScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val log = FootprintDiagnostics(folder, nextScope, fileLimit = 512)
            val output = StringWriter()
            log.export(output)
            val rows = output.toString().lineSequence().filter { it.isNotBlank() }.map { Json.parseToJsonElement(it).jsonObject }.toList()
            assertEquals(19, rows.last().getValue("sequence").jsonPrimitive.int)
            assertTrue(rows.first().getValue("sequence").jsonPrimitive.int > 0)
        } finally { nextScope.cancel() }
    }

    @Test fun writeFailureDoesNotKillWorkerAndRecoveryReportsLostEvents() = runBlocking {
        val folder = temp.newFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val log = FootprintDiagnostics(folder, scope)
            log.record("cannot_write")
            try { log.export(StringWriter()); fail("Export should report the storage failure") } catch (_: IOException) { }
            assertTrue(folder.delete())
            assertTrue(folder.mkdir())
            log.record("recovered")
            val output = StringWriter()
            log.export(output)
            val rows = output.toString().lineSequence().filter { it.isNotBlank() }.map { Json.parseToJsonElement(it).jsonObject }.toList()
            assertEquals("diagnostic_events_lost", rows.first().getValue("event").jsonPrimitive.content)
            assertEquals(1, rows.first().getValue("count").jsonPrimitive.int)
            assertEquals("recovered", rows.last().getValue("event").jsonPrimitive.content)
        } finally { scope.cancel() }
    }

    @Test fun anInterruptedAppendDoesNotCorruptSubsequentRecords() = runBlocking {
        val folder = temp.newFolder()
        File(folder, "current.jsonl").writeText("{\"event\":\"before_restart\"}\n{\"event\":\"unfinished")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val log = FootprintDiagnostics(folder, scope)
            log.record("after_restart")
            val output = StringWriter()
            log.export(output)
            val names = output.toString().lineSequence().filter { it.isNotBlank() }
                .map { Json.parseToJsonElement(it).jsonObject.getValue("event").jsonPrimitive.content }.toList()
            assertEquals(listOf("before_restart", "after_restart"), names)
        } finally { scope.cancel() }
    }
}
