package cc.hyoban.moments.ledger

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** The ledger as the pages read it; the phone's store keeps it, and a device with none has an empty one. */
interface LedgerSource {
    /** Whether the device keeps a ledger at all: where it doesn't, nothing offers to write a bill. */
    val kept: Boolean get() = true

    val ledger: StateFlow<Ledger>

    /** Keeps [book] as changed: its name, or who sees its new bills. */
    suspend fun saveBook(book: Book)
    suspend fun saveTxn(txn: Txn)
    suspend fun saveCategory(category: Category)
    suspend fun saveAccount(account: Account)

    /** Makes the first book, named [defaultName] with the usual categories, when there is none yet. */
    suspend fun ensureBook(defaultName: String, chinese: Boolean)
}

/** No ledger: a device that keeps none. */
object NoLedger : LedgerSource {
    override val kept: Boolean get() = false
    override val ledger: StateFlow<Ledger> = MutableStateFlow(Ledger())
    override suspend fun saveBook(book: Book) {}
    override suspend fun saveTxn(txn: Txn) {}
    override suspend fun saveCategory(category: Category) {}
    override suspend fun saveAccount(account: Account) {}
    override suspend fun ensureBook(defaultName: String, chinese: Boolean) {}
}
