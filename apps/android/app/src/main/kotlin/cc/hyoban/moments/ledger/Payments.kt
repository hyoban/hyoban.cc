package cc.hyoban.moments.ledger

import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs

// 自动记账: a payment read off WeChat Pay's or Alipay's page as it is shown, offered as a bill
// to write. Plain Kotlin over the page's words, so the pages' fixtures can be tested.

/** The apps whose payment pages are read, while 自动记账 is on. */
enum class PaymentApp(val packageName: String) {
    WECHAT("com.tencent.mm"),
    ALIPAY("com.eg.android.AlipayGphone");

    companion object {
        fun of(packageName: String?) = entries.firstOrNull { it.packageName == packageName }
    }
}

/** A payment as its page shows it: how much, the bill's note, paid with what, and when, where it says. */
data class Payment(
    val app: PaymentApp,
    val amount: Long,
    /** What the bill's note starts as: Alipay's 商品说明, WeChat's payee over the amount. */
    val note: String? = null,
    val method: String? = null,
    /** Epoch milliseconds; null when the page doesn't say, and the time it is seen is taken. */
    val time: Long? = null,
)

object PaymentPage {
    /** Words saying the money has gone: a payment's own page, or its bill's. */
    private val PAID = listOf("支付成功", "付款成功", "交易成功", "已支付", "已付款")
    /** A status that isn't an expense: money in, back or not gone. */
    private val NOT_PAID = listOf("已收款", "收款成功", "已收钱", "已到账", "退款成功", "已退款", "全额退款", "转入成功", "待支付", "待付款", "支付失败", "交易关闭", "已关闭")
    private val PAGE_TITLES = setOf("账单详情", "交易详情", "支付详情", "付款详情")
    /** An amount as the pages read it out, which isn't always as they draw it: Alipay's -12.50 is 支出12.5元. */
    private val AMOUNT = Regex("""^(支出|收入|[-−+]\s*)?([¥￥]\s*)?(\d{1,3}(?:,\d{3})+|\d+)(\.\d{1,2})?(元)?$""")
    private val TIMES = listOf("yyyy年M月d日 HH:mm:ss", "yyyy年M月d日 HH:mm", "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm", "yyyy/MM/dd HH:mm:ss")
        .map { DateTimeFormatter.ofPattern(it) }

    /**
     * The payment shown in [words], the texts and descriptions of [app]'s page in reading order,
     * or null for any page that isn't a payment made: an amount on it, its status a paid one and
     * nothing of money coming in.
     */
    fun read(app: PaymentApp, words: List<String>, zone: ZoneId = ZoneId.systemDefault()): Payment? {
        val texts = words.map { it.trim() }.filter { it.isNotEmpty() }
        if (texts.none { text -> PAID.any { text.contains(it) } }) return null
        if (texts.any { text -> text.length <= 16 && NOT_PAID.any { text.contains(it) } }) return null
        // The amount: the first that looks like one, a sign or a currency mark on it, or after a lone ¥.
        var at = -1
        var amount: Long? = null
        for ((i, text) in texts.withIndex()) {
            val match = AMOUNT.matchEntire(text) ?: continue
            val marked = match.groupValues[1].isNotEmpty() || match.groupValues[2].isNotEmpty() ||
                match.groupValues[5].isNotEmpty() || texts.getOrNull(i - 1) in setOf("¥", "￥")
            if (!marked) continue
            if (text.startsWith("+") || text.startsWith("收入")) return null
            amount = cents(match.groupValues[3].replace(",", "") + match.groupValues[4])
            at = i
            break
        }
        if (amount == null || amount <= 0) return null
        // Each app's rows to the fields, as they are. 支付时间 is the time; the account is
        // Alipay's 付款方式 and WeChat's 支付方式; the note is Alipay's 商品说明 and the name over
        // WeChat's amount, its brand, where 商品 and 商户全称 can be a franchisee's and a company's.
        val note = when (app) {
            PaymentApp.ALIPAY -> labelled(texts, "商品说明")
            PaymentApp.WECHAT -> texts.getOrNull(if (texts.getOrNull(at - 1) in setOf("¥", "￥")) at - 2 else at - 1)
                ?.takeIf { word -> word.length <= 40 && PAID.none { word.contains(it) } && word !in PAGE_TITLES }
        }
        val method = labelled(texts, if (app == PaymentApp.ALIPAY) "付款方式" else "支付方式")
        val time = labelled(texts, "支付时间")?.let { text ->
            TIMES.firstNotNullOfOrNull { runCatching { LocalDateTime.parse(text, it) }.getOrNull() }?.atZone(zone)?.toInstant()?.toEpochMilli()
        }
        return Payment(app, amount, note, method, time)
    }

    /** What follows [label]: in the next text, or after it in its own. */
    private fun labelled(texts: List<String>, label: String): String? {
        for ((i, text) in texts.withIndex()) {
            if (text == label) return texts.getOrNull(i + 1)?.takeIf { it != label }
            if (text.startsWith(label) && text.length > label.length) {
                val rest = text.removePrefix(label).trimStart(' ', '：', ':', '　')
                if (rest.isNotEmpty()) return rest
            }
        }
        return null
    }

    private fun cents(text: String) = text.toBigDecimal().movePointRight(2).toLong()
}

/**
 * The account a payment was made from, by its page's 支付方式: a card by its last digits and
 * bank, WeChat's 零钱 and Alipay's 余额, 余额宝 and 花呗 by the wallet named for them; null when
 * none is plainly it, and the form starts with the last one paid from.
 */
fun Ledger.accountFor(payment: Payment): String? {
    val open = accounts.filter { !it.archived }
    val method = payment.method?.replace(" ", "").orEmpty()
    Regex("""(\d{4})\)?$""").find(method)?.groupValues?.get(1)?.let { digits ->
        open.filter { it.name.contains(digits) || it.note.contains(digits) }.singleOrNull()?.let { return it.id }
    }
    val bank = method.substringBefore("(").substringBefore("（").replace(Regex("""(储蓄卡|信用卡|借记卡|银行卡)$"""), "")
    val wallet = when {
        method.contains("零钱通") -> listOf("零钱通", "微信")
        method.contains("零钱") -> listOf("零钱", "微信")
        method.contains("花呗") -> listOf("花呗")
        method.contains("余额宝") -> listOf("余额宝", "支付宝")
        method.contains("余额") && payment.app == PaymentApp.ALIPAY -> listOf("支付宝")
        method.contains("亲属卡") -> return null
        else -> null
    }
    if (wallet != null) {
        for (word in wallet) open.filter { it.name.contains(word) }.let { if (it.size == 1) return it.single().id }
        return null
    }
    if (bank.length >= 2) {
        val short = bank.removeSuffix("银行")
        val credit = method.contains("信用卡")
        open.filter { it.name.contains(short) }.let { banks ->
            banks.singleOrNull()?.let { return it.id }
            banks.filter { (it.kind == AccountKind.CREDIT) == credit }.singleOrNull()?.let { return it.id }
        }
    }
    return null
}

/**
 * A guess at the category from [note], null when there is none: the category of the last
 * expense in the book whose note has the same name in it, a shop's or a dish's, else the
 * book's category named for a kind of spending its words point to.
 */
fun Ledger.categoryFor(note: String?): String? {
    if (note.isNullOrBlank()) return null
    val bookId = book?.id
    // 某某茶饮(某某路店)外卖订单 is 某某茶饮's.
    val name = note.replace(Regex("""[(（][^)）]*[)）]"""), " ").replace(Regex("""(外卖)?订单.*$|-.*$"""), "").trim()
    if (name.length >= 2) {
        txns.firstOrNull { it.book == bookId && it.kind == TxnKind.EXPENSE && it.category != null && it.note.contains(name) }
            ?.let { return it.category }
    }
    val expenses = categories.filter { it.book == bookId && it.type == CategoryType.EXPENSE }
    for ((words, names) in CATEGORY_WORDS) {
        if (words.none { note.contains(it, ignoreCase = true) }) continue
        for (wanted in names) expenses.firstOrNull { it.name == wanted }?.let { return it.id }
    }
    return null
}

/** Words of a note and the category names they point to, the first of each a book has; in order, drinks ahead of meals. */
private val CATEGORY_WORDS = listOf(
    listOf("充电宝", "街电", "怪兽", "小电", "来电") to listOf("充电宝"),
    listOf("奶茶", "咖啡", "酸奶", "茶饮", "饮品", "果汁", "瑞幸", "星巴克", "喜茶", "蜜雪", "霸王茶姬", "古茗", "茶百道") to listOf("饮料", "饮品", "餐饮", "三餐"),
    listOf("外卖", "餐", "饭", "面馆", "粉", "麦当劳", "肯德基", "汉堡", "饿了么", "小吃", "食堂") to listOf("三餐", "餐饮", "饮食", "美食"),
    listOf("打车", "滴滴", "出行", "地铁", "公交", "高铁", "火车", "铁路", "12306", "机票", "航空", "停车", "加油", "高速", "充电") to listOf("交通", "出行"),
    listOf("房租", "租金") to listOf("住房"),
    listOf("话费", "流量", "水费", "电费", "燃气", "物业", "宽带") to listOf("日常", "生活缴费", "通讯"),
    listOf("医院", "药", "诊所", "挂号") to listOf("医疗"),
    listOf("洗衣") to listOf("洗衣"),
    listOf("电影", "游戏", "会员", "视频", "音乐", "KTV") to listOf("娱乐"),
    listOf("服饰", "衣", "鞋", "优衣库") to listOf("服饰"),
    listOf("超市", "便利店", "淘宝", "天猫", "京东", "拼多多", "商城") to listOf("购物", "日常"),
)

/** Whether the ledger already has this expense: of the amount, within ten minutes of [time]. */
fun Ledger.hasPayment(payment: Payment, time: Long): Boolean = txns.any {
    it.kind == TxnKind.EXPENSE && it.amount == payment.amount && it.currency == "CNY" && abs(it.time - time) <= 10 * 60_000
}
