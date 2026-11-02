import Foundation

// What the bills do, as Android's ledger/Accounting.kt works it out: to each account they touch,
// in that account's currency, and to the books' sums, in the bill's own currency. A refund takes
// from its expense's category up to what was spent, what it brings back beyond that earned; an
// expense waiting to be reimbursed isn't one's own spending until it is known how much comes back.

/// The category a transfer's fee is summed under, as an expense; its discount, as an income.
nonisolated let feeCategory = "@fee"
nonisolated let discountCategory = "@discount"

nonisolated enum Accounting {
    /// What a bill does to each account it touches, in that account's currency: paid from, paid
    /// into, or for a transfer, out of one and into the other, its fee out of and its discount into theirs.
    static func effects(_ txn: Txn, accounts: [String: Account], rates: Rates) -> [(String, Int)] {
        func into(_ account: String, _ cents: Int, recorded: Int? = nil) -> (String, Int) {
            let currency = accounts[account]?.currency ?? txn.currency
            if currency == txn.currency { return (account, cents) }
            if let recorded { return (account, recorded) }
            return (account, rates.convert(cents, from: txn.currency, to: currency, day: txn.day) ?? cents)
        }
        let single = (txn.splits ?? []).isEmpty
        switch txn.kind {
        case .expense:
            return txn.payers.map { payer in
                let (account, value) = into(payer.account, payer.amount, recorded: single ? txn.accountAmount : nil)
                return (account, -value)
            }
        case .income, .refund, .reimbursement:
            return txn.payers.map { into($0.account, $0.amount, recorded: single ? txn.accountAmount : nil) }
        case .transfer:
            var out: [(String, Int)] = []
            if let from = txn.account {
                let (account, value) = into(from, txn.amount, recorded: txn.accountAmount)
                out.append((account, -value))
            }
            if let to = txn.toAccount { out.append(into(to, txn.amount, recorded: txn.toAmount)) }
            if let fee = txn.fee, fee > 0, let account = txn.feeAccount ?? txn.account {
                let (id, value) = into(account, fee)
                out.append((id, -value))
            }
            if let discount = txn.discount, discount > 0, let account = txn.discountAccount ?? txn.account {
                out.append(into(account, discount))
            }
            return out
        }
    }

    static func balances(_ ledger: Ledger) -> [String: Int] {
        var out = Dictionary(ledger.accounts.map { ($0.id, $0.opening) }, uniquingKeysWith: { a, _ in a })
        let accounts = Dictionary(ledger.accounts.map { ($0.id, $0) }, uniquingKeysWith: { a, _ in a })
        for txn in ledger.txns {
            for (account, cents) in effects(txn, accounts: accounts, rates: ledger.rates) {
                out[account, default: 0] += cents
            }
        }
        return out
    }

    /// What each bill adds to the books' sums, oldest first.
    static func entries(_ ledger: Ledger) -> [LedgerEntry] {
        var out: [LedgerEntry] = []
        // How much of each expense is still there to be refunded, as its refunds come in order.
        var left: [String: Int] = [:]
        var reimbursed: [String: Int] = [:]
        for txn in ledger.txns where txn.kind == .reimbursement {
            if let related = txn.related { reimbursed[related, default: 0] += txn.amount }
        }
        for txn in ledger.txns.reversed() where !txn.excluded {
            switch txn.kind {
            case .expense:
                switch txn.reimburse ?? .none {
                case .none: out.append(LedgerEntry(txn: txn, expense: true, category: txn.category, amount: txn.amount))
                case .pending: break
                case .done:
                    let net = txn.amount - (reimbursed[txn.id] ?? 0)
                    if net > 0 { out.append(LedgerEntry(txn: txn, expense: true, category: txn.category, amount: net)) }
                    if net < 0 { out.append(LedgerEntry(txn: txn, expense: false, category: txn.category, amount: -net)) }
                }
            case .income:
                out.append(LedgerEntry(txn: txn, expense: false, category: txn.category, amount: txn.amount))
            case .refund:
                if let expense = ledger.txn(txn.related) {
                    let room = left[expense.id] ?? expense.amount
                    let back = min(room, txn.amount)
                    left[expense.id] = room - back
                    if back > 0 { out.append(LedgerEntry(txn: txn, expense: true, category: expense.category, amount: -back)) }
                    if txn.amount > back { out.append(LedgerEntry(txn: txn, expense: false, category: expense.category, amount: txn.amount - back)) }
                } else {
                    out.append(LedgerEntry(txn: txn, expense: true, category: txn.category, amount: -txn.amount))
                }
            case .reimbursement:
                // Summed with its expense; one for no expense is earned.
                if ledger.txn(txn.related) == nil {
                    out.append(LedgerEntry(txn: txn, expense: false, category: txn.category, amount: txn.amount))
                }
            case .transfer:
                if let fee = txn.fee, fee > 0 { out.append(LedgerEntry(txn: txn, expense: true, category: feeCategory, amount: fee)) }
                if let discount = txn.discount, discount > 0 { out.append(LedgerEntry(txn: txn, expense: false, category: discountCategory, amount: discount)) }
            }
        }
        return out
    }
}
