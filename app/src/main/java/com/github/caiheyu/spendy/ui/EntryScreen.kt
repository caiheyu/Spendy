@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.github.caiheyu.spendy.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.caiheyu.spendy.domain.*
import com.github.caiheyu.spendy.ui.theme.*

@Composable
fun EntryScreen(vm: SpendyViewModel, s: LedgerState, onClose: () -> Unit, onNewAccount: () -> Unit) {
    val current by vm.editor.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val error by vm.editorError.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { if (vm.editor.value == null) vm.openEditor() }
    val draft = current ?: return
    var sheet by remember { mutableStateOf<String?>(null) }
    var leave by remember { mutableStateOf(false) }
    val linked = draft.kind != "normal"
    val special = draft.type in listOf("transfer", "adjustment")
    val old = s.entry(draft.id)
    val amount = runCatching { Money.evaluate(draft.expression) }.getOrDefault(0)
    fun change(transform: (EntryDraft) -> EntryDraft) = vm.updateDraft(transform(draft))
    fun close() { if (draft.expression.isBlank() && draft.note.isBlank() && draft.id == null) vm.leaveEditor(false, onClose) else leave = true }
    BackHandler { close() }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { close() }) { SIcon("chevL", "返回") }
            Text(when { draft.kind == "refund" -> "登记退款"; draft.kind == "reimbursement" -> "报销回收"; draft.id != null -> "编辑账单"; else -> "记一笔" }, modifier = Modifier.weight(1f), textAlign = TextAlign.Center, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            IconButton(onClick = { sheet = "more" }) { SIcon("dots", "其他记账方式") }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 22.dp)) {
            if (!linked && draft.id == null || !linked && !special) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                LedgerTypeSelector(listOf("expense" to "支出", "income" to "收入") + if (draft.id == null) listOf("transfer" to "转账") else emptyList(), draft.type, tabs = true) { type ->
                    change { it.copy(type = type, categoryId = s.categories.firstOrNull { c -> c.type == type && !c.archived }?.id,
                        subcategoryId = null, reimbursable = false, tagIds = if (type == "transfer") emptyList() else it.tagIds) }
                }
            }
            Spacer(Modifier.height(18.dp)); Hint(if (linked) "实际到账金额" else if (draft.type == "adjustment") "调整差额（支持负数）" else "金额")
            Row(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("¥", fontSize = 27.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(draft.expression.ifBlank { "0" }, fontSize = if (draft.expression.length > 14) 27.sp else 40.sp, fontWeight = FontWeight.Bold, maxLines = 2, modifier = Modifier.weight(1f).padding(start = 10.dp))
            }
            if (draft.expression.drop(1).any { it == '+' || it == '-' }) Hint("= ¥ ${Money.format(amount)}")
            Spacer(Modifier.height(18.dp))
            if (!linked && !special) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("分类", fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    TextButton(onClick = { sheet = "category" }) { Text("全部分类  ›", fontSize = 11.sp) }
                }
                val cats = s.categories.filter { it.type == draft.type && (!it.archived || old?.categoryId == it.id) }.sortedBy { it.sortOrder }
                val visible = cats.take(8).toMutableList()
                cats.find { it.id == draft.categoryId && it !in visible }?.let { if (visible.isNotEmpty()) visible[visible.lastIndex] = it }
                visible.chunked(4).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEach { c ->
                            Column(Modifier.weight(1f).clip(RoundedCornerShape(16.dp))
                                .clickable { change { it.copy(categoryId = c.id, subcategoryId = null) }; if (s.subcategories.any { it.categoryId == c.id && !it.archived }) sheet = "subcategory" }
                                .padding(vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(7.dp)) {
                                val active = draft.categoryId == c.id
                                Box(Modifier.size(42.dp).clip(RoundedCornerShape(12.dp)).background(if (active) colorOf(c.color).copy(alpha = .12f) else Color.Transparent), contentAlignment = Alignment.Center) {
                                    LibrarySIcon(c.icon, modifier = Modifier.size(24.dp), tint = if (active) colorOf(c.color) else MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Text(c.name, fontSize = 11.sp, fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                                    color = if (active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
                if (s.subcategories.any { it.categoryId == draft.categoryId }) TextButton(onClick = { sheet = "subcategory" }, modifier = Modifier.fillMaxWidth()) { Text("细分：${s.subcategory(draft.subcategoryId)?.name ?: "不细分"}  ›", fontSize = 12.sp) }
            }
            if (linked) SpendCard(Modifier.fillMaxWidth()) {
                Text("${if (draft.kind == "refund") "退款" else "报销"}关联原消费", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                val original = s.entry(draft.originalId)
                Hint("${original?.date} · ${s.category(original?.categoryId)?.name}")
                Hint("剩余可回收 ¥${Money.format((original?.amountMinor ?: 0) - Ledger.recovered(s, draft.originalId.orEmpty()))}，分类、标签和统计设置跟随原单。")
            }
            Spacer(Modifier.height(12.dp))
            Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    SettingRow("wallet", if (draft.type == "transfer") "转出账户" else if (linked) "到账账户" else "账户", s.account(draft.accountId)?.name ?: "选择账户") { sheet = "account" }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    if (draft.type == "transfer") {
                        SettingRow("refresh", "转入账户", s.account(draft.toAccountId)?.name ?: "选择转入账户") { sheet = "toAccount" }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        SettingRow("receipt", "手续费", if (draft.fee.isBlank()) "无" else "¥${draft.fee}") { sheet = "fee" }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                    SettingRow("calendar", "日期", draft.date) { sheet = "date" }
                    if (!special && !linked) { HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant); SettingRow("tag", "标签", s.tags.filter { it.id in draft.tagIds }.joinToString { it.name }.ifBlank { "添加标签" }) { sheet = "tags" } }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    SettingRow("pencil", "备注", draft.note.ifBlank { "写点什么，方便以后回忆" }) { sheet = "note" }
                }
            }
            if (!linked && !special) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(draft.countInStats, { value -> change { it.copy(countInStats = value) } })
                    Text("计入个人收支统计", fontSize = 12.sp)
                }
                if (draft.type == "expense") Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(draft.reimbursable, enabled = s.transactions.none { it.originalId == draft.id && it.kind == "reimbursement" }, onCheckedChange = { value -> change { it.copy(reimbursable = value) } })
                    Text("这笔支出可报销", fontSize = 12.sp)
                }
                if (!draft.countInStats) Hint("仅从个人统计中排除，真实账户余额仍会变化。")
            }
            Spacer(Modifier.height(16.dp))
        }
        Surface(color = MaterialTheme.colorScheme.outlineVariant, shadowElevation = 0.dp) {
            Column(Modifier.padding(top = 1.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                error?.let { Text(it, color = Expense, fontSize = 12.sp,
                    modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).padding(horizontal = 16.dp, vertical = 8.dp)) }
                listOf(listOf("1", "2", "3", "+"), listOf("4", "5", "6", "−"), listOf("7", "8", "9", "⌫"), listOf(".", "0", "再记", "完成")).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(1.dp)) { row.forEach { key ->
                        val done = key == "完成"
                        val again = key == "再记"
                        val enabled = !busy && (!again || draft.id == null && !linked && draft.type != "adjustment")
                        if (done) Button(onClick = { vm.saveEditor(false, onClose) }, enabled = enabled,
                            modifier = Modifier.weight(1f).height(52.dp).testTag("entry-save"),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.onSurface, contentColor = MaterialTheme.colorScheme.background),
                            shape = RoundedCornerShape(0.dp), contentPadding = PaddingValues(0.dp)) {
                            Text(if (draft.id != null) "保存" else key, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        } else Box(Modifier.weight(1f).height(52.dp).background(
                            MaterialTheme.colorScheme.background)
                            .clickable(enabled = enabled) {
                                when (key) {
                                    "再记" -> vm.saveEditor(true, onClose)
                                    else -> change { it.copy(expression = keypad(it.expression, key)) }
                                }
                            }, contentAlignment = Alignment.Center) {
                            if (key == "⌫") SIcon("backspace", "退格", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            else Text(key, fontSize = if (again) 14.sp else 22.sp, fontWeight = if (again) FontWeight.Medium else FontWeight.Medium,
                                color = if (!enabled) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .35f) else if (again || key == "+" || key == "−") MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                        }
                    } }
                }
            }
        }
    }
    if (leave) AlertDialog(onDismissRequest = { leave = false }, title = { Text("保留这笔草稿？") }, text = { Text("未完成的内容仅保存在本机，下次点击记账可继续。") },
        confirmButton = { TextButton(onClick = { vm.leaveEditor(true, onClose) }) { Text("保留并离开") } },
        dismissButton = { TextButton(onClick = { vm.leaveEditor(false, onClose) }) { Text("放弃草稿", color = Expense) } })
    when (sheet) {
        "date" -> DateWheel("选择流水日期", draft.date, onDismiss = { sheet = null }) { value -> change { it.copy(date = value) }; sheet = null }
        "account", "toAccount" -> SimpleSheet(if (sheet == "account") "选择账户" else "选择转入账户", { sheet = null }) {
            s.accounts.filter { !it.archived || it.id == old?.accountId || it.id == old?.toAccountId }.sortedBy { it.sortOrder }.forEach { a ->
                SettingRow(a.icon, a.name, if (vm.settings.value.hideAmounts) "****" else "¥${Money.format(Ledger.balance(s, a.id))}", colorOf(a.color), libraryIcon = true) {
                    if (sheet == "account") change { it.copy(accountId = a.id) } else change { it.copy(toAccountId = a.id) }; sheet = null
                }
            }
            OutlinedButton(onClick = { sheet = null; onNewAccount() }, modifier = Modifier.fillMaxWidth()) { Text("新建账户") }
        }
        "category" -> SimpleSheet("选择分类", { sheet = null }) {
            s.categories.filter { it.type == draft.type && (!it.archived || it.id == old?.categoryId) }.sortedBy { it.sortOrder }.forEach { c ->
                SettingRow(c.icon, c.name, color = colorOf(c.color), libraryIcon = true) { change { it.copy(categoryId = c.id, subcategoryId = null) }; sheet = if (s.subcategories.any { it.categoryId == c.id && !it.archived }) "subcategory" else null }
            }
        }
        "subcategory" -> SimpleSheet("${s.category(draft.categoryId)?.name.orEmpty()} · 细分", { sheet = null }) {
            SettingRow("dots", "不细分") { change { it.copy(subcategoryId = null) }; sheet = null }
            s.subcategories.filter { it.categoryId == draft.categoryId && (!it.archived || it.id == old?.subcategoryId) }.sortedBy { it.sortOrder }.forEach { sub ->
                SettingRow(s.category(draft.categoryId)?.icon ?: "tag", sub.name, libraryIcon = true) { change { it.copy(subcategoryId = sub.id) }; sheet = null }
            }
        }
        "tags" -> SimpleSheet("选择标签 · 可多选", { sheet = null }) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { s.tags.filter { !it.archived || it.id in old?.tagIds.orEmpty() }.forEach { t ->
                TagChoiceChip(t, t.id in draft.tagIds) { change { it.copy(tagIds = if (t.id in it.tagIds) it.tagIds - t.id else it.tagIds + t.id) } }
            } }
            Button(onClick = { sheet = null }, modifier = Modifier.fillMaxWidth()) { Text("完成") }
        }
        "note", "fee" -> {
            var value by remember(sheet) { mutableStateOf(if (sheet == "note") draft.note else draft.fee) }
            AlertDialog(onDismissRequest = { sheet = null }, title = { Text(if (sheet == "note") "添加备注" else "转账手续费") }, text = {
                OutlinedTextField(value, { if (it.length <= if (sheet == "note") 1000 else 15) value = it }, modifier = Modifier.fillMaxWidth(), minLines = if (sheet == "note") 3 else 1)
            }, confirmButton = { TextButton(onClick = { if (sheet == "note") change { it.copy(note = value.trim()) } else change { it.copy(fee = value.trim()) }; sheet = null }) { Text("保存") } }, dismissButton = { TextButton(onClick = { sheet = null }) { Text("取消") } })
        }
        "more" -> SimpleSheet("资金记录", { sheet = null }) {
            Hint("提现、充值和信用卡还款均使用账户转账，本金不计入收支。")
            if (draft.id == null && !linked) SettingRow("refresh", "账户转账", "可同时登记手续费") { change { it.copy(type = "transfer", categoryId = null, subcategoryId = null, tagIds = emptyList(), reimbursable = false, countInStats = true) }; sheet = null }
            Hint("退款与报销请从原消费的账单详情发起。余额对账请进入账户页。")
        }
    }
}

fun keypad(expression: String, key: String): String {
    if (key == "⌫") return expression.dropLast(1)
    if (expression.length >= 40) return expression
    if (key == "+" || key == "−") {
        val op = if (key == "−") "-" else "+"
        if (expression.isEmpty()) return if (op == "-") "-" else ""
        return expression.trimEnd('+', '-', '.') + op
    }
    val segment = expression.split('+', '-').last()
    if (key == ".") return if ('.' in segment) expression else expression + if (segment.isBlank()) "0." else "."
    if (segment.substringAfter('.', "").length >= 2 && '.' in segment) return expression
    if (segment.length >= 12 && '.' !in segment) return expression
    return if (segment == "0") expression.dropLast(1) + key else expression + key
}
