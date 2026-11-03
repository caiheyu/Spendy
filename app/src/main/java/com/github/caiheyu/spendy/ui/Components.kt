@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.github.caiheyu.spendy.ui

import android.widget.NumberPicker
import android.widget.LinearLayout
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.compose.ui.viewinterop.AndroidView
import com.github.caiheyu.spendy.domain.*
import com.github.caiheyu.spendy.ui.theme.*
import java.time.*

val CardShape = RoundedCornerShape(24.dp)
val Palette = listOf("#5560E9", "#0E9F8A", "#F0564A", "#F5A623", "#EC5A9E", "#29B6F6", "#7E57C2", "#FF7A59", "#B074E8", "#1FA97A", "#8A8D9F")

@Composable
fun SpendCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier, shape = CardShape, color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(20.dp), content = content)
    }
}
@Composable
fun PageHeader(title: String, back: (() -> Unit)? = null, action: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        if (back != null) { IconButton(onClick = back) { SIcon("chevL", "返回") }; Spacer(Modifier.width(6.dp)) }
        Text(title, fontSize = 27.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        action()
    }
}
@Composable
fun IconCircle(name: String, color: Color, size: Dp = 44.dp, libraryIcon: Boolean = false) {
    Box(Modifier.size(size).clip(RoundedCornerShape(15.dp)).background(color.copy(alpha = .13f)), contentAlignment = Alignment.Center) {
        if (libraryIcon) LibrarySIcon(name, modifier = Modifier.size(size * .51f), tint = color)
        else SIcon(name, modifier = Modifier.size(size * .51f), tint = color)
    }
}
@Composable
fun SectionTitle(title: String, detail: String? = null) {
    Row(Modifier.fillMaxWidth().padding(bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        detail?.let { Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}
@Composable
fun Hint(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, fontSize = 12.sp, lineHeight = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
@Composable
fun EmptyState(title: String, subtitle: String, icon: String = "receipt", action: String? = null, onAction: () -> Unit = {}) {
    Column(Modifier.fillMaxWidth().padding(vertical = 42.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        IconCircle(icon, MaterialTheme.colorScheme.primary, 68.dp)
        Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        Hint(subtitle)
        if (action != null) Button(onClick = onAction, shape = RoundedCornerShape(16.dp)) { Text(action) }
    }
}
@Composable
fun ChoiceChips(options: List<Pair<String, String>>, selected: String?, onSelect: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (key, text) ->
            FilterChip(selected = selected == key, onClick = { onSelect(key) }, label = { Text(text, fontSize = 12.sp) },
                shape = CircleShape, colors = FilterChipDefaults.filterChipColors(
                    containerColor = Color.Transparent, labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    selectedContainerColor = MaterialTheme.colorScheme.onSurface,
                    selectedLabelColor = MaterialTheme.colorScheme.background,
                ), border = FilterChipDefaults.filterChipBorder(enabled = true, selected = selected == key,
                    borderColor = MaterialTheme.colorScheme.outlineVariant, selectedBorderColor = Color.Transparent))
        }
    }
}

/** Entry tabs and chart toggles use the ledger red/green rather than the brand accent. */
@Composable
fun LedgerTypeSelector(options: List<Pair<String, String>>, selected: String, tabs: Boolean = false, onSelect: (String) -> Unit) {
    val modifier = if (tabs) Modifier else Modifier.clip(RoundedCornerShape(12.dp)).border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(if (tabs) 24.dp else 0.dp), verticalAlignment = Alignment.CenterVertically) {
        options.forEach { (key, label) ->
            val active = key == selected
            val color = when (key) { "expense" -> Expense; "income" -> Income; else -> MaterialTheme.colorScheme.primary }
            val ink = if (active) color else MaterialTheme.colorScheme.onSurfaceVariant
            Column(Modifier.clip(if (tabs) RoundedCornerShape(0.dp) else RoundedCornerShape(10.dp))
                .background(if (active && !tabs) color.copy(alpha = .08f) else Color.Transparent)
                .selectable(selected = active, onClick = { onSelect(key) }, role = androidx.compose.ui.semantics.Role.Tab)) {
                Text(label, modifier = Modifier.padding(horizontal = if (tabs) 18.dp else 17.dp, vertical = 10.dp),
                    fontSize = if (tabs) 14.sp else 12.sp, fontWeight = FontWeight.SemiBold, color = ink)
                if (tabs) HorizontalDivider(color = if (active) color else Color.Transparent, thickness = 2.dp, modifier = Modifier.width(64.dp))
            }
        }
    }
}

@Composable
fun SpendySwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Switch(checked, onCheckedChange, colors = SwitchDefaults.colors(
        checkedThumbColor = Color.White, checkedTrackColor = MaterialTheme.colorScheme.primary,
        checkedBorderColor = Color.Transparent, uncheckedThumbColor = Color.White,
        uncheckedTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = .12f), uncheckedBorderColor = Color.Transparent,
    ))
}

@Composable
fun TagChoiceChip(tag: Tag, selected: Boolean, onClick: () -> Unit) {
    val color = colorOf(tag.color)
    FilterChip(selected, onClick, label = { Text(tag.name, fontSize = 12.sp) },
        leadingIcon = { LibrarySIcon(tag.icon, modifier = Modifier.size(15.dp), tint = color) },
        shape = CircleShape, colors = FilterChipDefaults.filterChipColors(
            containerColor = Color.Transparent, labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
            selectedContainerColor = color.copy(alpha = .1f), selectedLabelColor = color,
        ), border = FilterChipDefaults.filterChipBorder(enabled = true, selected = selected,
            borderColor = MaterialTheme.colorScheme.outlineVariant, selectedBorderColor = color.copy(alpha = .3f)))
}
@Composable
fun SettingRow(icon: String, title: String, subtitle: String? = null, color: Color = MaterialTheme.colorScheme.primary,
    trailing: @Composable (() -> Unit)? = null, libraryIcon: Boolean = false, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
        IconCircle(icon, color, 38.dp, libraryIcon); Spacer(Modifier.width(13.dp))
        Column(Modifier.weight(1f)) { Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium); if (subtitle != null) Hint(subtitle) }
        if (trailing != null) trailing() else SIcon("chevR", modifier = Modifier.size(17.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
@Composable
fun ConfirmDialog(title: String, text: String, confirm: String = "确认", onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = { Text(text, lineHeight = 23.sp) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirm, color = Expense) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}
@Composable
fun SimpleSheet(title: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 22.dp, end = 22.dp, bottom = 28.dp)) {
            Text(title, fontSize = 21.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 18.dp))
            content()
        }
    }
}
@Composable
fun DateWheel(title: String, value: String, future: Boolean = false, onDismiss: () -> Unit, onSelect: (String) -> Unit) {
    var date by remember(value) { mutableStateOf(runCatching { LocalDate.parse(value) }.getOrDefault(LocalDate.now())) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = {
        Column {
            Text("${date.year} 年 ${date.monthValue} 月 ${date.dayOfMonth} 日", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            AndroidView(modifier = Modifier.fillMaxWidth().height(180.dp), factory = { context ->
                val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
                val year = NumberPicker(context); val month = NumberPicker(context); val day = NumberPicker(context)
                fun updateDay() { day.maxValue = YearMonth.of(year.value, month.value).lengthOfMonth(); date = LocalDate.of(year.value, month.value, day.value.coerceAtMost(day.maxValue)) }
                year.minValue = 1900; year.maxValue = LocalDate.now().year + 1; year.value = date.year
                month.minValue = 1; month.maxValue = 12; month.value = date.monthValue
                day.minValue = 1; day.maxValue = date.lengthOfMonth(); day.value = date.dayOfMonth
                listOf(year, month, day).forEach { picker ->
                    picker.wrapSelectorWheel = false
                    picker.descendantFocusability = NumberPicker.FOCUS_BLOCK_DESCENDANTS
                    row.addView(picker, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
                    picker.setOnValueChangedListener { _, _, _ -> updateDay() }
                }
                row
            })
            if (!future && date > LocalDate.now()) Text("流水日期不能晚于今天", color = Expense, fontSize = 12.sp)
        }
    }, confirmButton = { TextButton(enabled = future || date <= LocalDate.now(), onClick = { onSelect(date.toString()) }) { Text("确定") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}
@Composable
fun EntryRow(s: LedgerState, entry: Entry, onClick: () -> Unit, selected: Boolean = false, selectable: Boolean = false) {
    val c = s.category(entry.categoryId ?: entry.feeCategoryId)
    val title = when {
        entry.kind == "refund" -> "退款 · ${c?.name.orEmpty()}"
        entry.kind == "reimbursement" -> "报销回收 · ${c?.name.orEmpty()}"
        entry.type == "transfer" -> "账户转账"
        entry.type == "adjustment" -> "余额调整"
        else -> s.subcategory(entry.subcategoryId)?.name ?: c?.name ?: "账单"
    }
    val positive = entry.type == "income" || entry.kind != "normal" || entry.type == "adjustment" && entry.amountMinor > 0
    val amountColor = when { entry.type == "transfer" || entry.type == "adjustment" -> MaterialTheme.colorScheme.onSurface; positive -> Income; else -> Expense }
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 15.dp), verticalAlignment = Alignment.CenterVertically) {
        if (selectable) { Checkbox(selected, onCheckedChange = { onClick() }); Spacer(Modifier.width(3.dp)) }
        IconCircle(if (entry.type == "transfer") "refresh" else if (entry.type == "adjustment") "wallet" else c?.icon ?: "receipt", c?.color?.let(::colorOf) ?: MaterialTheme.colorScheme.primary,
            libraryIcon = c != null && entry.type !in listOf("transfer", "adjustment"))
        Spacer(Modifier.width(13.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(listOfNotNull(s.account(entry.accountId)?.name, if (entry.type == "transfer") "→ ${s.account(entry.toAccountId)?.name.orEmpty()}" else null,
                entry.note.takeIf { it.isNotBlank() }, if (!entry.countInStats) "不计统计" else null).joinToString(" · "), fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (entry.tagIds.isNotEmpty() || entry.reimbursable) FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                s.tags.filter { it.id in entry.tagIds }.take(3).forEach { t ->
                    Text(t.name, color = colorOf(t.color), fontSize = 10.sp, modifier = Modifier.clip(RoundedCornerShape(5.dp)).background(colorOf(t.color).copy(alpha = .1f)).padding(horizontal = 5.dp, vertical = 1.dp))
                }
                if (entry.reimbursable) Text(if (Ledger.pending(s, entry) > 0) "待报销" else "已完成", color = Income, fontSize = 10.sp)
            }
        }
        Spacer(Modifier.width(8.dp))
        Text((if (entry.type == "transfer") "" else if (positive) "+" else if (entry.type == "adjustment") "−" else "−") + Money.format(kotlin.math.abs(entry.amountMinor)),
            color = amountColor, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}
