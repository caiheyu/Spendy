@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.github.caiheyu.spendy.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.caiheyu.spendy.domain.*
import com.github.caiheyu.spendy.ui.theme.*
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun TransactionsScreen(vm: SpendyViewModel, s: LedgerState, onEntry: (Entry) -> Unit, onAdd: () -> Unit,
    onSearch: () -> Unit, onPending: () -> Unit) {
    var start by rememberSaveable { mutableStateOf(YearMonth.now().atDay(1).toString()) }
    var end by rememberSaveable { mutableStateOf(YearMonth.now().atEndOfMonth().toString()) }
    var type by rememberSaveable { mutableStateOf("all") }
    var rangeSheet by remember { mutableStateOf(false) }
    var shown by remember(start, end, type, s.meta.revision) { mutableIntStateOf(100) }
    var deleting by remember { mutableStateOf<Entry?>(null) }
    val entries = remember(s, start, end, type) { Ledger.filter(s, EntryFilter(start = start, end = end, type = type.takeIf { it != "all" })) }
    val totals by produceState(Ledger.totals(s, start, end), s.meta.revision, start, end) { value = vm.repository.totals(start, end) }
    val canUndo by vm.canUndo.collectAsStateWithLifecycle()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 18.dp, end = 18.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { PageHeader("明细", action = { FilledTonalIconButton(onClick = onSearch, colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = MaterialTheme.colorScheme.surface)) { SIcon("search", "搜索与批量修改") } }) }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                AssistChip(onClick = { rangeSheet = true }, label = { Text(rangeLabel(start, end), fontSize = 12.sp) }, trailingIcon = { SIcon("chevD", modifier = Modifier.size(14.dp)) },
                    shape = RoundedCornerShape(50), border = null, colors = AssistChipDefaults.assistChipColors(
                        containerColor = MaterialTheme.colorScheme.surface, labelColor = MaterialTheme.colorScheme.onSurfaceVariant, trailingIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant))
                Spacer(Modifier.weight(1f))
                ChoiceChips(listOf("all" to "全部", "expense" to "支出", "income" to "收入"), type) { type = it }
            }
        }
        item { OverviewCard(totals, type == "income", if (start.startsWith(YearMonth.now().toString()) && start.endsWith("-01")) "本月" else "本期") }
        if (s.transactions.any { Ledger.pending(s, it) > 0 }) item {
            SpendCard(Modifier.fillMaxWidth().clickable(onClick = onPending)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconCircle("receipt", Income, 38.dp); Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) { Text("待报销", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Hint("${s.transactions.count { Ledger.pending(s, it) > 0 }} 笔 · 可报 ¥${Money.format(s.transactions.sumOf { Ledger.pending(s, it) })}") }
                    SIcon("chevR", modifier = Modifier.size(18.dp))
                }
            }
        }
        if (canUndo) item { TextButton(onClick = { vm.action("已撤销") { vm.repository.undo() } }) { SIcon("refresh", modifier = Modifier.size(16.dp)); Text("  撤销最近一次删除 / 批量操作") } }
        if (entries.isEmpty()) item { EmptyState("还没有记账记录", "从一杯咖啡开始，记录认真生活的每一天", action = "记下第一笔", onAction = onAdd) }
        entryGroups(s, entries.take(shown), onEntry, onDelete = { deleting = it })
        if (entries.size > shown) item {
            TextButton(onClick = { shown += 100 }, modifier = Modifier.fillMaxWidth()) { Text("加载更多 · 已显示 $shown / ${entries.size} 条") }
        }
    }
    if (rangeSheet) RangeSheet(start, end, { rangeSheet = false }) { a, b -> start = a; end = b; rangeSheet = false }
    deleting?.let { e -> DeleteEntryDialog(s, e, { deleting = null }) { vm.action("已删除，可在明细撤销", done = { deleting = null }) { vm.repository.delete(setOf(e.id)) } } }
}

@Composable
fun OverviewCard(totals: Totals, income: Boolean = false, period: String = "本期") {
    Box(Modifier.fillMaxWidth().clip(CardShape).background(Brush.linearGradient(listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.secondary)))) {
        Canvas(Modifier.matchParentSize()) {
            drawCircle(Color.White.copy(alpha = .06f), size.width * .39f, androidx.compose.ui.geometry.Offset(size.width * .94f, size.height * .1f))
            drawCircle(Color.White.copy(alpha = .04f), size.width * .3f, androidx.compose.ui.geometry.Offset(size.width * .92f, size.height * .1f))
        }
        Column(Modifier.padding(24.dp)) {
            Text(period + if (income) "收入" else "支出", color = Color.White.copy(alpha = .78f), fontSize = 12.sp)
            Text("¥ ${Money.format(if (income) totals.income else totals.expense)}", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp, bottom = 24.dp))
            Row(Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) { Text(period + if (income) "支出" else "收入", color = Color.White.copy(alpha = .7f), fontSize = 11.sp); Text("¥ ${Money.format(if (income) totals.expense else totals.income)}", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 5.dp)) }
                Column(Modifier.weight(1f)) { Text("${period}结余", color = Color.White.copy(alpha = .7f), fontSize = 11.sp); Text("¥ ${Money.format(totals.balance)}", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 5.dp)) }
            }
        }
    }
}

fun LazyListScope.entryGroups(s: LedgerState, entries: List<Entry>, onEntry: (Entry) -> Unit, selected: Set<String> = emptySet(), selectable: Boolean = false, onDelete: ((Entry) -> Unit)? = null) {
    entries.groupBy { it.date }.forEach { (date, rows) ->
        item(key = "day-$date") {
            val day = LocalDate.parse(date)
            val label = when (day) { LocalDate.now() -> "今天"; LocalDate.now().minusDays(1) -> "昨天"; else -> "${day.monthValue}月${day.dayOfMonth}日" }
            val totals = Ledger.totals(s.copy(transactions = rows), date, date)
            Row(Modifier.fillMaxWidth().padding(top = 13.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text("  ${day.format(DateTimeFormatter.ofPattern("EE", Locale.CHINA))}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
                Spacer(Modifier.weight(1f))
                Text(listOfNotNull(if (totals.expense != 0L) "支 ${Money.format(totals.expense)}" else null, if (totals.income != 0L) "收 ${Money.format(totals.income)}" else null).joinToString(" · "), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
            }
        }
        items(rows, key = { it.id }) { e ->
            Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface) {
                if (onDelete != null && !selectable) {
                    val swipe = rememberSwipeToDismissBoxState(confirmValueChange = { value -> if (value == SwipeToDismissBoxValue.EndToStart) onDelete(e); false })
                    SwipeToDismissBox(swipe, enableDismissFromStartToEnd = false, backgroundContent = {
                        Box(Modifier.fillMaxSize().background(Expense).padding(end = 24.dp), contentAlignment = Alignment.CenterEnd) { SIcon("trash", "删除账单", tint = Color.White) }
                    }) { Surface(color = MaterialTheme.colorScheme.surface) { EntryRow(s, e, { onEntry(e) }) } }
                } else EntryRow(s, e, { onEntry(e) }, e.id in selected, selectable && e.kind == "normal" && e.type in listOf("expense", "income"))
            }
        }
    }
}

fun rangeLabel(start: String, end: String): String {
    val a = LocalDate.parse(start); val b = LocalDate.parse(end)
    return if (a.dayOfMonth == 1 && b == YearMonth.from(a).atEndOfMonth()) "${a.year}年${a.monthValue}月"
    else if (start == "1900-01-01") "全部时间"
    else if (a.year == b.year) "${a.year}年 ${a.monthValue}.${a.dayOfMonth}–${b.monthValue}.${b.dayOfMonth}" else "$start – $end"
}

@Composable
fun RangeSheet(start: String, end: String, onDismiss: () -> Unit, onApply: (String, String) -> Unit) {
    var a by remember { mutableStateOf(start) }; var b by remember { mutableStateOf(end) }
    var picking by remember { mutableStateOf<String?>(null) }
    var month by remember { mutableStateOf(YearMonth.from(LocalDate.parse(end))) }
    SimpleSheet("时间范围", onDismiss) {
        Hint("快捷范围")
        ChoiceChips(listOf("month" to "本月", "last" to "上月", "three" to "近 3 个月", "year" to "今年", "all" to "全部"), null) { key ->
            val today = LocalDate.now(); val ym = YearMonth.now()
            val range = when (key) {
                "month" -> ym.atDay(1) to ym.atEndOfMonth()
                "last" -> ym.minusMonths(1).atDay(1) to ym.minusMonths(1).atEndOfMonth()
                "three" -> ym.minusMonths(2).atDay(1) to today
                "year" -> today.withDayOfYear(1) to today
                else -> LocalDate.of(1900, 1, 1) to today
            }; onApply(range.first.toString(), range.second.toString())
        }
        Spacer(Modifier.height(16.dp)); Hint("按月查看")
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            IconButton(onClick = { month = month.minusMonths(1) }) { SIcon("chevL", "上一月") }
            TextButton(onClick = { onApply(month.atDay(1).toString(), month.atEndOfMonth().toString()) }) { Text("${month.year} 年 ${month.monthValue} 月") }
            IconButton(onClick = { month = month.plusMonths(1) }) { SIcon("chevR", "下一月") }
        }
        Spacer(Modifier.height(12.dp)); Hint("自定义区间")
        SettingRow("calendar", "开始日期", a) { picking = "start" }
        SettingRow("calendar", "结束日期", b) { picking = "end" }
        Button(onClick = { onApply(minOf(a, b), maxOf(a, b)) }, modifier = Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(16.dp)) { Text("应用区间") }
    }
    picking?.let { pick -> DateWheel("选择日期", if (pick == "start") a else b, true, { picking = null }) { if (pick == "start") a = it else b = it; picking = null } }
}

@Composable
fun FilteredScreen(s: LedgerState, f: EntryFilter, onBack: () -> Unit, onEntry: (Entry) -> Unit) {
    val entries = remember(s, f) { Ledger.filter(s, f) }
    var shown by remember(s, f) { mutableIntStateOf(100) }
    val totals = Ledger.totals(s.copy(transactions = entries), f.start, f.end)
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { PageHeader(if (f.pendingOnly) "待报销" else s.account(f.accountId)?.name ?: s.category(f.categoryId)?.name ?: "账单明细", onBack) }
        item { SpendCard { SectionTitle("共 ${entries.size} 笔", if (f.pendingOnly) "可报 ¥${Money.format(entries.sumOf { Ledger.pending(s, it) })}" else "收 ¥${Money.format(totals.income)} · 支 ¥${Money.format(totals.expense)}"); Hint("${f.start} — ${f.end}") } }
        if (entries.isEmpty()) item { EmptyState("暂无匹配账单", "试试其他时间或筛选条件") }
        entryGroups(s, entries.take(shown), onEntry)
        if (entries.size > shown) item { TextButton(onClick = { shown += 100 }) { Text("加载更多") } }
    }
}

@Composable
fun DeleteEntryDialog(s: LedgerState, e: Entry, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val count = s.transactions.count { it.originalId == e.id }
    ConfirmDialog("删除这笔账单？", "将删除这笔账单${if (count > 0) "及 $count 笔关联退款 / 报销" else ""}，账户余额和统计会重新计算。可在明细页撤销最近一次删除。", "删除", onDismiss, onConfirm)
}

@Composable
fun EntryDetails(vm: SpendyViewModel, s: LedgerState, e: Entry, onBack: () -> Unit, onEdit: () -> Unit, onCopy: () -> Unit, onRecover: (String) -> Unit) {
    var deleting by remember { mutableStateOf(false) }
    val c = s.category(e.categoryId)
    val linked = s.transactions.filter { it.originalId == e.id }
    val canRecover = e.kind == "normal" && e.type == "expense" && Ledger.recovered(s, e.id) < e.amountMinor
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { PageHeader("账单详情", onBack, action = { IconButton(onClick = onEdit) { SIcon("pencil", "编辑") } }) }
        item { SpendCard(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) { IconCircle(c?.icon ?: if (e.type == "transfer") "refresh" else "wallet", c?.color?.let(::colorOf) ?: MaterialTheme.colorScheme.primary); Spacer(Modifier.width(12.dp)); Text(c?.name ?: if (e.type == "transfer") "账户转账" else "余额调整", fontWeight = FontWeight.Bold) }
            Text("¥ ${Money.format(e.amountMinor)}", fontSize = 34.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 24.dp))
            listOf("类型" to when { e.kind == "refund" -> "关联退款"; e.kind == "reimbursement" -> "报销回收"; e.type == "expense" -> "支出"; e.type == "income" -> "收入"; e.type == "transfer" -> "转账"; else -> "调整" },
                "账户" to (s.account(e.accountId)?.name ?: ""), "日期" to e.date,
                "细分" to (s.subcategory(e.subcategoryId)?.name ?: "不细分"), "标签" to s.tags.filter { it.id in e.tagIds }.joinToString { it.name }.ifBlank { "无" },
                "备注" to e.note.ifBlank { "无" }, "收支统计" to if (e.countInStats && e.type in listOf("income", "expense")) "计入" else "不计入"
            ).forEach { (label, value) -> Row(Modifier.fillMaxWidth().padding(vertical = 9.dp)) { Hint(label, Modifier.width(82.dp)); Text(value, fontSize = 13.sp, modifier = Modifier.weight(1f)) } }
            if (e.type == "transfer") { Hint("转入 ${s.account(e.toAccountId)?.name} · 手续费 ¥${Money.format(e.feeMinor)}") }
            if (e.originalId != null) Hint("原消费：${s.entry(e.originalId)?.date} · ¥${s.entry(e.originalId)?.amountMinor?.let(Money::format)}")
        } }
        if (e.reimbursable || linked.isNotEmpty()) item { SpendCard { SectionTitle("回收进度"); Text("已回收 ¥${Money.format(Ledger.recovered(s, e.id))}", fontWeight = FontWeight.Bold); Hint("${if (e.reimbursable) "待报销" else "可退"} ¥${Money.format(e.amountMinor - Ledger.recovered(s, e.id))}"); linked.forEach { Hint("${it.date}  ${if (it.kind == "refund") "退款" else "报销"}  ¥${Money.format(-it.amountMinor)}") } } }
        if (canRecover) item { Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { onRecover("refund") }, modifier = Modifier.weight(1f)) { Text("登记退款") }
            if (e.reimbursable) Button(onClick = { onRecover("reimbursement") }, modifier = Modifier.weight(1f)) { Text("报销回收") }
        } }
        if (e.kind == "normal" && e.type in listOf("expense", "income")) item { OutlinedButton(onClick = onCopy, modifier = Modifier.fillMaxWidth()) { SIcon("receipt", modifier = Modifier.size(18.dp)); Text("  复制一笔") } }
        item { TextButton(onClick = { deleting = true }, modifier = Modifier.fillMaxWidth()) { Text("删除账单", color = Expense) } }
    }
    if (deleting) DeleteEntryDialog(s, e, { deleting = false }) { vm.action("账单已删除", done = onBack) { vm.repository.delete(setOf(e.id)) } }
}
