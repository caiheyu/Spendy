@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.github.caiheyu.spendy.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.caiheyu.spendy.domain.*
import com.github.caiheyu.spendy.ui.theme.*
import kotlin.math.roundToInt

@Composable
fun ManagementScreen(vm: SpendyViewModel, s: LedgerState, kind: String, onBack: () -> Unit) {
    var editing by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<String?>(null) }
    var subParent by remember { mutableStateOf<String?>(null) }
    var type by remember { mutableStateOf("expense") }
    var organize by remember { mutableStateOf(false) }
    val title = when (kind) { "account" -> "账户管理"; "tag" -> "标签管理"; else -> "分类管理" }
    val entities = when (kind) {
        "account" -> s.accounts.map { ManageItem(it.id, it.name, it.icon, it.color, it.archived, it.sortOrder) }
        "tag" -> s.tags.map { ManageItem(it.id, it.name, it.icon, it.color, it.archived, it.sortOrder) }
        else -> s.categories.filter { it.type == type }.map { ManageItem(it.id, it.name, it.icon, it.color, it.archived, it.sortOrder) }
    }.sortedBy { it.order }
    val rowHeight = with(LocalDensity.current) { 80.dp.toPx() }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { PageHeader(title, onBack, action = { IconButton(onClick = { editing = "new" }) { SIcon("plus", "新建") } }) }
        if (kind == "category") item { ChoiceChips(listOf("expense" to "支出分类", "income" to "收入分类"), type) { type = it } }
        item { Hint(if (kind == "account") "归档保留历史余额。有流水的账户不能删除。" else "长按后拖动排序；归档只隐藏新账单选项，历史引用保留。") }
        items(entities, key = { it.id }) { item ->
            var offset by remember { mutableFloatStateOf(0f) }
            Surface(shape = CardShape, color = MaterialTheme.colorScheme.surface, modifier = Modifier.graphicsLayer { translationY = offset }
                .pointerInput(item.id, entities) {
                    detectDragGesturesAfterLongPress(onDragEnd = {
                        val steps = (offset / rowHeight).roundToInt(); offset = 0f
                        if (steps != 0) vm.action { vm.repository.reorder(kind, item.id, steps) }
                    }, onDragCancel = { offset = 0f }, onDrag = { change, amount -> change.consume(); offset += amount.y })
                }) {
                Column(Modifier.padding(horizontal = 15.dp, vertical = 4.dp)) {
                    SettingRow(item.icon, item.name + if (item.archived) " · 已归档" else "", color = colorOf(item.color), trailing = { SIcon("pencil", "编辑", Modifier.size(16.dp)) }, libraryIcon = true) { editing = item.id }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        if (kind == "category") TextButton(onClick = { subParent = item.id }) { Text("子分类", fontSize = 11.sp) }
                        TextButton(onClick = { vm.action("已保存") {
                            when (kind) {
                                "account" -> vm.repository.saveAccount(s.account(item.id)!!.copy(archived = !item.archived))
                                "tag" -> vm.repository.saveTag(s.tags.first { it.id == item.id }.copy(archived = !item.archived))
                                else -> vm.repository.saveCategory(s.category(item.id)!!.copy(archived = !item.archived))
                            }
                        } }) { Text(if (item.archived) "启用" else "归档", fontSize = 11.sp) }
                        TextButton(onClick = { deleting = item.id }) { Text("删除", color = Expense, fontSize = 11.sp) }
                    }
                }
            }
        }
        item { OutlinedButton(onClick = { editing = "new" }, modifier = Modifier.fillMaxWidth()) { SIcon("plus", modifier = Modifier.size(17.dp)); Text("  新建${title.removeSuffix("管理")}") } }
        if (kind != "account") item { TextButton(onClick = { organize = true }, modifier = Modifier.fillMaxWidth()) { Text("整理常用分类与标签") } }
    }
    editing?.let { id -> EntityEditor(vm, s, kind, id.takeIf { it != "new" }, type, { editing = null }) }
    deleting?.let { id ->
        val impact = when (kind) {
            "tag" -> "将从 ${s.transactions.count { id in it.tagIds }} 笔账单摘除此标签，账单本身保留。"
            "account" -> "账户将被移除；有流水的账户禁止删除，可改用归档。"
            else -> "分类及其 ${s.subcategories.count { it.categoryId == id }} 个子分类将被删除；有流水的分类禁止删除。"
        }
        ConfirmDialog("删除${entities.find { it.id == id }?.name.orEmpty()}？", impact, "删除", { deleting = null }) { vm.action("已删除", done = { deleting = null }) { vm.repository.deleteEntity(kind, id) } }
    }
    if (organize) ConfirmDialog("整理常用项目？", "补充缺失的内置分类、子分类和标签。已有自定义名称、排序与历史流水保留。", "整理", { organize = false }) { vm.action("整理完成", done = { organize = false }) { vm.repository.organizeDefaults() } }
    subParent?.let { SubcategorySheet(vm, s, it, { subParent = null }) }
}

private data class ManageItem(val id: String, val name: String, val icon: String, val color: String, val archived: Boolean, val order: Int)

@Composable
private fun EntityEditor(vm: SpendyViewModel, s: LedgerState, kind: String, id: String?, type: String, onDismiss: () -> Unit) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val icons = remember(settings.iconLibrary, s, kind) { settings.iconLibrary.available(s, kind) }
    val account = s.account(id); val category = s.category(id); val tag = s.tags.find { it.id == id }
    var name by remember { mutableStateOf(account?.name ?: category?.name ?: tag?.name ?: "") }
    var icon by remember { mutableStateOf(account?.icon ?: category?.icon ?: tag?.icon ?:
        icons.firstOrNull { it.value == if (kind == "account") "wallet" else "tag" }?.value ?: icons.firstOrNull()?.value.orEmpty()) }
    var color by remember { mutableStateOf(account?.color ?: category?.color ?: tag?.color ?: "#5560E9") }
    var opening by remember { mutableStateOf(Money.decimal(account?.openingBalanceMinor ?: 0)) }
    var openedOn by remember { mutableStateOf(account?.openedOn ?: java.time.LocalDate.now().toString()) }
    var datePicker by remember { mutableStateOf(false) }; var confirm by remember { mutableStateOf(false) }
    val importer = rememberIconImporter(vm) { icon = it.value }
    fun save() { vm.action("已保存", done = onDismiss) {
        require(name.trim().isNotEmpty()) { "请输入名称" }
        require(icon.isNotBlank()) { "请先导入或选择一个图标" }
        when (kind) {
            "account" -> vm.repository.saveAccount((account ?: Account(name = name.trim(), sortOrder = s.accounts.size)).copy(name = name.trim(), icon = icon, color = color, openingBalanceMinor = Money.parse(opening), openedOn = openedOn))
            "tag" -> vm.repository.saveTag((tag ?: Tag(name = name.trim(), sortOrder = s.tags.size)).copy(name = name.trim(), icon = icon, color = color))
            else -> vm.repository.saveCategory((category ?: Category(name = name.trim(), type = type, sortOrder = s.categories.size)).copy(name = name.trim(), icon = icon, color = color))
        }
    } }
    SimpleSheet(if (id == null) "新建项目" else "编辑项目", onDismiss) {
        Row(Modifier.fillMaxWidth().padding(bottom = 18.dp), horizontalArrangement = Arrangement.Center) { IconCircle(icon, colorOf(color), 60.dp, libraryIcon = true) }
        OutlinedTextField(name, { name = it.take(60) }, label = { Text("名称") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        Spacer(Modifier.height(16.dp)); Hint("图标")
        IconPicker(icons, s, icon, colorOf(color), { icon = it.value }, { importer.launch("image/*") })
        Spacer(Modifier.height(14.dp)); Hint("颜色")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Palette.forEach { value -> Box(Modifier.size(28.dp).clip(CircleShape).background(colorOf(value)).clickable { color = value }, contentAlignment = Alignment.Center) { if (color == value) SIcon("check", modifier = Modifier.size(17.dp), tint = Color.White) } }
        }
        if (kind == "account") {
            Spacer(Modifier.height(18.dp))
            OutlinedTextField(opening, { opening = it }, label = { Text("期初余额（可为负数）") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            SettingRow("calendar", "期初生效日期", openedOn) { datePicker = true }
            Hint("期初余额为 0 时可补记更早的流水；非零时流水不能早于期初生效日。修改期初会重新计算历史余额，负余额可用于信用卡。")
        }
        Spacer(Modifier.height(20.dp))
        Button(onClick = { if (account != null && (opening != Money.decimal(account.openingBalanceMinor) || openedOn != account.openedOn)) confirm = true else save() }, modifier = Modifier.fillMaxWidth().height(49.dp), enabled = name.isNotBlank() && icon.isNotBlank(), shape = CardShape) { Text("保存") }
    }
    if (datePicker) DateWheel("期初生效日期", openedOn, onDismiss = { datePicker = false }) { openedOn = it; datePicker = false }
    if (confirm) ConfirmDialog("重新计算历史余额？", "修改 ${account?.name} 的期初余额或生效日期后，历史余额曲线会重新计算。不会生成收入或支出。", "确认保存", { confirm = false }, ::save)
}

@Composable
private fun SubcategorySheet(vm: SpendyViewModel, s: LedgerState, parent: String, onDismiss: () -> Unit) {
    var editing by remember { mutableStateOf<Subcategory?>(null) }; var name by remember { mutableStateOf("") }
    var deleting by remember { mutableStateOf<Subcategory?>(null) }; var second by remember { mutableStateOf(false) }
    SimpleSheet("${s.category(parent)?.name} · 子分类", onDismiss) {
        s.subcategories.filter { it.categoryId == parent }.sortedBy { it.sortOrder }.forEach { sub ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { editing = sub; name = sub.name }, modifier = Modifier.weight(1f)) { Text(sub.name) }
                IconButton(onClick = { deleting = sub; second = false }) { SIcon("trash", "删除${sub.name}", Modifier.size(18.dp), Expense) }
            }
        }
        OutlinedTextField(name, { name = it.take(60) }, label = { Text(if (editing == null) "新子分类名称" else "重命名子分类") }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(12.dp))
        Button(enabled = name.isNotBlank(), onClick = { vm.action("子分类已保存", done = { name = ""; editing = null }) { vm.repository.saveSubcategory((editing ?: Subcategory(categoryId = parent, name = name.trim(), sortOrder = s.subcategories.count { it.categoryId == parent })).copy(name = name.trim())) } }, modifier = Modifier.fillMaxWidth()) { Text(if (editing == null) "添加子分类" else "保存名称") }
    }
    deleting?.let { sub -> ConfirmDialog(if (second) "再次确认删除" else "删除 ${sub.name}？", "${s.transactions.count { it.subcategoryId == sub.id }} 笔历史流水将归为「不细分」，主分类保持不变。", if (second) "确认删除" else "继续", { deleting = null }) {
        if (!second) second = true else vm.action("已删除子分类", done = { deleting = null }) { vm.repository.deleteEntity("subcategory", sub.id) }
    } }
}
