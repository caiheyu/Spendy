package com.github.caiheyu.spendy.ui

import android.net.Uri
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.caiheyu.spendy.data.IconImageImporter
import com.github.caiheyu.spendy.domain.*
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun rememberIconImporter(vm: SpendyViewModel, onImported: (LibraryIcon) -> Unit = {}): ManagedActivityResultLauncher<String, Uri?> {
    val context = LocalContext.current
    val imported by rememberUpdatedState(onImported)
    return rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) vm.action {
            val icon = IconImageImporter.read(context, uri)
            vm.preferences.change { it.copy(iconLibrary = it.iconLibrary.add(icon)) }
            withContext(Dispatchers.Main) { imported(icon) }
        }
    }
}

private fun iconUsage(s: LedgerState): Map<String, Int> =
    (s.accounts.map { it.icon } + s.categories.map { it.icon } + s.tags.map { it.icon }).groupingBy { it }.eachCount()

private fun scopeLabel(library: IconLibrary, icon: LibraryIcon): String {
    val scopes = library.scopesFor(icon)
    return when (scopes.size) {
        0 -> "均不可见"
        IconScopes.size -> "全部位置"
        else -> IconScopes.filter { it.first in scopes }.joinToString(" · ") { it.second }
    }
}

private fun matchingIcons(icons: List<LibraryIcon>, usage: Map<String, Int>, query: String, filter: String,
    selected: String? = null): List<LibraryIcon> = icons.filter { icon ->
    (query.isBlank() || icon.name.contains(query.trim(), ignoreCase = true)) && when (filter) {
        "builtIn" -> !icon.value.startsWith("data:")
        "custom" -> icon.value.startsWith("data:")
        "used" -> (usage[icon.value] ?: 0) > 0
        else -> true
    }
}.sortedWith(compareByDescending<LibraryIcon> { it.value == selected }.thenByDescending { usage[it.value] ?: 0 })

@Composable
fun IconPicker(
    icons: List<LibraryIcon>, ledger: LedgerState, selected: String, tint: Color,
    onSelected: (LibraryIcon) -> Unit, onImport: () -> Unit, importLabel: String = "导入图片并加入图标库",
) {
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf("all") }
    var expanded by rememberSaveable { mutableStateOf(false) }
    val usage = remember(ledger) { iconUsage(ledger) }
    val filtered = matchingIcons(icons, usage, if (expanded) query else "", if (expanded) filter else "all", selected)
    val visible = if (expanded) filtered else filtered.take(12)
    if (expanded) {
        OutlinedTextField(query, { query = it }, modifier = Modifier.fillMaxWidth().testTag("icon-picker-search"), singleLine = true,
            label = { Text("搜索图标") }, placeholder = { Text("例如：钱包、餐饮") })
        Spacer(Modifier.height(8.dp))
        ChoiceChips(listOf("all" to "全部", "builtIn" to "内置", "custom" to "自定义", "used" to "已使用"), filter) { filter = it }
    }
    Spacer(Modifier.height(8.dp))
    if (visible.isEmpty()) Hint("没有符合条件的图标")
    else FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        visible.forEach { choice ->
            Box(Modifier.size(42.dp).clip(CardShape)
                .background(if (selected == choice.value) tint.copy(alpha = .18f) else MaterialTheme.colorScheme.background)
                .testTag("icon-option-${choice.key}").clickable { onSelected(choice) }, contentAlignment = Alignment.Center) {
                LibrarySIcon(choice.value, choice.name, Modifier.size(22.dp), tint)
            }
        }
    }
    if (icons.isNotEmpty()) TextButton(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth().testTag("icon-picker-more")) {
        Text(if (expanded) "收起图标" else "更多图标（${icons.size}）")
    }
    TextButton(onClick = onImport, modifier = Modifier.fillMaxWidth()) {
        SIcon("upload", modifier = Modifier.size(15.dp)); Text("  $importLabel")
    }
}

@Composable
fun IconLibraryScreen(vm: SpendyViewModel, s: LedgerState, onBack: () -> Unit) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val icons = remember(settings.iconLibrary, s) { settings.iconLibrary.available(s) }
    var deleting by remember { mutableStateOf<LibraryIcon?>(null) }
    var editing by remember { mutableStateOf<LibraryIcon?>(null) }
    var replacing by remember { mutableStateOf<LibraryIcon?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf("all") }
    var scopeFilter by rememberSaveable { mutableStateOf("all") }
    var expanded by rememberSaveable { mutableStateOf(false) }
    val importer = rememberIconImporter(vm) { filter = "custom"; scopeFilter = "all"; query = it.name; expanded = false }
    val usage = remember(s) { iconUsage(s) }
    val inScope = icons.filter { icon ->
        val scopes = settings.iconLibrary.scopesFor(icon)
        when (scopeFilter) {
            "all" -> true
            "none" -> scopes.isEmpty()
            else -> scopeFilter in scopes
        }
    }
    val filtered = matchingIcons(inScope, usage, query, filter)
    val visible = if (expanded) filtered else filtered.take(12)
    val shownCustom = visible.filter { it.value.startsWith("data:") }
    val shownBuiltIns = visible.filterNot { it.value.startsWith("data:") }
    LazyColumn(Modifier.fillMaxSize().testTag("icon-library"), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { PageHeader("图标管理", onBack, action = {
            IconButton(onClick = { importer.launch("image/*") }, enabled = !busy) { SIcon("plus", "添加图标") }
        }) }
        item { Hint("分类、标签和账户共用此图标库，点击图标可设置它在哪些位置可见。") }
        item {
            OutlinedTextField(query, { query = it; expanded = false }, modifier = Modifier.fillMaxWidth().testTag("icon-library-search"), singleLine = true,
                label = { Text("搜索图标") }, placeholder = { Text("例如：钱包、餐饮") })
            Spacer(Modifier.height(8.dp))
            ChoiceChips(listOf("all" to "全部", "builtIn" to "内置", "custom" to "自定义", "used" to "已使用"), filter) { filter = it; expanded = false }
            Spacer(Modifier.height(8.dp))
            Column(Modifier.testTag("icon-library-scope-filter")) {
                Hint("可见范围")
                ChoiceChips(listOf("all" to "全部范围") + IconScopes + ("none" to "均不可见"), scopeFilter) {
                    scopeFilter = it; expanded = false
                }
            }
        }
        item {
            OutlinedButton(onClick = { importer.launch("image/*") }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                SIcon("upload", modifier = Modifier.size(18.dp)); Text("  导入图片图标")
            }
            Hint("PNG、JPG、WebP 等图片 · 最大 5 MB · 自动缩放至 96×96")
        }
        if (visible.isEmpty()) item { EmptyState("没有符合条件的图标", "试试其他名称或筛选条件，也可以导入喜欢的图片", "palette") }
        if (shownCustom.isNotEmpty()) item { SectionTitle("自定义图标") }
        items(shownCustom, key = { it.key }) { icon ->
            SpendCard(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconCircle(icon.value, MaterialTheme.colorScheme.primary, libraryIcon = true)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f).clickable(enabled = !busy) { editing = icon }.padding(vertical = 8.dp)) {
                        Text(icon.name, fontSize = 14.sp)
                        Hint("可见范围：${scopeLabel(settings.iconLibrary, icon)}")
                    }
                    IconButton(onClick = { deleting = icon }, enabled = !busy,
                        modifier = Modifier.testTag("remove-icon-${icon.key}")) { SIcon("trash", "移除图标：${icon.name}") }
                }
            }
        }
        if (shownBuiltIns.isNotEmpty()) item { SectionTitle("内置图标") }
        items(shownBuiltIns.chunked(4), key = { "built-in-${it.first().key}" }) { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { icon ->
                    Column(Modifier.weight(1f).clip(CardShape).background(MaterialTheme.colorScheme.surface)
                        .testTag("library-icon-${icon.key}").clickable(enabled = !busy) { editing = icon }
                        .padding(vertical = 14.dp), horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        LibrarySIcon(icon.value, "设置图标：${icon.name}", Modifier.size(25.dp), MaterialTheme.colorScheme.primary)
                        Text(icon.name, fontSize = 11.sp)
                        Text(scopeLabel(settings.iconLibrary, icon), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        if (filtered.size > visible.size) item {
            TextButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) { Text("显示全部 ${filtered.size} 个图标") }
        } else if (expanded && filtered.size > 12) item {
            TextButton(onClick = { expanded = false }, modifier = Modifier.fillMaxWidth()) { Text("收起图标") }
        }
        if (settings.iconLibrary.hiddenIconIds.any { id -> BuiltInIcons.any { it.key == id } }) item {
            TextButton(onClick = { vm.action { vm.preferences.change { it.copy(iconLibrary = it.iconLibrary.restoreBuiltIns()) } } },
                enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("恢复全部内置图标", fontSize = 12.sp) }
        }
    }
    deleting?.let { icon ->
        val used = usage[icon.value] ?: 0
        val detail = if (used > 0) "从选择列表移除后，已有 $used 个使用此图标的项目会继续显示原图标。"
            else if (icon.value.startsWith("data:")) "之后可通过导入图片重新添加。" else "之后可通过「恢复全部内置图标」重新添加。"
        ConfirmDialog("移除「${icon.name}」？", detail, "移除", { deleting = null }) {
            vm.action(done = { deleting = null }) { vm.preferences.change { it.copy(iconLibrary = it.iconLibrary.remove(icon)) } }
        }
    }
    editing?.let { icon ->
        IconSettingsDialog(vm, settings.iconLibrary, icon, busy, onDismiss = { editing = null },
            onReplace = { editing = null; replacing = icon },
            onRemove = { editing = null; deleting = icon })
    }
    replacing?.let { icon ->
        IconReplacementSheet(vm, s, settings.iconLibrary, icon, onDismiss = { replacing = null }) {
            replacing = null; filter = "all"; query = icon.name; expanded = false
        }
    }
}

@Composable
private fun IconSettingsDialog(vm: SpendyViewModel, library: IconLibrary, icon: LibraryIcon, busy: Boolean,
    onDismiss: () -> Unit, onReplace: () -> Unit, onRemove: () -> Unit) {
    var name by remember(icon.key) { mutableStateOf(icon.name) }
    var scopes by remember(icon.key) { mutableStateOf(library.scopesFor(icon)) }
    val custom = icon.value.startsWith("data:")
    AlertDialog(onDismissRequest = onDismiss, title = { Text("图标设置") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconCircle(icon.value, MaterialTheme.colorScheme.primary, libraryIcon = true)
                Spacer(Modifier.width(12.dp))
                Text(icon.name)
            }
            if (custom) {
                Spacer(Modifier.height(14.dp))
                OutlinedTextField(name, { name = it.take(60) }, singleLine = true, label = { Text("名称") })
            }
            Spacer(Modifier.height(14.dp))
            Text("可见范围（可多选）")
            IconScopes.forEach { (scope, label) ->
                Row(Modifier.fillMaxWidth().testTag("icon-scope-$scope")
                    .toggleable(value = scope in scopes, role = Role.Checkbox) {
                        scopes = if (it) scopes + scope else scopes - scope
                    }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = scope in scopes, onCheckedChange = null)
                    Text(label)
                }
            }
            Hint("仅在勾选位置的图标选择列表中显示，已有项目的图标会保留。")
            OutlinedButton(onClick = onReplace, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("替换图标") }
            TextButton(onClick = onRemove, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Text("移除此图标", color = MaterialTheme.colorScheme.error)
            }
        }
    }, confirmButton = {
        TextButton(enabled = name.isNotBlank() && !busy, onClick = {
            vm.action(done = onDismiss) {
                vm.preferences.change {
                    val renamed = if (custom) it.iconLibrary.add(icon.copy(name = name.trim())) else it.iconLibrary
                    it.copy(iconLibrary = renamed.withScopes(icon, scopes))
                }
            }
        }) { Text("保存") }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

@Composable
private fun IconReplacementSheet(vm: SpendyViewModel, s: LedgerState, library: IconLibrary, source: LibraryIcon,
    onDismiss: () -> Unit, onReplaced: () -> Unit) {
    val busy by vm.busy.collectAsStateWithLifecycle()
    var selected by remember { mutableStateOf<LibraryIcon?>(null) }
    var image by remember { mutableStateOf<String?>(null) }
    var confirming by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    // Stage uploads locally. Choosing or cancelling a replacement never adds an icon.
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) vm.action(onError = { error = it }) {
            val imported = IconImageImporter.read(context, uri)
            withContext(Dispatchers.Main) { selected = imported; image = imported.value; error = null }
        }
    }
    val originalImage = library.imageFor(source.value)
    val choices = remember(library, s, source.key) {
        library.available(s).filter { it.key != source.key && library.imageFor(it.value) != originalImage }
    }
    SimpleSheet("替换「${source.name}」", onDismiss) {
        Hint("选择已有图标的图案，或上传新图片。原名称与可见范围会保留。")
        Spacer(Modifier.height(12.dp))
        IconPicker(choices, s, selected?.value.orEmpty(), MaterialTheme.colorScheme.primary, {
            selected = it; image = library.imageFor(it.value); error = null
        }, { importer.launch("image/*") }, importLabel = "选择替换图片")
        if (source.value != originalImage) TextButton(onClick = { selected = source; image = source.value; error = null },
            enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("使用原始图案") }
        image?.let { next ->
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
                IconCircle(source.value, MaterialTheme.colorScheme.primary, 54.dp, libraryIcon = true)
                Text("→")
                IconCircle(next, MaterialTheme.colorScheme.primary, 54.dp)
                Text("替换预览")
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Spacer(Modifier.height(14.dp))
        Button(onClick = { confirming = true }, enabled = image != null && image != originalImage && !busy,
            modifier = Modifier.fillMaxWidth().testTag("preview-icon-replacement")) { Text("预览替换") }
        TextButton(onClick = onDismiss, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("取消") }
    }
    if (confirming) {
        val accounts = s.accounts.count { it.icon == source.value }
        val categories = s.categories.count { it.icon == source.value }
        val tags = s.tags.count { it.icon == source.value }
        AlertDialog(onDismissRequest = { if (!busy) confirming = false }, title = { Text("确认替换图标？") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("将更新 $categories 个分类、$tags 个标签和 $accounts 个账户，共 ${accounts + categories + tags} 个项目（含已归档项目）。")
                Text("历史账单会同步显示新图案，原图标名称和可见范围保留。")
                Hint("只复用所选图案，所选图标自身的设置保持不变。")
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }, confirmButton = {
            TextButton(enabled = !busy, onClick = {
                val replacement = image ?: return@TextButton
                vm.action("图标已统一替换", done = onReplaced, onError = { error = it }) {
                    vm.preferences.change {
                        require(it.iconLibrary.imageFor(source.value) == originalImage) { "图标已变更，请返回重新选择" }
                        it.copy(iconLibrary = it.iconLibrary.replaceImage(source, replacement))
                    }
                }
            }) { Text("确认替换") }
        }, dismissButton = { TextButton(enabled = !busy, onClick = { confirming = false }) { Text("返回选择") } })
    }
}
