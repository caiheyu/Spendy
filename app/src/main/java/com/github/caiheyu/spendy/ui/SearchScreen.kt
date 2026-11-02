@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.github.caiheyu.spendy.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.*
import com.github.caiheyu.spendy.domain.*

@Composable
fun SearchScreen(vm: SpendyViewModel, s: LedgerState, onBack: () -> Unit, onEntry: (Entry) -> Unit) {
    var f by remember { mutableStateOf(EntryFilter()) }
    var query by remember { mutableStateOf("") }
    var advanced by remember { mutableStateOf(false) }
    var batch by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var batchSheet by remember { mutableStateOf(false) }
    var rangeSheet by remember { mutableStateOf(false) }
    var minimum by remember { mutableStateOf("") }; var maximum by remember { mutableStateOf("") }
    val entries = remember(s, f, query) { Ledger.filter(s, f.copy(query = query)) }
    var shown by remember(s, f, query) { mutableIntStateOf(100) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { PageHeader("搜索账单", onBack, action = { TextButton(onClick = { batch = !batch; selected = emptySet() }) { Text(if (batch) "取消" else "批量") } }) }
        item { OutlinedTextField(query, { query = it }, placeholder = { Text("备注、分类、账户或标签") }, leadingIcon = { SIcon("search") }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = CardShape) }
        item { Row { TextButton(onClick = { advanced = !advanced }) { SIcon("filter", modifier = Modifier.size(16.dp)); Text(if (advanced) " 收起筛选" else " 组合筛选") }; TextButton(onClick = { f = EntryFilter(); query = ""; minimum = ""; maximum = ""; selected = emptySet() }) { Text("重置") } } }
        if (advanced) item { SpendCard {
            Hint("流水类型")
            ChoiceChips(listOf("all" to "全部", "expense" to "支出", "income" to "收入", "transfer" to "转账", "refund" to "退款", "reimbursement" to "报销", "adjustment" to "调整"), f.type ?: "all") { f = f.copy(type = it.takeIf { it != "all" }) }
            Hint("账户（含归档）")
            ChoiceChips(listOf("all" to "全部") + s.accounts.map { it.id to it.name }, f.accountId ?: "all") { f = f.copy(accountId = it.takeIf { it != "all" }) }
            Hint("分类（含归档）")
            ChoiceChips(listOf("all" to "全部") + s.categories.map { it.id to it.name }, f.categoryId ?: "all") { f = f.copy(categoryId = it.takeIf { it != "all" }) }
            Hint("标签")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) { s.tags.forEach { t -> TagChoiceChip(t, t.id in f.tagIds) { f = f.copy(tagIds = if (t.id in f.tagIds) f.tagIds - t.id else f.tagIds + t.id) } } }
            ChoiceChips(listOf("all" to "同时包含", "any" to "包含任意"), if (f.matchAllTags) "all" else "any") { f = f.copy(matchAllTags = it == "all") }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(minimum, { value -> minimum = value; f = f.copy(min = runCatching { Money.parse(value).also { require(it >= 0) } }.getOrNull()) }, label = { Text("最低金额") }, modifier = Modifier.weight(1f), singleLine = true)
                OutlinedTextField(maximum, { value -> maximum = value; f = f.copy(max = runCatching { Money.parse(value).also { require(it >= 0) } }.getOrNull()) }, label = { Text("最高金额") }, modifier = Modifier.weight(1f), singleLine = true)
            }
            SettingRow("calendar", "日期范围", rangeLabel(f.start, f.end)) { rangeSheet = true }
            Row { Checkbox(f.excludedOnly, { f = f.copy(excludedOnly = it) }); Text("仅看统计排除项", modifier = Modifier.padding(top = 14.dp), fontSize = 12.sp) }
        } }
        item { Hint("共 ${entries.size} 笔${if (batch) " · 已选 ${selected.size} 笔，特殊流水需逐笔修改" else ""}") }
        if (batch) item { Row {
            TextButton(onClick = { selected = entries.filter { it.kind == "normal" && it.type in listOf("income", "expense") }.map { it.id }.toSet() }) { Text("全选普通收支") }
            Button(enabled = selected.isNotEmpty(), onClick = { batchSheet = true }) { Text("批量修改 ${selected.size} 笔") }
        } }
        if (entries.isEmpty()) item { EmptyState("没有找到匹配的账单", "调整关键字或筛选条件再试试", "search") }
        entryGroups(s, entries.take(shown), { e -> if (batch) { if (e.kind == "normal" && e.type in listOf("income", "expense")) selected = if (e.id in selected) selected - e.id else selected + e.id } else onEntry(e) }, selected, batch)
        if (entries.size > shown) item { TextButton(onClick = { shown += 100 }) { Text("加载更多") } }
    }
    if (rangeSheet) RangeSheet(f.start, f.end, { rangeSheet = false }) { a, b -> f = f.copy(start = a, end = b); rangeSheet = false }
    if (batchSheet) BatchSheet(vm, s, selected, { batchSheet = false }) { batchSheet = false; selected = emptySet(); batch = false }
}

@Composable
fun BatchSheet(vm: SpendyViewModel, s: LedgerState, ids: Set<String>, onDismiss: () -> Unit, onDone: () -> Unit) {
    var category by remember { mutableStateOf<String?>(null) }; var account by remember { mutableStateOf<String?>(null) }
    var stats by remember { mutableStateOf<Boolean?>(null) }
    var add by remember { mutableStateOf(emptySet<String>()) }; var remove by remember { mutableStateOf(emptySet<String>()) }
    var confirm by remember { mutableStateOf(false) }
    val types = s.transactions.filter { it.id in ids }.map { it.type }.distinct()
    SimpleSheet("批量修改 · ${ids.size} 笔", onDismiss) {
        Hint("未选择的字段保持原样。收入和支出混合选择时不能统一分类。")
        if (types.size == 1) {
            Hint("统一分类")
            ChoiceChips(listOf("keep" to "保持原样") + s.categories.filter { it.type == types.first() && !it.archived }.map { it.id to it.name }, category ?: "keep") { category = it.takeIf { it != "keep" } }
        }
        Hint("统一账户")
        ChoiceChips(listOf("keep" to "保持原样") + s.accounts.filter { !it.archived }.map { it.id to it.name }, account ?: "keep") { account = it.takeIf { it != "keep" } }
        Hint("收支统计")
        ChoiceChips(listOf("keep" to "保持原样", "yes" to "计入", "no" to "排除"), when (stats) { true -> "yes"; false -> "no"; null -> "keep" }) { stats = when (it) { "yes" -> true; "no" -> false; else -> null } }
        Hint("添加标签")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) { s.tags.filter { !it.archived }.forEach { t -> TagChoiceChip(t, t.id in add) { add = if (t.id in add) add - t.id else add + t.id; remove -= t.id } } }
        Hint("移除标签（含归档）")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) { s.tags.forEach { t -> TagChoiceChip(t, t.id in remove) { remove = if (t.id in remove) remove - t.id else remove + t.id; add -= t.id } } }
        Button(onClick = { confirm = true }, enabled = category != null || account != null || stats != null || add.isNotEmpty() || remove.isNotEmpty(), modifier = Modifier.fillMaxWidth()) { Text("预览并提交") }
    }
    if (confirm) ConfirmDialog("确认批量修改？", "将修改 ${ids.size} 笔普通收支，并联动 ${s.transactions.count { it.originalId in ids }} 笔退款 / 报销记录。全部修改同事务保存，可撤销最近一次操作。", "提交", { confirm = false }) {
        vm.action("批量修改已保存，可在明细撤销", done = onDone) { vm.repository.batch(ids, category, account, add, remove, stats) }
    }
}
