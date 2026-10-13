package cc.hyoban.moments

import cc.hyoban.moments.data.spec.Ulid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class UlidTest {
    @Test
    fun `sorts by time and gives the time back`() {
        val time = 1_790_000_000_000
        val first = Ulid.new(time)
        val second = Ulid.new(time + 1)

        assertTrue(Ulid.PATTERN.matches(first), first)
        assertEquals(time, Ulid.timeOf(first))
        assertTrue(first < second)
        assertNotEquals(first, Ulid.new(time))
    }
}
