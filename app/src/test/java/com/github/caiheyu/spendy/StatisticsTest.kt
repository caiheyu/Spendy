package com.github.caiheyu.spendy

import com.github.caiheyu.spendy.domain.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class StatisticsTest {
    private fun state(): LedgerState {
        val s = Ledger.seed().let { it.copy(accounts = it.accounts.map { a -> a.copy(openedOn = "2020-01-01") }) }
        return s.copy(transactions = listOf(Entry(amountMinor = 1234, date = "2024-02-01", accountId = "a-cash", categoryId = "c-food"),
            Entry(amountMinor = 5678, date = "2024-02-29", accountId = "a-cash", categoryId = "c-food"),
            Entry(type = "income", amountMinor = 10000, date = "2024-02-10", accountId = "a-cash", categoryId = "c-salary")))
    }
    @Test fun weekStartsMondayAcrossYearBoundary() { val r = Statistics.range("week", LocalDate.parse("2023-01-01")); assertEquals("2022-12-26", r.startText); assertEquals("2023-01-01", r.endText) }
    @Test fun leapMonthHas29DailyBuckets() { val b = Statistics.buckets(state(), Statistics.range("month", LocalDate.parse("2024-02-01")), "month"); assertEquals(29, b.size); assertEquals(5678L, b.last().expense) }
    @Test fun bucketSumsEqualPeriodTotals() {
        val s = state(); val r = DateRange(LocalDate.parse("2024-01-31"), LocalDate.parse("2024-03-03"))
        val bins = Statistics.buckets(s, r, "custom"); val totals = Ledger.totals(s, r.startText, r.endText)
        assertEquals(totals.expense, bins.sumOf { it.netExpense ?: 0 }); assertEquals(totals.income, bins.sumOf { it.income ?: 0 })
    }
    @Test fun customBucketsUseDailyWeeklyMonthlyThresholds() {
        val start = LocalDate.parse("2024-01-01")
        fun bins(n: Long) = Statistics.buckets(state(), DateRange(start, start.plusDays(n - 1)), "custom")
        assertEquals(62, bins(62).size); assertEquals(9, bins(63).size); assertEquals(27, bins(186).size); assertEquals(7, bins(187).size)
    }
    @Test fun bucketsAreClippedToCustomRange() {
        val r = DateRange(LocalDate.parse("2024-01-17"), LocalDate.parse("2024-10-13")); val bins = Statistics.buckets(state(), r, "custom")
        assertEquals(r.startText, bins.first().start); assertEquals(r.endText, bins.last().end)
    }
    @Test fun futurePointsAreNullNotZero() {
        val bins = Statistics.buckets(state(), Statistics.range("month", LocalDate.parse("2024-02-01")), "month", LocalDate.parse("2024-02-10"))
        assertNotNull(bins[9].expense); assertNull(bins[10].expense)
    }
    @Test fun comparisonDoesNotInventZeroBasePercentage() { assertEquals("上期基数不足，暂不比较", Statistics.comparison(state(), Statistics.range("month", LocalDate.parse("2024-02-01")), "month", "expense")) }
    @Test fun futureComparisonHasNoPercentage() { assertEquals("尚未开始", Statistics.comparison(state(), Statistics.range("month", LocalDate.parse("2027-01-01")), "month", "expense", LocalDate.parse("2026-10-30"))) }
    @Test fun annualBucketsRetain12Months() { assertEquals(12, Statistics.buckets(state(), Statistics.range("year", LocalDate.parse("2024-01-01")), "year").size) }
}
