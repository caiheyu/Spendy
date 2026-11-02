@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.github.caiheyu.spendy.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.caiheyu.spendy.domain.*
import com.github.caiheyu.spendy.ui.theme.*
import java.time.*
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun ChartsScreen(vm: SpendyViewModel, s: LedgerState, onBrowse: (EntryFilter) -> Unit) {
    var mode by rememberSaveable { mutableStateOf("month") }
    var anchorText by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
    var type by rememberSaveable { mutableStateOf("expense") }
    var customStart by rememberSaveable { mutableStateOf(YearMonth.now().atDay(1).toString()) }
    var customEnd by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
    var rangeSheet by remember { mutableStateOf(false) }
    var drill by rememberSaveable { mutableStateOf<String?>(null) }
    var selected by remember(mode, anchorText, customStart, customEnd) { mutableStateOf<Int?>(null) }
    val settings by vm.settings.collectAsStateWithLifecycle()
    val anchor = LocalDate.parse(anchorText)
    val range = if (mode == "custom") DateRange(LocalDate.parse(customStart), LocalDate.parse(customEnd)) else Statistics.range(mode, anchor)
    val buckets = remember(s, range, mode) { Statistics.buckets(s, range, mode) }
    val points = remember(s, range, type) { Ledger.project(s).filter { it.type == type && it.date in range.startText..range.endText } }
    val total = points.sumOf { it.amount }
    val elapsed = if (range.start > LocalDate.now()) 0 else if (mode == "year") minOf(range.end, LocalDate.now()).monthValue else (ChronoUnit.DAYS.between(range.start, minOf(range.end, LocalDate.now())) + 1).toInt()
    val grouped = points.groupBy { it.categoryId }.map { it.key to it.value.sumOf { p -> p.amount } }.filter { it.second != 0L }.sortedByDescending { abs(it.second) }
    val base = EntryFilter(start = range.startText, end = range.endText, statType = type)
    val chartColor = if (type == "expense") Expense else Income
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 18.dp, end = 18.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { PageHeader("图表") }
        item { SpendCard {
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                LedgerTypeSelector(listOf("expense" to "支出", "income" to "收入"), type) { type = it; drill = null; selected = null }
                Row { listOf("week" to "周", "month" to "月", "year" to "年", "custom" to "自定").forEach { (key, label) ->
                    TextButton(onClick = { if (key == "custom") rangeSheet = true else mode = key; drill = null }, contentPadding = PaddingValues(horizontal = 5.dp), modifier = Modifier.width(38.dp)) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(label, fontSize = 12.sp, fontWeight = if (mode == key) FontWeight.Bold else FontWeight.Normal, color = if (mode == key) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(5.dp))
                            HorizontalDivider(Modifier.width(18.dp), color = if (mode == key) MaterialTheme.colorScheme.primary else Color.Transparent, thickness = 2.dp)
                        }
                    }
                } }
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(enabled = mode != "custom", onClick = { anchorText = Statistics.shift(mode, anchor, -1).toString(); drill = null }, modifier = Modifier.size(30.dp)) { SIcon("chevL", "上一期", Modifier.size(17.dp)) }
                TextButton(onClick = { rangeSheet = true }, modifier = Modifier.weight(1f)) { Text(if (mode == "year") "${anchor.year}年" else rangeLabel(range.startText, range.endText), fontSize = 13.sp) }
                val next = Statistics.range(mode, Statistics.shift(mode, anchor, 1))
                IconButton(enabled = mode != "custom" && next.start <= LocalDate.now(), onClick = { anchorText = Statistics.shift(mode, anchor, 1).toString(); drill = null }, modifier = Modifier.size(30.dp)) { SIcon("chevR", "下一期", Modifier.size(17.dp)) }
            }
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                Column { Hint(if (type == "expense") "本期净支出" else "本期收入总额"); Text("¥${Money.format(total)}", fontSize = 25.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 5.dp)) }
                Column(horizontalAlignment = Alignment.End) { Hint(if (mode == "year") "月均" else "日均${if (type == "expense") "净支出" else "收入"}"); Text("¥${Money.format(if (elapsed > 0) total / elapsed else 0)}", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 5.dp)) }
            }
            Spacer(Modifier.height(10.dp)); Hint(Statistics.comparison(s, range, mode, type))
            LineGraph(buckets.map { it.label }, listOf(GraphLine(if (type == "expense") "实际支出" else "收入", buckets.map { if (type == "expense") it.expense else it.income }, chartColor)), selected = selected, onSelect = { selected = it })
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { Legend(chartColor, if (type == "expense") "实际支出" else "收入"); if (type == "expense") Legend(Income, "退款 / 报销冲减净额") }
            selected?.let { index -> buckets.getOrNull(index)?.let { b ->
                Surface(color = MaterialTheme.colorScheme.primary.copy(alpha = .07f), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().padding(top = 12.dp).clickable { onBrowse(base.copy(start = b.start, end = b.end, statType = null)) }) {
                    Column(Modifier.padding(12.dp)) { Text("${b.start}${if (b.end != b.start) " — ${b.end}" else ""}", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Hint(if (b.expense == null) "尚未开始" else "支出 ¥${Money.format(b.expense)} · 回收 ¥${Money.format(b.recovery ?: 0)}\n收入 ¥${Money.format(b.income ?: 0)} · 结余 ¥${Money.format(b.balance ?: 0)}  ›") }
                }
            } }
            HorizontalDivider(Modifier.padding(vertical = 20.dp), color = MaterialTheme.colorScheme.outlineVariant)
            val cat = s.category(drill)
            if (cat == null) {
                SectionTitle(if (type == "expense") "支出排行榜" else "收入排行榜", "${grouped.size} 个分类")
                if (grouped.isEmpty()) ChartEmpty("该区间暂无${if (type == "expense") "支出" else "收入"}")
                grouped.forEach { (id, amount) -> val c = s.category(id); RankRow(c?.name ?: "未分类", amount, total, grouped.maxOfOrNull { abs(it.second) } ?: 1, c?.color?.let(::colorOf) ?: chartColor) { drill = id } }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) { IconButton(onClick = { drill = null }, modifier = Modifier.size(30.dp)) { SIcon("chevL", "返回分类排行", Modifier.size(17.dp)) }; Text("${cat.name} · 子分类", fontWeight = FontWeight.Bold, fontSize = 15.sp) }
                val sub = points.filter { it.categoryId == cat.id }.groupBy { it.subcategoryId }.map { it.key to it.value.sumOf { p -> p.amount } }.sortedByDescending { abs(it.second) }
                sub.forEach { (id, amount) -> RankRow(s.subcategory(id)?.name ?: "不细分", amount, sub.sumOf { it.second }, sub.maxOfOrNull { abs(it.second) } ?: 1, colorOf(cat.color)) {
                    onBrowse(base.copy(categoryId = cat.id, subcategoryId = id, withoutSubcategory = id == null))
                } }
                Spacer(Modifier.height(12.dp)); Hint("近 6 个月 · ${cat.name}")
                val months = (5 downTo 0).map { YearMonth.from(range.end).minusMonths(it.toLong()) }
                val vals = months.map { m -> Ledger.project(s).filter { it.type == type && it.categoryId == cat.id && it.date in m.atDay(1).toString()..m.atEndOfMonth().toString() }.sumOf { it.amount } }
                BarGraph(months.map { "${it.monthValue}月" }, vals, colorOf(cat.color)) { i -> val m = months[i]; onBrowse(base.copy(categoryId = cat.id, start = m.atDay(1).toString(), end = m.atEndOfMonth().toString())) }
                TextButton(onClick = { onBrowse(base.copy(categoryId = cat.id)) }, modifier = Modifier.fillMaxWidth()) { Text("查看${cat.name}全部账单  ›") }
            }
        } }
        item { SpendCard {
            SectionTitle("${if (type == "expense") "支出" else "收入"}标签分布", "点按看明细")
            val tags = points.flatMap { p -> p.tagIds.map { it to p.amount } }.groupBy { it.first }.map { it.key to it.value.sumOf { p -> p.second } }.filter { it.second != 0L }.sortedByDescending { abs(it.second) }
            val tagTotal = tags.sumOf { it.second }
            if (tags.isEmpty()) ChartEmpty("该区间暂无标签数据")
            if (tagTotal > 0 && tags.all { it.second >= 0 }) Row(Modifier.fillMaxWidth().height(16.dp).clip(RoundedCornerShape(8.dp))) {
                tags.take(5).forEach { (id, amount) -> if (amount > 0) Box(Modifier.weight(amount.toFloat()).fillMaxHeight().background(colorOf(s.tags.first { it.id == id }.color)).clickable { onBrowse(base.copy(tagIds = setOf(id))) }) }
                val other = tags.drop(5).sumOf { it.second }; if (other > 0) Box(Modifier.weight(other.toFloat()).fillMaxHeight().background(Color(0xFF8A8D9F)))
            }
            tags.forEach { (id, amount) -> val tag = s.tags.first { it.id == id }; RankRow(tag.name, amount, tagTotal, tags.maxOfOrNull { abs(it.second) } ?: 1, colorOf(tag.color), false) { onBrowse(base.copy(tagIds = setOf(id))) } }
            if (tags.isNotEmpty()) Hint("同一笔的多个标签会重复计入，比例按标签累计金额之和计算。")
        } }
        item { SpendCard {
            SectionTitle("${if (type == "expense") "支出" else "收入"}账户分布")
            val accounts = points.groupBy { it.accountId }.map { it.key to it.value.sumOf { p -> p.amount } }.filter { it.second != 0L }.sortedByDescending { abs(it.second) }
            if (accounts.isEmpty()) ChartEmpty("该区间暂无账户数据")
            accounts.forEach { (id, amount) -> val a = s.account(id); RankRow(a?.name.orEmpty(), amount, total, accounts.maxOfOrNull { abs(it.second) } ?: 1, a?.color?.let(::colorOf) ?: chartColor) { onBrowse(base.copy(accountId = id)) } }
        } }
        item { SpendCard {
            Row(verticalAlignment = Alignment.CenterVertically) { Text("账户净额趋势", fontWeight = FontWeight.Bold, fontSize = 15.sp, modifier = Modifier.weight(1f)); IconButton(onClick = { vm.changeSettings { it.copy(hideAmounts = !it.hideAmounts) } }, modifier = Modifier.size(30.dp)) { SIcon(if (settings.hideAmounts) "eyeOff" else "eye", if (settings.hideAmounts) "显示金额" else "隐藏金额", Modifier.size(18.dp)) } }
            if (settings.hideAmounts) Column(Modifier.fillMaxWidth().padding(vertical = 44.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) { SIcon("eyeOff", tint = MaterialTheme.colorScheme.onSurfaceVariant); Hint("金额已隐藏，点小眼睛查看") }
            else {
                val worth = buckets.map { b -> if (LocalDate.parse(b.start) > LocalDate.now()) null else Ledger.net(s, minOf(b.end, LocalDate.now().toString())) }
                Hint("期末 ¥${Money.format(Ledger.net(s, minOf(range.endText, LocalDate.now().toString())))}")
                LineGraph(buckets.map { it.label }, listOf(GraphLine("账户净额", worth, MaterialTheme.colorScheme.primary)), area = true)
            }
            Hint("全部已记录账户（含归档），以真实资金流和期初生效日计算。")
        } }
        item { SpendCard {
            SectionTitle("星期分布", if (type == "expense") "净支出合计" else "收入合计")
            val values = (1..7).map { day -> points.filter { LocalDate.parse(it.date).dayOfWeek.value == day }.sumOf { it.amount } }
            BarGraph(listOf("一", "二", "三", "四", "五", "六", "日"), values, chartColor)
        } }
        item { SpendCard {
            if (mode == "year") {
                SectionTitle("月度对比", "点按查看月份")
                val months = (1..12).map { YearMonth.of(anchor.year, it) }
                BarGraph(months.map { "${it.monthValue}" }, months.map { m -> points.filter { it.date.startsWith(m.toString()) }.sumOf { it.amount } }, chartColor) { index -> anchorText = months[index].atDay(1).toString(); mode = "month" }
            } else if (mode == "custom") {
                SectionTitle("连续热力图", "少 → 多")
                val daily = Ledger.project(s).filter { it.type == "expense" && it.date in range.startText..range.endText }.groupBy { it.date }.mapValues { it.value.sumOf { p -> p.amount } }
                val dailyMax = daily.values.maxOfOrNull { abs(it) } ?: 1
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    val count = (ChronoUnit.DAYS.between(range.start, range.end) + 1).coerceAtMost(46_400).toInt()
                    val days = (0 until count).map { range.start.plusDays(it.toLong()) }
                    days.chunked(7).forEach { column -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) { column.forEach { day ->
                        HeatCell(day, daily[day.toString()] ?: 0, dailyMax, false) { onBrowse(EntryFilter(start = day.toString(), end = day.toString())) }
                    } } }
                }
            } else CalendarChart(s, range, points, onBrowse)
        } }
    }
    if (rangeSheet) RangeSheet(customStart, customEnd, { rangeSheet = false }) { a, b -> customStart = a; customEnd = b; mode = "custom"; drill = null; rangeSheet = false }
}

data class GraphLine(val name: String, val values: List<Long?>, val color: Color)

@Composable
fun LineGraph(labels: List<String>, series: List<GraphLine>, selected: Int? = null, area: Boolean = false, onSelect: ((Int) -> Unit)? = null) {
    val values = series.flatMap { it.values }.filterNotNull()
    val minimum = minOf(0L, values.minOrNull() ?: 0).toDouble()
    val maximum = maxOf(100L, values.maxOrNull() ?: 0).toDouble()
    val span = (maximum - minimum).coerceAtLeast(1.0)
    val grid = MaterialTheme.colorScheme.outlineVariant
    val description = series.joinToString { line -> line.name + "：" + line.values.mapIndexed { i, v -> "${labels.getOrNull(i)} ${v?.let(Money::format) ?: "尚未开始"}" }.joinToString("，") }
    Row(Modifier.fillMaxWidth().padding(top = 16.dp)) {
        Column(Modifier.width(39.dp).height(152.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Hint(axisLabel(maximum.toLong())); Hint(axisLabel(((maximum + minimum) / 2).toLong())); Hint(axisLabel(minimum.toLong()))
        }
        Canvas(Modifier.weight(1f).height(152.dp).semantics { contentDescription = description }
            .pointerInput(labels, onSelect) { if (onSelect != null) detectTapGestures { p -> onSelect(((p.x / size.width) * (labels.size - 1)).roundToInt().coerceIn(0, (labels.size - 1).coerceAtLeast(0))) } }) {
            val top = 7.dp.toPx(); val height = size.height - 16.dp.toPx()
            fun y(v: Long) = top + height * (1.0 - (v - minimum) / span).toFloat()
            fun x(i: Int) = if (labels.size <= 1) size.width / 2 else i * size.width / (labels.size - 1)
            repeat(3) { i -> val yy = top + height * i / 2; drawLine(grid, Offset(0f, yy), Offset(size.width, yy), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f, 6f))) }
            series.forEach { line ->
                val path = Path(); var begun = false; var firstIndex = -1; var lastIndex = -1
                line.values.forEachIndexed { i, value -> if (value != null) { if (!begun) { path.moveTo(x(i), y(value)); begun = true; firstIndex = i } else path.lineTo(x(i), y(value)); lastIndex = i } }
                if (area && firstIndex >= 0) { val fill = Path().apply { addPath(path); lineTo(x(lastIndex), y(0)); lineTo(x(firstIndex), y(0)); close() }; drawPath(fill, Brush.verticalGradient(listOf(line.color.copy(alpha = .22f), line.color.copy(alpha = .015f)))) }
                drawPath(path, line.color, style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                line.values.forEachIndexed { i, value -> if (value != null && (labels.size <= 7 || selected == i || i == lastIndex)) { drawCircle(line.color, if (selected == i) 4.dp.toPx() else 2.5.dp.toPx(), Offset(x(i), y(value))); drawCircle(Color.White, 1.4.dp.toPx(), Offset(x(i), y(value))) } }
            }
            selected?.let { i -> drawLine(grid, Offset(x(i), 0f), Offset(x(i), size.height), 1.dp.toPx()) }
        }
    }
    Row(Modifier.fillMaxWidth().padding(start = 39.dp, top = 6.dp, bottom = 14.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        listOf(0, (labels.size - 1) / 2, labels.size - 1).distinct().filter { it >= 0 }.forEach { index -> Text(labels.getOrNull(index).orEmpty(), fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

private fun axisLabel(value: Long) = when { abs(value) >= 1_000_000 -> "${value / 1_000_000.0}万"; abs(value) >= 100_000 -> "${value / 100_000.0}k"; else -> "${value / 100}" }
@Composable private fun Legend(color: Color, text: String) { Row(verticalAlignment = Alignment.CenterVertically) { Box(Modifier.size(6.dp).clip(CircleShape).background(color)); Text("  $text", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
@Composable private fun ChartEmpty(text: String) { Box(Modifier.fillMaxWidth().height(100.dp), contentAlignment = Alignment.Center) { Hint(text) } }

@Composable
private fun RankRow(name: String, amount: Long, total: Long, max: Long, color: Color, bar: Boolean = true, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(color)); Text("  $name", fontSize = 12.sp, modifier = Modifier.weight(1f))
            Text(if (total > 0 && amount >= 0) Money.percent(amount, total) else "—", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(48.dp), textAlign = TextAlign.End)
            Text("¥${Money.format(amount)}", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(88.dp), textAlign = TextAlign.End)
        }
        if (bar) Box(Modifier.fillMaxWidth().padding(top = 9.dp).height(4.dp).clip(CircleShape).background(MaterialTheme.colorScheme.outlineVariant)) { Box(Modifier.fillMaxHeight().fillMaxWidth((abs(amount).toFloat() / max.coerceAtLeast(1)).coerceIn(0f, 1f)).background(color)) }
    }
}

@Composable
fun BarGraph(labels: List<String>, values: List<Long>, color: Color, onClick: ((Int) -> Unit)? = null) {
    val max = values.maxOfOrNull { abs(it) }?.coerceAtLeast(1) ?: 1
    Row(Modifier.fillMaxWidth().height(132.dp).padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.Bottom) {
        values.forEachIndexed { i, value -> Column(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(4.dp)).clickable(enabled = onClick != null) { onClick?.invoke(i) }.semantics { contentDescription = "${labels.getOrNull(i)} ¥${Money.format(value)}" }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
            Box(Modifier.width(17.dp).height((abs(value).toFloat() / max * 88).coerceAtLeast(3f).dp).clip(RoundedCornerShape(topStart = 5.dp, topEnd = 5.dp)).background(if (value < 0) Income else color.copy(alpha = if (abs(value) == max) 1f else .48f)))
            Text(labels.getOrNull(i).orEmpty(), fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
        } }
    }
}

@Composable
private fun CalendarChart(s: LedgerState, range: DateRange, points: List<StatPoint>, onBrowse: (EntryFilter) -> Unit) {
    val month = YearMonth.from(range.end)
    val days = (1..month.lengthOfMonth()).map { month.atDay(it) }
    val amounts = Ledger.project(s).filter { it.type == "expense" && it.date in month.atDay(1).toString()..month.atEndOfMonth().toString() }.groupBy { it.date }.mapValues { it.value.sumOf { p -> p.amount } }
    val max = amounts.values.maxOfOrNull { abs(it) } ?: 1
    SectionTitle("${month.monthValue}月日历", "少 → 多")
    Row(Modifier.fillMaxWidth()) { listOf("一", "二", "三", "四", "五", "六", "日").forEach { Text(it, fontSize = 11.sp, textAlign = TextAlign.Center, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant) } }
    Spacer(Modifier.height(10.dp))
    val cells = List<LocalDate?>(month.atDay(1).dayOfWeek.value - 1) { null } + days
    cells.chunked(7).forEach { row -> Row(Modifier.fillMaxWidth().padding(bottom = 5.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        row.forEach { day -> Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { if (day != null) HeatCell(day, amounts[day.toString()] ?: 0, max, true) { onBrowse(EntryFilter(start = day.toString(), end = day.toString())) } } }
        repeat(7 - row.size) { Spacer(Modifier.weight(1f)) }
    } }
}

@Composable
private fun HeatCell(date: LocalDate, value: Long, max: Long, label: Boolean, onClick: () -> Unit) {
    val color = if (value < 0) Income else Expense
    Box(Modifier.size(if (label) 32.dp else 15.dp).clip(RoundedCornerShape(if (label) 9.dp else 4.dp))
        .background(if (value == 0L) MaterialTheme.colorScheme.background else color.copy(alpha = (.12f + abs(value).toFloat() / max.coerceAtLeast(1) * .65f).coerceIn(.12f, .85f)))
        .then(if (date == LocalDate.now()) Modifier.border(1.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(9.dp)) else Modifier)
        .clickable(onClick = onClick).semantics { contentDescription = "$date 净额 ¥${Money.format(value)}" }, contentAlignment = Alignment.Center) { if (label) Text("${date.dayOfMonth}", fontSize = 10.sp) }
}
