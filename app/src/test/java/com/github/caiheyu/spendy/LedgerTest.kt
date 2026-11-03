package com.github.caiheyu.spendy

import com.github.caiheyu.spendy.domain.*
import com.github.caiheyu.spendy.ui.keypad
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class LedgerTest {
    private fun base() = Ledger.seed().let { it.copy(accounts = listOf(
        it.accounts.first().copy(openedOn = "2020-01-01", openingBalanceMinor = 100_000),
        Account(id = "b", name = "银行卡", openedOn = "2020-01-01"))) }
    private fun expense() = Entry(id = "expense", amountMinor = 10_000, date = "2024-01-20", accountId = "a-cash", categoryId = "c-food", tagIds = listOf("t-work"), reimbursable = true)
    private fun refund(kind: String = "refund", amount: Long = -3000) = Entry(id = newId(), kind = kind, amountMinor = amount,
        date = "2024-02-10", accountId = "b", categoryId = "c-food", tagIds = listOf("t-work"), originalId = "expense")
    private fun fails(block: () -> Unit) { assertThrows(IllegalArgumentException::class.java) { block() } }

    @Test fun moneyUsesExactMinorUnits() { assertEquals(29L, Money.parse("0.29")); assertEquals(30L, Money.evaluate("0.1+0.2")); assertEquals(1750L, Money.evaluate("20-2.50")); assertEquals("-1.25", Money.decimal(-125)) }
    @Test fun moneyRejectsExcessPrecisionAndExponent() { listOf("1.001", "1e3", "NaN", "", "9999999999999").forEach { raw -> fails { Money.parse(raw) } } }
    @Test fun calculatorHandlesDecimalAndOperations() { assertEquals("0.", keypad("", ".")); assertEquals("1-", keypad("1+", "−")); assertEquals("0.12", keypad("0.12", "3")); assertEquals(100L, Money.evaluate("1.")); assertEquals(50L, Money.evaluate("1-0.50")) }
    @Test fun draftRetryHasStableId() { val d = EntryDraft(expression = "10", accountId = "a-cash"); assertEquals(d.toEntry().id, d.toEntry().id) }
    @Test fun negativeAdjustmentCanBeEdited() { val e = Entry(type = "adjustment", amountMinor = -1234, accountId = "a-cash"); assertEquals(-1234L, EntryDraft.from(e).toEntry().amountMinor) }
    @Test fun transferOnlyReducesNetByFee() {
        val s = Ledger.save(base(), Entry(type = "transfer", amountMinor = 20_000, feeMinor = 100, feeCategoryId = "c-fee", date = "2024-01-01", accountId = "a-cash", toAccountId = "b"))
        assertEquals(99_900L, Ledger.net(s)); assertEquals(79_900L, Ledger.balance(s, "a-cash")); assertEquals(20_000L, Ledger.balance(s, "b"))
        assertEquals(Totals(100, 0), Ledger.totals(s, "2024-01-01", "2024-12-31"))
    }
    @Test fun transferFeeCannotBeExcludedAsOrdinarySpending() {
        val s = Ledger.save(base(), Entry(type = "transfer", amountMinor = 1000, feeMinor = 10, feeCategoryId = "c-fee", countInStats = false, date = "2024-01-01", accountId = "a-cash", toAccountId = "b"))
        assertEquals(10L, Ledger.project(s).single().amount)
    }
    @Test fun sameAccountTransferRejected() { fails { Ledger.save(base(), Entry(type = "transfer", amountMinor = 1000, date = "2024-01-01", accountId = "a-cash", toAccountId = "a-cash")) } }
    @Test fun excludedExpenseStillChangesBalance() {
        val s = Ledger.save(base(), expense().copy(countInStats = false)); assertEquals(90_000L, Ledger.net(s)); assertEquals(Totals(), Ledger.totals(s, "2024-01-01", "2024-12-31"))
    }
    @Test fun crossMonthRefundReducesArrivalPeriodExpense() {
        val s = Ledger.save(Ledger.save(base(), expense()), refund())
        assertEquals(10_000L, Ledger.totals(s, "2024-01-01", "2024-01-31").expense)
        assertEquals(-3000L, Ledger.totals(s, "2024-02-01", "2024-02-29").expense)
        assertEquals(0L, Ledger.totals(s, "2024-02-01", "2024-02-29").income)
        assertEquals(93_000L, Ledger.net(s)); assertEquals(3000L, Ledger.balance(s, "b"))
    }
    @Test fun refundAndReimbursementShareOneLimit() {
        var s = Ledger.save(base(), expense()); s = Ledger.save(s, refund()); s = Ledger.save(s, refund("reimbursement", -7000))
        assertEquals(0L, Ledger.pending(s, expense())); val final = s
        fails { Ledger.save(final, refund(amount = -1)) }
    }
    @Test fun reimbursementRequiresEligibility() { val s = Ledger.save(base(), expense().copy(reimbursable = false)); fails { Ledger.save(s, refund("reimbursement")) } }
    @Test fun eligibilityCannotBeRemovedAfterRecovery() { var s = Ledger.save(base(), expense()); s = Ledger.save(s, refund("reimbursement")); val result = s; fails { Ledger.save(result, expense().copy(reimbursable = false)) } }
    @Test fun originalChangesPropagateToRecovery() {
        var s = Ledger.save(base(), expense()); s = Ledger.save(s, refund())
        s = Ledger.save(s, expense().copy(categoryId = "c-shop", tagIds = listOf("t-online"), countInStats = false))
        val linked = s.transactions.first { it.originalId != null }; assertEquals("c-shop", linked.categoryId); assertEquals(listOf("t-online"), linked.tagIds); assertFalse(linked.countInStats)
    }
    @Test fun recoveryCannotPrecedeOriginal() { fails { Ledger.save(Ledger.save(base(), expense()), refund().copy(date = "2024-01-01")) } }
    @Test fun shrinkingOriginalBelowRecoveryRejected() { val s = Ledger.save(Ledger.save(base(), expense()), refund()); fails { Ledger.save(s, expense().copy(amountMinor = 1000)) } }
    @Test fun deletingOriginalCascadesToBothKinds() {
        var s = Ledger.save(base(), expense()); s = Ledger.save(s, refund()); s = Ledger.save(s, refund("reimbursement"))
        val deleted = Ledger.delete(s, setOf("expense")); assertTrue(deleted.transactions.isEmpty()); assertEquals(100_000L, Ledger.net(deleted))
    }
    @Test fun dateAndOpeningBoundariesEnforced() {
        assertEquals(0L, Ledger.balance(base(), "a-cash", "2019-12-31"))
        fails { Ledger.save(base(), expense().copy(date = "2019-12-31")) }; fails { Ledger.save(base(), expense().copy(date = LocalDate.now().plusDays(1).toString())) }
    }
    @Test fun zeroOpeningAccountsAllowHistoricalIncomeAndExpenses() {
        val date = LocalDate.now().minusDays(2).toString()
        var s = Ledger.save(Ledger.seed(), expense().copy(date = date))
        assertEquals(-10_000L, Ledger.balance(s, "a-cash", date))
        s = Ledger.save(s, Entry(type = "income", amountMinor = 25_000, date = date, accountId = "a-cash", categoryId = "c-salary"))
        assertEquals(15_000L, Ledger.balance(s, "a-cash", date))
        assertEquals(15_000L, Ledger.net(s))
        assertEquals(0L, Ledger.balance(s, "a-cash", LocalDate.parse(date).minusDays(1).toString()))
        assertEquals(Totals(10_000, 25_000), Ledger.totals(s, date, date))
    }
    @Test fun existingEntryCanBeMovedBeforeZeroOpeningDate() {
        val today = LocalDate.now().toString()
        val earlier = LocalDate.now().minusDays(2).toString()
        val entry = expense().copy(date = today)
        val s = Ledger.save(Ledger.save(Ledger.seed(), entry), EntryDraft.from(entry).copy(date = earlier).toEntry())
        assertEquals(1, s.transactions.size)
        assertEquals(earlier, s.entry(entry.id)?.date)
        assertEquals(-10_000L, Ledger.balance(s, "a-cash", earlier))
        assertEquals(Totals(), Ledger.totals(s, today, today))
    }
    @Test fun historicalTransferIncludesBothZeroOpeningAccountsAndFee() {
        val date = LocalDate.now().minusDays(2).toString()
        val seed = Ledger.seed().let { it.copy(accounts = it.accounts + Account(id = "b", name = "卡")) }
        val s = Ledger.save(seed, Entry(type = "transfer", amountMinor = 10_000, date = date,
            accountId = "a-cash", toAccountId = "b", feeMinor = 100, feeCategoryId = "c-fee"))
        assertEquals(-10_100L, Ledger.balance(s, "a-cash", date))
        assertEquals(10_000L, Ledger.balance(s, "b", date))
        assertEquals(-100L, Ledger.net(s, date))
    }
    @Test fun historicalTransferCannotPrecedeNonzeroTargetOpeningBalance() {
        val seed = Ledger.seed().let { it.copy(accounts = it.accounts + Account(id = "b", name = "卡", openingBalanceMinor = 1000)) }
        val error = assertThrows(IllegalArgumentException::class.java) {
            Ledger.save(seed, Entry(type = "transfer", amountMinor = 100, date = LocalDate.now().minusDays(2).toString(), accountId = "a-cash", toAccountId = "b"))
        }
        assertTrue(error.message!!.contains("卡"))
        assertTrue(error.message!!.contains("期初生效日"))
    }
    @Test fun historicalDatesStillRespectSupportedRange() {
        val entry = expense().copy(date = "1900-01-01")
        assertEquals(-10_000L, Ledger.balance(Ledger.save(Ledger.seed(), entry), "a-cash", entry.date))
        fails { Ledger.save(Ledger.seed(), entry.copy(date = "1899-12-31")) }
    }
    @Test fun negativeBalancesAreLegitimate() { val s = Ledger.save(base(), expense().copy(amountMinor = 200_000)); assertEquals(-100_000L, Ledger.net(s)) }
    @Test fun archivedReferenceCanBePreservedButNotNewlyAssigned() {
        var s = Ledger.save(base(), expense()); s = s.copy(categories = s.categories.map { if (it.id == "c-food") it.copy(archived = true) else it })
        assertEquals("更正", Ledger.save(s, expense().copy(note = "更正")).entry("expense")?.note)
        val archived = s; fails { Ledger.save(archived, expense().copy(id = "new")) }
    }
    @Test fun integrityRejectsMissingEntitiesAndDuplicateIds() {
        fails { Ledger.validate(base().copy(accounts = base().accounts + base().accounts.first())) }
        fails { Ledger.save(base(), expense().copy(categoryId = "missing")) }
        fails { Ledger.save(base(), expense().copy(subcategoryId = "s-shop-0")) }
    }
    @Test fun sourceIdsAreUniqueBySource() {
        val e = expense().copy(externalSource = "csv", externalId = "source1")
        val s = Ledger.save(base(), e); fails { Ledger.save(s, e.copy(id = "other")) }
    }
    @Test fun searchSupportsAllAndAnyTagsAndAmountAbsoluteValue() {
        var s = Ledger.save(base(), expense().copy(tagIds = listOf("t-work", "t-travel"))); s = Ledger.save(s, refund())
        assertEquals(2, Ledger.filter(s, EntryFilter(tagIds = setOf("t-work", "t-travel"))).size)
        assertEquals(0, Ledger.filter(s, EntryFilter(tagIds = setOf("t-work", "t-online"))).size)
        assertEquals(2, Ledger.filter(s, EntryFilter(tagIds = setOf("t-work", "t-online"), matchAllTags = false)).size)
        assertEquals(1, Ledger.filter(s, EntryFilter(min = 2500, max = 3500)).size)
    }
    @Test fun adjustmentExcludedFilterOnlyShowsOrdinaryExcludedItems() {
        var s = Ledger.save(base(), expense().copy(countInStats = false))
        s = Ledger.save(s, Entry(type = "adjustment", amountMinor = -100, accountId = "a-cash", countInStats = false))
        assertEquals(1, Ledger.filter(s, EntryFilter(excludedOnly = true)).size)
    }
}
