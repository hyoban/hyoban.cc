package cc.hyoban.moments

import cc.hyoban.moments.ledger.Account
import cc.hyoban.moments.ledger.AccountKind
import cc.hyoban.moments.ledger.Book
import cc.hyoban.moments.ledger.Category
import cc.hyoban.moments.ledger.CategoryType
import cc.hyoban.moments.ledger.Ledger
import cc.hyoban.moments.ledger.Payment
import cc.hyoban.moments.ledger.PaymentApp
import cc.hyoban.moments.ledger.PaymentPage
import cc.hyoban.moments.ledger.Txn
import cc.hyoban.moments.ledger.TxnKind
import cc.hyoban.moments.ledger.accountFor
import cc.hyoban.moments.ledger.categoryFor
import cc.hyoban.moments.ledger.hasPayment
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PaymentsTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val wechat = PaymentApp.WECHAT
    private val alipay = PaymentApp.ALIPAY

    @Test
    fun readsWeChatPaySuccess() {
        val page = listOf("支付成功", "瑞幸咖啡", "¥", "9.90", "支付方式", "零钱", "完成")
        assertEquals(Payment(PaymentApp.WECHAT, 990, "瑞幸咖啡", "零钱"), PaymentPage.read(wechat, page, zone))
    }

    @Test
    fun readsWeChatBillDetail() {
        // WeChat 8.0.79's 账单详情 as its accessibility nodes give it, made-up values in its rows.
        val page = listOf(
            "某某零食", "-12.50", "当前状态", "支付成功", "支付时间", "2026年1月15日 12:30:01", "商品", "某某食品销售店",
            "商户全称", "某某商贸有限公司", "收单机构", "某某银行股份有限公司", "支付方式", "零钱", "交易单号",
            "4200000000000000000000000000", "商户单号", "可在支持的商户扫码退款", "一维码", "账单服务", "对订单有疑惑",
            "发起群收款", "在此商户的交易", "申请电子凭证", "全部账单",
        )
        val read = PaymentPage.read(wechat, page, zone)!!
        assertEquals(1250, read.amount)
        assertEquals("某某零食", read.note)
        assertEquals("零钱", read.method)
        assertEquals(LocalDateTime.of(2026, 1, 15, 12, 30, 1).atZone(zone).toInstant().toEpochMilli(), read.time)
    }

    @Test
    fun readsAlipaySuccess() {
        val page = listOf("支付成功", "￥1,280.00", "收款方", "某某商店", "付款方式", "花呗", "完成")
        assertEquals(Payment(PaymentApp.ALIPAY, 128000, null, "花呗"), PaymentPage.read(alipay, page, zone))
    }

    @Test
    fun readsAlipayBillDetail() {
        val page = listOf("账单详情", "美团", "-36.80", "交易成功", "支付时间", "2026-01-15 19:02:11", "付款方式", "余额宝", "商品说明", "美团订单-外卖")
        val read = PaymentPage.read(alipay, page, zone)!!
        assertEquals(3680, read.amount)
        assertEquals("美团订单-外卖", read.note)
        assertEquals("余额宝", read.method)
    }

    @Test
    fun readsAlipayBillDetailAsReadOut() {
        // Alipay 12.12.36's 账单详情 as its accessibility nodes give it, made-up values in its rows:
        // the amount spoken, not drawn.
        val page = listOf(
            "某某外卖", "支出12.5元", "交易成功", "支付时间", "2026-01-15 12:30:01", "2026-01-15 12:30:01",
            "付款方式", "某某银行储蓄卡(1234) ", "商品说明", "某某茶饮(某某路店)外卖订单", "某某茶饮(某某路店)外卖订单",
            "支付奖励", "更多", "账单管理", "账单分类", "标签", "AA收款", "往来流水证明", "返回", "账单详情", "全部账单",
        )
        val read = PaymentPage.read(alipay, page, zone)!!
        assertEquals(1250, read.amount)
        assertEquals("某某茶饮(某某路店)外卖订单", read.note)
        assertEquals("某某银行储蓄卡(1234)", read.method)
        assertEquals(LocalDateTime.of(2026, 1, 15, 12, 30, 1).atZone(zone).toInstant().toEpochMilli(), read.time)
        // Scrolled down, the payee over the amount gone, it reads the same.
        assertEquals(read, PaymentPage.read(alipay, page.drop(1), zone))
        assertNull(PaymentPage.read(alipay, listOf("某人", "收入50元", "交易成功"), zone))
    }

    @Test
    fun leavesOtherPagesAlone() {
        // Money coming in, money back, a payment not yet made, a chat with a price in it.
        assertNull(PaymentPage.read(wechat, listOf("已收款", "+50.00", "支付成功"), zone))
        assertNull(PaymentPage.read(alipay, listOf("退款成功", "¥12.00", "交易成功"), zone))
        assertNull(PaymentPage.read(wechat, listOf("付款给 瑞幸咖啡", "¥", "9.90", "确认支付"), zone))
        assertNull(PaymentPage.read(wechat, listOf("张三", "这个 ¥30 吗"), zone))
    }

    @Test
    fun findsTheAccountPaidFrom() {
        val ledger = Ledger(accounts = listOf(
            Account("w", "微信零钱", AccountKind.WALLET),
            Account("a", "支付宝", AccountKind.WALLET),
            Account("h", "花呗", AccountKind.CREDIT),
            Account("c", "招行信用卡 1234", AccountKind.CREDIT),
            Account("d", "招商银行储蓄卡", AccountKind.DEBIT),
        ))
        fun of(app: PaymentApp, method: String) = ledger.accountFor(Payment(app, 100, method = method))
        assertEquals("w", of(PaymentApp.WECHAT, "零钱"))
        assertEquals("h", of(PaymentApp.ALIPAY, "花呗"))
        assertEquals("a", of(PaymentApp.ALIPAY, "账户余额"))
        assertEquals("c", of(PaymentApp.WECHAT, "招商银行信用卡(1234)"))
        assertEquals("d", of(PaymentApp.WECHAT, "招商银行储蓄卡(9876)"))
        assertNull(of(PaymentApp.WECHAT, "工商银行储蓄卡(5555)"))
    }

    @Test
    fun guessesTheCategoryFromTheNote() {
        val categories = listOf(
            Category("food", "b", CategoryType.EXPENSE, "三餐"),
            Category("drink", "b", CategoryType.EXPENSE, "饮料"),
            Category("ride", "b", CategoryType.EXPENSE, "交通"),
        )
        val ledger = Ledger(books = listOf(Book("b", "默认账本")), categories = categories, txns = listOf(
            Txn("t", "b", TxnKind.EXPENSE, 0, 480, 1500, "CNY", category = "food", note = "某某茶饮 加料"),
        ))
        // The same shop's last bill, its branch and 外卖订单 left out.
        assertEquals("food", ledger.categoryFor("某某茶饮(某某路店)外卖订单"))
        assertEquals("drink", ledger.categoryFor("瑞幸咖啡(万达店)"))
        assertEquals("ride", ledger.categoryFor("滴滴快车-张师傅"))
        assertEquals("food", ledger.categoryFor("老王面馆外卖订单"))
        assertNull(ledger.categoryFor("某某科技有限公司"))
        assertNull(ledger.categoryFor(null))
    }

    @Test
    fun knowsAPaymentAlreadyKept() {
        val at = 1_800_000_000_000
        val ledger = Ledger(txns = listOf(Txn("t", "b", TxnKind.EXPENSE, at, 480, 990, "CNY")))
        assertTrue(ledger.hasPayment(Payment(PaymentApp.WECHAT, 990), at + 5 * 60_000))
        assertFalse(ledger.hasPayment(Payment(PaymentApp.WECHAT, 990), at + 30 * 60_000))
        assertFalse(ledger.hasPayment(Payment(PaymentApp.WECHAT, 1000), at))
    }
}
