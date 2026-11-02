@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.github.caiheyu.spendy.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.caiheyu.spendy.data.*
import com.github.caiheyu.spendy.domain.*
import com.github.caiheyu.spendy.ui.theme.*
import java.time.*
import java.time.format.DateTimeFormatter

@Composable
fun DataScreen(vm: SpendyViewModel, s: LedgerState, onBack: () -> Unit) {
    val context = LocalContext.current
    val settings by vm.settings.collectAsStateWithLifecycle()
    val canRollback by vm.canUndoRestore.collectAsStateWithLifecycle()
    var backupDialog by remember { mutableStateOf(false) }; var encrypt by remember { mutableStateOf(true) }
    var password by remember { mutableStateOf("") }; var passwordAgain by remember { mutableStateOf("") }
    var restoreBytes by remember { mutableStateOf<ByteArray?>(null) }; var restorePassword by remember { mutableStateOf("") }
    var restorePreview by remember { mutableStateOf<Backup?>(null) }; var restoreConfirm by remember { mutableStateOf(false) }
    var rollback by remember { mutableStateOf(false) }
    var start by remember { mutableStateOf(YearMonth.now().atDay(1).toString()) }; var end by remember { mutableStateOf(LocalDate.now().toString()) }
    var rangeSheet by remember { mutableStateOf(false) }
    var table by remember { mutableStateOf<CsvTable?>(null) }; var mapping by remember { mutableStateOf<Map<String, Int?>>(emptyMap()) }
    var defaultAccount by remember { mutableStateOf(s.accounts.firstOrNull { !it.archived }?.id) }
    var csvPreview by remember { mutableStateOf<ImportPreview?>(null) }; var selected by remember { mutableStateOf(emptySet<Int>()) }
    var importConfirm by remember { mutableStateOf(false) }
    val count = s.transactions.count { it.date in start..end }
    fun writeFile(uri: Uri?) {
        val file = vm.pendingFile; vm.pendingFile = null
        if (uri == null) { vm.message("已取消文件保存"); return }
        if (file == null) { vm.message("导出内容已失效，请重新生成"); return }
        vm.action("文件已成功写入所选位置") {
            val stream = context.contentResolver.openOutputStream(uri, "wt") ?: error("无法写入所选位置")
            stream.use { it.write(file.bytes); it.flush() }
            if (file.backupRevision != null) vm.preferences.change { it.copy(lastBackupAt = System.currentTimeMillis(), backedUpRevision = file.backupRevision, backedUpEntryCount = file.entryCount, backedUpPreferencesHash = file.preferencesHash) }
        }
    }
    val createCsv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv"), ::writeFile)
    val createBackup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream"), ::writeFile)
    val importCsv = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.action {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readLimited(20 * 1024 * 1024) } ?: error("无法读取文件")
        val charset = Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
        val parsed = Csv.parse(charset.decode(java.nio.ByteBuffer.wrap(bytes)).toString())
        table = parsed; mapping = Csv.mapping(parsed); csvPreview = null
    } }
    val openBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.action {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readLimited(BackupCodec.MAX_BYTES) } ?: error("无法读取文件")
        if (BackupCodec.isEncrypted(bytes)) { restoreBytes = bytes; restorePassword = "" }
        else restorePreview = BackupCodec.decode(bytes)
    } }
    LazyColumn(Modifier.fillMaxSize().testTag("data-list"), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { PageHeader("数据管理", onBack) }
        item { SpendCard {
            SectionTitle("备份状态")
            val text = if (settings.lastBackupAt == 0L) "尚未生成外部备份" else "最近备份 ${Instant.ofEpochMilli(settings.lastBackupAt).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))}"
            Text(text, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Spacer(Modifier.height(9.dp)); Hint(if (settings.isBackupCurrent(s.meta.revision)) "账本与偏好均与最近备份一致" else "账本或偏好有未备份变更 · 此后新增 ${s.transactions.count { it.createdAt > settings.lastBackupAt }} 笔")
            Hint("仅在文件输出流成功写入后更新备份状态。请将文件另外保存到可靠介质。")
        } }
        item { SpendCard {
            SectionTitle("完整备份与恢复")
            SettingRow("download", "导出 .spendy 备份", "默认使用密码加密 · 包含全部账目与偏好") { backupDialog = true; password = ""; passwordAgain = ""; encrypt = true }
            SettingRow("upload", "从备份恢复", "先校验和预览，再确认覆盖本机数据") { openBackup.launch(arrayOf("*/*")) }
            if (canRollback) SettingRow("refresh", "撤回上次恢复 / 重置", "使用覆盖前的本机快照") { rollback = true }
            Hint("支持此 Android 版本生成的 schema v5 完整备份。网页原型的设置格式不同，请勿用 CSV 迁移特殊流水。")
        } }
        item { SpendCard {
            SectionTitle("导出标准 CSV", "$count 笔")
            SettingRow("calendar", "导出日期范围", rangeLabel(start, end)) { rangeSheet = true }
            Button(onClick = { vm.action(done = { createCsv.launch(vm.pendingFile!!.fileName) }) {
                vm.pendingFile = ExportDocument(Csv.export(s, start, end).toByteArray(Charsets.UTF_8), "Spendy_${start}_$end.csv")
            } }, modifier = Modifier.fillMaxWidth()) { Text("导出 CSV · $count 笔") }
            Spacer(Modifier.height(10.dp)); Hint("CSV 为明文，Excel 可打开；特殊流水仅供查阅，完整换机请使用加密备份。")
        } }
        item { SpendCard {
            SectionTitle("导入标准 CSV")
            SettingRow("upload", "选择 CSV 文件", "列映射、错误与重复预览，提交后可撤销") { importCsv.launch(arrayOf("text/*", "application/csv", "application/vnd.ms-excel", "application/octet-stream")) }
            TextButton(onClick = { vm.action(done = { createCsv.launch(vm.pendingFile!!.fileName) }) { vm.pendingFile = ExportDocument(Csv.template().toByteArray(Charsets.UTF_8), "Spendy_导入模板.csv") } }) { Text("下载 CSV 模板") }
            Hint("仅支持普通正数收入 / 支出，UTF-8 编码。账户需要预先建立；分类与标签可自动新建。已导入源 ID 禁止重复，疑似重复默认不选。")
        } }
    }
    if (rangeSheet) RangeSheet(start, end, { rangeSheet = false }) { a, b -> start = a; end = b; rangeSheet = false }
    if (backupDialog) AlertDialog(onDismissRequest = { backupDialog = false; password = ""; passwordAgain = "" }, title = { Text("导出完整备份") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(encrypt, { encrypt = it }); Text("密码加密（推荐）", fontSize = 14.sp) }
            if (encrypt) {
                OutlinedTextField(password, { password = it }, label = { Text("密码 · 至少 8 个字符") }, visualTransformation = PasswordVisualTransformation(), singleLine = true)
                OutlinedTextField(passwordAgain, { passwordAgain = it }, label = { Text("再次输入密码") }, visualTransformation = PasswordVisualTransformation(), singleLine = true)
                Text("密码遗忘无法找回，请妥善保存。密码不会写入本机存储。", color = Expense, fontSize = 12.sp)
            } else Text("明文备份包含全部账目，请自行保管。", color = Expense, fontSize = 12.sp)
        }
    }, confirmButton = { TextButton(enabled = !encrypt || password.length >= 8 && password == passwordAgain, onClick = {
        val secret = if (encrypt) password.toCharArray() else null
        password = ""; passwordAgain = ""; backupDialog = false
        vm.action(done = { createBackup.launch(vm.pendingFile!!.fileName) }) {
            try { val backup = vm.repository.export(); val bytes = BackupCodec.encode(backup, secret)
                vm.pendingFile = ExportDocument(bytes, "Spendy_${LocalDate.now()}.spendy", backup.data.meta.revision, backup.data.transactions.size, backup.settings.preferencesHash())
            } finally { secret?.fill('\u0000') }
        }
    }) { Text("选择保存位置") } }, dismissButton = { TextButton(onClick = { backupDialog = false; password = ""; passwordAgain = "" }) { Text("取消") } })
    if (restoreBytes != null) AlertDialog(onDismissRequest = { restoreBytes = null; restorePassword = "" }, title = { Text("解密备份") }, text = {
        OutlinedTextField(restorePassword, { restorePassword = it }, label = { Text("备份密码") }, visualTransformation = PasswordVisualTransformation(), singleLine = true)
    }, confirmButton = { TextButton(onClick = {
        val secret = restorePassword.toCharArray(); val bytes = restoreBytes!!; restorePassword = ""
        vm.action(done = { restoreBytes = null }) { try { restorePreview = BackupCodec.decode(bytes, secret) } finally { secret.fill('\u0000') } }
    }, enabled = restorePassword.isNotEmpty()) { Text("解密并校验") } }, dismissButton = { TextButton(onClick = { restoreBytes = null; restorePassword = "" }) { Text("取消") } })
    restorePreview?.let { backup -> SimpleSheet("恢复预览 · 校验通过", { restorePreview = null }) {
        val d = backup.data
        Text("${d.transactions.size} 笔账单", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp)); Hint("账户 ${d.accounts.size} · 分类 ${d.categories.size} · 子分类 ${d.subcategories.size} · 标签 ${d.tags.size}")
        Hint("日期范围 ${d.transactions.minOfOrNull { it.date } ?: "无"} — ${d.transactions.maxOfOrNull { it.date } ?: "无"}")
        Hint("导出时间 ${backup.exportedAt}\n主题 ${backup.settings.theme} · 金额遮罩${if (backup.settings.hideAmounts) "开启" else "关闭"}")
        Spacer(Modifier.height(16.dp)); Text("将整体替换本机 ${s.transactions.size} 笔账单及偏好。覆盖前会保留本机快照，不进行隐式合并。", color = Expense, fontSize = 13.sp)
        Spacer(Modifier.height(16.dp)); Button(onClick = { restoreConfirm = true }, modifier = Modifier.fillMaxWidth()) { Text("确认覆盖前继续") }
    } }
    if (restoreConfirm && restorePreview != null) ConfirmDialog("确认覆盖本机账本？", "已完整校验数据与引用。将以备份替换当前账本，并保留覆盖前快照。", "覆盖并恢复", { restoreConfirm = false }) { val backup = restorePreview!!; vm.action("恢复完成", done = { restorePreview = null; restoreConfirm = false }) { vm.repository.restore(backup) } }
    if (rollback) ConfirmDialog("撤回上次恢复？", "将使用覆盖前的本机快照替换当前账本和偏好；快照之后新增的账目将不在恢复后的账本中，请先备份。", "撤回恢复", { rollback = false }) { vm.action("已回到恢复前的账本", done = { rollback = false }) { vm.repository.undoRestore() } }
    table?.let { t -> if (csvPreview == null) SimpleSheet("CSV 列映射 · ${t.rows.size} 行", { table = null }) {
        Hint("日期、类型、金额必须映射。账户列未映射时使用默认账户。")
        Csv.fields.forEach { (key, label) ->
            var expanded by remember(key) { mutableStateOf(false) }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(label, fontSize = 12.sp, modifier = Modifier.weight(1f))
                Box { TextButton(onClick = { expanded = true }) { Text(mapping[key]?.let { t.headers[it] } ?: "未映射", fontSize = 12.sp) }
                    DropdownMenu(expanded, { expanded = false }) {
                        DropdownMenuItem(text = { Text("未映射") }, onClick = { mapping = mapping + (key to null); expanded = false })
                        t.headers.forEachIndexed { i, h -> DropdownMenuItem(text = { Text(h) }, onClick = { mapping = mapping + (key to i); expanded = false }) }
                    }
                }
            }
        }
        Hint("默认账户")
        ChoiceChips(s.accounts.filter { !it.archived }.map { it.id to it.name }, defaultAccount) { defaultAccount = it }
        Button(onClick = { vm.action {
            val preview = Csv.preview(vm.ledger.value ?: s, t, mapping, defaultAccount)
            csvPreview = preview; selected = preview.rows.filter { it.selected }.map { it.index }.toSet()
        } }, modifier = Modifier.fillMaxWidth()) { Text("生成逐行预览") }
    } }
    csvPreview?.let { preview -> ModalBottomSheet(onDismissRequest = { csvPreview = null }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.9f).padding(horizontal = 20.dp)) {
            Text("导入预览 · 已选 ${selected.size} 笔", fontWeight = FontWeight.Bold, fontSize = 20.sp)
            Hint("错误 ${preview.rows.count { it.error != null }} · 重复 ID ${preview.rows.count { it.exactDuplicate }} · 疑似重复 ${preview.rows.count { it.suspectedDuplicate }}")
            if (preview.revision != s.meta.revision) Text("账本已变化，预览已过期，请返回重新生成。", color = Expense, fontSize = 12.sp)
            LazyColumn(Modifier.weight(1f)) {
                items(preview.rows, key = { it.index }) { row -> Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(row.index in selected, enabled = row.error == null && !row.exactDuplicate, onCheckedChange = { selected = if (it) selected + row.index else selected - row.index })
                    Column(Modifier.weight(1f)) { Text("第 ${row.index + 2} 行", fontSize = 12.sp, fontWeight = FontWeight.Bold); Hint(row.description)
                        when { row.error != null -> Text(row.error, color = Expense, fontSize = 11.sp); row.exactDuplicate -> Text("源 ID 已存在，禁止重复导入", color = Expense, fontSize = 11.sp); row.suspectedDuplicate -> Text("疑似重复，请核对后手动选择", color = MaterialTheme.colorScheme.primary, fontSize = 11.sp) }
                    }
                } }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = { csvPreview = null }, modifier = Modifier.weight(1f)) { Text("返回映射") }
                Button(enabled = selected.isNotEmpty() && preview.revision == s.meta.revision, onClick = { importConfirm = true }, modifier = Modifier.weight(1f)) { Text("导入 ${selected.size} 笔") }
            }
            Spacer(Modifier.height(24.dp))
        }
    } }
    if (importConfirm && csvPreview != null) ConfirmDialog("确认导入 ${selected.size} 笔？", "仅选中行及其新建分类 / 标签会入库，全部内容同事务保存。可撤销最近一次导入。", "导入", { importConfirm = false }) {
        val preview = csvPreview!!; val ids = selected
        vm.action("导入完成，可在明细撤销", done = { table = null; csvPreview = null; importConfirm = false }) {
            vm.repository.mutate(undoable = true, expectedRevision = preview.revision) { Csv.apply(it, preview, ids) }
        }
    }
}
