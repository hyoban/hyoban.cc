package cc.hyoban.moments

import cc.hyoban.moments.data.spec.Module
import cc.hyoban.moments.plugins.Plugins
import cc.hyoban.moments.plugins.Points
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 发布更改 lists what changed of every part an entry can have: each part the spec has, but the
 * words and photos that are the host's, has its plugin's diff, so a new part comes with its lines.
 */
class PartDiffsTest {
    /** The parts the spec's `entryPartsSchema` lists, by their keys. */
    private val specParts: List<String> by lazy {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "pnpm-workspace.yaml").exists() }
        val source = File(root, "packages/data-spec/src/entry.ts").readText()
        val block = Regex("""export const entryPartsSchema = z\.looseObject\(\{(.*?)\n\}\)""", RegexOption.DOT_MATCHES_ALL)
            .find(source)?.groupValues?.get(1) ?: error("entryPartsSchema not found in entry.ts")
        Regex("""^\s*(\w+):""", RegexOption.MULTILINE).findAll(block).map { it.groupValues[1] }.toList()
    }

    /** The parts every Moment has, whose lines the page draws itself. */
    private val hostParts = setOf("text", "media")

    @Test fun `every part the spec has but the words and photos has a diff`() {
        assertTrue("expected the spec's parts", specParts.containsAll(hostParts) && specParts.size > hostParts.size)
        val diffed = Plugins.registry.all(Points.partDiffs).map { it.part }
        val missing = specParts.filter { it !in hostParts && it !in diffed }
        assertEquals("parts with no diff on 发布更改", emptyList<String>(), missing)
    }

    @Test fun `each part is diffed once, by the plugin of the module that owns it`() {
        val contributions = Plugins.registry.contributions(Points.partDiffs)
        assertEquals(contributions.map { it.item.part }.distinct(), contributions.map { it.item.part })
        contributions.forEach { contribution ->
            val part = contribution.item.part
            assertEquals("the module owning $part", Module.entries.firstOrNull { part in it.plugins }, contribution.module)
        }
        // Every module's parts are among the spec's, and so are diffed.
        assertTrue(Module.entries.flatMap { it.plugins }.all { it in specParts })
    }
}
