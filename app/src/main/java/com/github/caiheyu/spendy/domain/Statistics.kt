package com.github.caiheyu.spendy.domain

import java.time.*
import java.time.temporal.ChronoUnit

data class DateRange(val start: LocalDate, val end: LocalDate) {
    val startText get() = start.toString()
    val endText get() = end.toString()
}
data class TrendBucket(val start: String, val end: String, val label: String,
    val expense: Long?, val recovery: Long?, val income: Long?) {
    val netExpense get() = expense?.minus(recovery ?: 0)
    val balance get() = income?.minus(netExpense ?: 0)
}

object Statistics {
    fun range(mode: String, anchor: LocalDate): DateRange = when (mode) {
        "week" -> anchor.minusDays((anchor.dayOfWeek.value - 1).toLong()).let { DateRange(it, it.plusDays(6)) }
        "year" -> DateRange(LocalDate.of(anchor.year, 1, 1), LocalDate.of(anchor.year, 12, 31))
        else -> YearMonth.from(anchor).let { DateRange(it.atDay(1), it.atEndOfMonth()) }
    }
    fun shift(mode: String, anchor: LocalDate, amount: Long): LocalDate = when (mode) {
        "week" -> anchor.plusWeeks(amount); "year" -> anchor.withDayOfYear(1).plusYears(amount)
        else -> anchor.withDayOfMonth(1).plusMonths(amount)
    }
    fun buckets(s: LedgerState, range: DateRange, mode: String, today: LocalDate = LocalDate.now()): List<TrendBucket> {
        val points = Ledger.project(s).filter { it.date in range.startText..range.endText }
        val days = ChronoUnit.DAYS.between(range.start, range.end) + 1
        val unit = if (mode == "year" || days > 186) "month" else if (mode == "custom" && days > 62) "week" else "day"
        val result = mutableListOf<TrendBucket>()
        var start = range.start
        while (start <= range.end) {
            val end = minOf(range.end, when (unit) { "month" -> YearMonth.from(start).atEndOfMonth(); "week" -> start.plusDays(6); else -> start })
            val part = points.filter { it.date in start.toString()..end.toString() }
            result += TrendBucket(start.toString(), end.toString(), if (unit == "month") "${start.monthValue}月" else "${start.monthValue}/${start.dayOfMonth}",
                if (start > today) null else part.filter { it.type == "expense" && it.amount > 0 }.sumOf { it.amount },
                if (start > today) null else -part.filter { it.type == "expense" && it.amount < 0 }.sumOf { it.amount },
                if (start > today) null else part.filter { it.type == "income" }.sumOf { it.amount })
            start = end.plusDays(1)
        }
        return result
    }
    fun comparison(s: LedgerState, r: DateRange, mode: String, type: String, today: LocalDate = LocalDate.now()): String {
        if (r.start > today) return "尚未开始"
        val cutoff = minOf(r.end, today)
        val current = Ledger.project(s).filter { it.type == type && it.date in r.startText..cutoff.toString() }.sumOf { it.amount }
        val previous = when (mode) {
            "month" -> DateRange(r.start.minusMonths(1), minOf(r.start.minusDays(1), cutoff.minusMonths(1)))
            "year" -> DateRange(r.start.minusYears(1), cutoff.minusYears(1))
            "week" -> DateRange(r.start.minusWeeks(1), cutoff.minusWeeks(1))
            else -> { val duration = ChronoUnit.DAYS.between(r.start, r.end) + 1; DateRange(r.start.minusDays(duration), cutoff.minusDays(duration)) }
        }
        val prev = Ledger.project(s).filter { it.type == type && it.date in previous.startText..previous.endText }.sumOf { it.amount }
        if (prev <= 0) return "上期基数不足，暂不比较"
        val delta = current - prev
        return (if (mode == "year") "同比" else "较上期") + (if (delta > 0) " ↑ " else " ↓ ") + Money.percent(kotlin.math.abs(delta), prev) + if (cutoff < r.end) " · 同进度" else ""
    }
}
