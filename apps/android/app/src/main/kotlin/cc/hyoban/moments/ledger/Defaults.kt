package cc.hyoban.moments.ledger

import cc.hyoban.moments.data.local.Ulid

/** The categories a new book starts with, first-level ones with their second-level ones. */
private val EXPENSE_ZH = listOf(
    "餐饮" to listOf("三餐", "零食", "饮料", "水果"),
    "交通" to listOf("公交", "地铁", "打车", "火车", "飞机"),
    "购物" to listOf("日用", "服饰", "数码"),
    "住房" to listOf("房租", "水费", "电费"),
    "通讯" to listOf("话费", "网络"),
    "娱乐" to emptyList(),
    "医疗" to emptyList(),
    "人情" to listOf("礼物", "红包"),
    "旅行" to listOf("住宿", "门票"),
    "其他" to emptyList(),
)
private val INCOME_ZH = listOf("工资", "奖金", "红包", "利息", "二手", "其他")

private val EXPENSE_EN = listOf(
    "Food" to listOf("Meals", "Snacks", "Drinks", "Fruit"),
    "Transport" to listOf("Bus", "Metro", "Taxi", "Train", "Flight"),
    "Shopping" to listOf("Daily", "Clothes", "Gadgets"),
    "Housing" to listOf("Rent", "Water", "Electricity"),
    "Phone" to listOf("Mobile", "Internet"),
    "Fun" to emptyList(),
    "Health" to emptyList(),
    "Gifts" to listOf("Presents", "Red packets"),
    "Travel" to listOf("Stays", "Tickets"),
    "Other" to emptyList(),
)
private val INCOME_EN = listOf("Salary", "Bonus", "Gifts", "Interest", "Sold", "Other")

/** The categories of a new book of [book]'s id. */
fun defaultCategories(book: String, chinese: Boolean): List<Category> {
    val out = mutableListOf<Category>()
    (if (chinese) EXPENSE_ZH else EXPENSE_EN).forEach { (name, children) ->
        val parent = Category(Ulid.next(), book, CategoryType.EXPENSE, name, position = out.size)
        out += parent
        children.forEach { out += Category(Ulid.next(), book, CategoryType.EXPENSE, it, parent = parent.id, position = out.size) }
    }
    (if (chinese) INCOME_ZH else INCOME_EN).forEach { out += Category(Ulid.next(), book, CategoryType.INCOME, it, position = out.size) }
    return out
}

/** A new book named [name] with the default categories, made the one shown. */
suspend fun LedgerStore.addBook(name: String, currency: String, chinese: Boolean): Book {
    val book = Book(Ulid.next(), name, currency, position = ledger.value.books.size)
    putAll(listOf(book), emptyList(), defaultCategories(book.id, chinese), emptyList(), replace = false)
    setCurrentBook(book.id)
    return book
}

/** The book shown, made first with its categories when the ledger has none. */
suspend fun LedgerStore.ensureBook(defaultName: String, chinese: Boolean): Book =
    ensureDefaultBook(defaultName, chinese)
