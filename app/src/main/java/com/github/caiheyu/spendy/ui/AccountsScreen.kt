package com.github.caiheyu.spendy.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.caiheyu.spendy.domain.*
import com.github.caiheyu.spendy.ui.theme.*
import java.time.*

@Composable
fun AccountsScreen(vm: SpendyViewModel, s: LedgerState, onBrowse: (EntryFilter) -> Unit, onManage: () -> Unit) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val hidden = settings.hideAmounts
    var reconciling by remember { mutableStateOf<Account?>(null) }
    val month = YearMonth.now()
    val totals = Ledger.totals(s, month.atDay(1).toString(), LocalDate.now().toString())
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 18.dp, end = 18.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { PageHeader("账户", action = { IconButton(onClick = onManage) { SIcon("grid", "账户管理") } }) }
        item { Box(Modifier.fillMaxWidth().clip(CardShape).background(AccountCard)) {
            Canvas(Modifier.matchParentSize()) {
                val center = Offset(size.width - 30.dp.toPx(), 20.dp.toPx())
                val radius = 90.dp.toPx()
                drawCircle(Brush.radialGradient(listOf(SpendyGradientEnd.copy(alpha = .4f), Color.Transparent),
                    center = center, radius = radius), radius = radius, center = center)
            }
            Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("已记录账户净额", Modifier.weight(1f), fontSize = 12.sp, color = Color.White.copy(alpha = .65f))
                IconButton(onClick = { vm.changeSettings { it.copy(hideAmounts = !it.hideAmounts) } }, modifier = Modifier.size(30.dp)) { SIcon(if (hidden) "eyeOff" else "eye", if (hidden) "显示金额" else "隐藏金额", Modifier.size(19.dp), tint = Color.White.copy(alpha = .85f)) }
            }
            Text(if (hidden) "****" else "¥ ${Money.format(Ledger.net(s))}", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp, bottom = 24.dp))
            Row(Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) { Text("本月收入", fontSize = 12.sp, color = Color.White.copy(alpha = .85f)); Text(if (hidden) "****" else "¥${Money.format(totals.income)}", color = AccountIncome, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp)) }
                Column(Modifier.weight(1f)) { Text("本月支出", fontSize = 12.sp, color = Color.White.copy(alpha = .85f)); Text(if (hidden) "****" else "¥${Money.format(totals.expense)}", color = AccountExpense, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp)) }
            }
            Spacer(Modifier.height(18.dp)); Text("仅覆盖你维护的账户，含归档账户及负余额。", fontSize = 12.sp, color = Color.White.copy(alpha = .65f))
            }
        } }
        item { SectionTitle("我的账户", "${s.accounts.size} 个账户") }
        items(s.accounts.sortedWith(compareBy<Account> { it.archived }.thenBy { it.sortOrder }), key = { it.id }) { a ->
            val related = s.transactions.filter { it.accountId == a.id || it.toAccountId == a.id }
            val stats = Ledger.project(s).filter { it.accountId == a.id && it.date in month.atDay(1).toString()..LocalDate.now().toString() }
            val part = Totals(stats.filter { it.type == "expense" }.sumOf { it.amount }, stats.filter { it.type == "income" }.sumOf { it.amount })
            SpendCard(Modifier.fillMaxWidth().clickable { onBrowse(EntryFilter(accountId = a.id)) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconCircle(a.icon, colorOf(a.color)); Spacer(Modifier.width(13.dp))
                    Column(Modifier.weight(1f)) { Text(a.name + if (a.archived) " · 已归档" else "", fontSize = 15.sp, fontWeight = FontWeight.Bold); Hint("期初生效 ${a.openedOn}") }
                    Text(if (hidden) "****" else "¥${Money.format(Ledger.balance(s, a.id))}", fontSize = 19.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(15.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Hint(if (hidden) "本月收入 **** · 支出 ****" else "本月收 ${Money.format(part.income)} · 支 ${Money.format(part.expense)}", Modifier.weight(1f))
                    TextButton(onClick = { reconciling = a }, contentPadding = PaddingValues(horizontal = 5.dp)) { Text("对账", fontSize = 12.sp) }
                }
            }
        }
        item { OutlinedButton(onClick = onManage, modifier = Modifier.fillMaxWidth().height(50.dp), shape = CardShape) { SIcon("plus", modifier = Modifier.size(18.dp)); Text("  新建 / 管理账户") } }
        if (s.accounts.size == 1 && s.transactions.isEmpty()) item { Hint("开始前，可先核对现金期初余额，并添加微信、支付宝或银行卡。") }
    }
    reconciling?.let { a -> ReconcileSheet(vm, s, a, { reconciling = null }) }
}

@Composable
private fun ReconcileSheet(vm: SpendyViewModel, s: LedgerState, a: Account, onDismiss: () -> Unit) {
    var date by remember { mutableStateOf(LocalDate.now().toString()) }
    var actual by remember { mutableStateOf("") }; var reason by remember { mutableStateOf("") }
    var pick by remember { mutableStateOf(false) }; var confirm by remember { mutableStateOf(false) }
    val parsed = runCatching { Money.parse(actual) }.getOrNull()
    val recorded = Ledger.balance(s, a.id, date)
    val delta = parsed?.minus(recorded)
    SimpleSheet("${a.name} · 余额对账", onDismiss) {
        Hint("记录一笔带日期的余额调整，不进入收支统计，也不会覆盖期初余额。")
        SettingRow("calendar", "对账日期", date) { pick = true }
        OutlinedTextField(actual, { actual = it }, label = { Text("实际余额（可为负数）") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(reason, { reason = it.take(1000) }, label = { Text("调整原因") }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(16.dp)); Hint("账面余额 ¥${Money.format(recorded)}")
        Text("调整差额 ${delta?.let { "¥${Money.format(it)}" } ?: "—"}", fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 12.dp))
        Button(onClick = { confirm = true }, enabled = delta != null && delta != 0L && reason.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("预览并确认") }
    }
    if (pick) DateWheel("对账日期", date, onDismiss = { pick = false }) { date = it; pick = false }
    if (confirm && delta != null) ConfirmDialog("确认余额调整？", "${a.name} 在 $date 的账面余额将调整 ¥${Money.format(delta)}。收支统计不变。", "保存调整", { confirm = false }) {
        vm.action("已完成对账", done = onDismiss) { vm.repository.save(Entry(type = "adjustment", amountMinor = delta, date = date, accountId = a.id, countInStats = false, note = reason.trim())) }
    }
}
