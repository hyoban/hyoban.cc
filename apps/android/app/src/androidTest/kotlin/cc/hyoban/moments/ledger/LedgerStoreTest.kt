package cc.hyoban.moments.ledger

import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** Uses an isolated database and never writes to the user's ledger. */
class LedgerStoreTest {
    private val base = InstrumentationRegistry.getInstrumentation().targetContext
    private val directory = File(base.cacheDir, "ledger-test-${UUID.randomUUID()}").apply { mkdirs() }
    private val context = object : ContextWrapper(base) {
        override fun getNoBackupFilesDir(): File = directory
    }
    private var store: LedgerStore? = null

    @After fun cleanup() {
        store?.close()
        directory.deleteRecursively()
    }

    @Test fun openingBeforeLoadPreservesExistingBooks() = runBlocking {
        val existing = Book("existing", "Existing book")
        LedgerStore(context).use { seed -> seed.saveBook(existing) }
        val ledger = LedgerStore(context).also { store = it }
        val pending = async(start = CoroutineStart.UNDISPATCHED) { ledger.ensureBook("Default book", false) }
        assertFalse(pending.isCompleted)
        ledger.load()
        assertEquals(existing, pending.await())
        assertEquals(listOf(existing), ledger.ledger.value.books)
    }

    @Test fun simultaneousFirstEntriesCreateOnlyOneBook() = runBlocking {
        val ledger = LedgerStore(context).also { store = it }
        ledger.load()
        val books = List(8) { async(Dispatchers.Default) { ledger.ensureBook("Default book", false) } }.awaitAll()
        assertEquals(1, books.map { it.id }.toSet().size)
        assertEquals(listOf(books.first()), ledger.ledger.value.books)
        assertEquals(defaultCategories("unused", false).size, ledger.ledger.value.categories.size)
        assertEquals(books.first().id, ledger.ledger.value.currentBook)
    }
}
