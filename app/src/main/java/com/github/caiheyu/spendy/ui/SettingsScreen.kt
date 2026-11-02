@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.github.caiheyu.spendy.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.caiheyu.spendy.BuildConfig
import com.github.caiheyu.spendy.domain.*
import com.github.caiheyu.spendy.ui.theme.*

@Composable
fun SettingsScreen(vm: SpendyViewModel, s: LedgerState, onManage: (String) -> Unit, onData: () -> Unit) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val update by vm.availableUpdate.collectAsStateWithLifecycle()
    val downloadedApk by vm.updateApk.collectAsStateWithLifecycle()
    var sheet by remember { mutableStateOf<String?>(null) }
    var updateDialog by remember { mutableStateOf(false) }
    var reset by remember { mutableStateOf(false) }
    val context = LocalContext.current
    LazyColumn(Modifier.fillMaxSize().testTag("settings-list"), contentPadding = PaddingValues(start = 18.dp, end = 18.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { PageHeader("我的") }
        item { SpendCard(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(58.dp).clip(CardShape).background(Brush.linearGradient(listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.secondary))), contentAlignment = Alignment.Center) { SIcon("wallet", "Spendy", Modifier.size(29.dp), Color.White) }
                Spacer(Modifier.width(15.dp))
                Column { Text("Spendy", fontWeight = FontWeight.Bold, fontSize = 24.sp); Hint("认真记账，自在生活") }
            }
            Spacer(Modifier.height(22.dp)); HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant); Spacer(Modifier.height(17.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                listOf(s.transactions.size.toString() to "账单记录", s.accounts.size.toString() to "我的账户", s.tags.size.toString() to "场景标签").forEach { (n, label) -> Column(horizontalAlignment = Alignment.CenterHorizontally) { Text(n, fontSize = 22.sp, fontWeight = FontWeight.Bold); Hint(label) } }
            }
        } }
        item { SpendCard {
            SectionTitle("账本管理")
            SettingRow("grid", "分类管理", "主分类、子分类与排序") { onManage("category") }
            SettingRow("tag", "标签管理", "为生活添加不同维度") { onManage("tag") }
            SettingRow("wallet", "账户管理", "期初余额、归档与图标") { onManage("account") }
        } }
        item { SpendCard {
            SectionTitle("偏好设置")
            SettingRow("moon", "外观模式", when (settings.theme) { "dark" -> "深色"; "light" -> "浅色"; else -> "跟随系统" }) { sheet = "theme" }
            SettingRow("palette", "主题颜色", if (settings.dynamicColor) "系统动态取色" else "自定义主题色") { sheet = "color" }
            SettingRow("eyeOff", "金额隐私遮罩", "隐藏账户余额及净额趋势", trailing = { SpendySwitch(settings.hideAmounts, { value -> vm.changeSettings { it.copy(hideAmounts = value) } }) }) { vm.changeSettings { it.copy(hideAmounts = !it.hideAmounts) } }
        } }
        item { SpendCard {
            SectionTitle("数据与安全")
            SettingRow("db", "数据管理", "CSV 导入导出 · 加密备份与恢复") { onData() }
            Hint("所有账目仅保存在本机，无账号、无广告、无统计上报。请定期将备份保存到其他介质。")
        } }
        item { SpendCard {
            SectionTitle("关于")
            SettingRow("refresh", "检查更新", update?.let { "发现新版本 ${it.versionName}" } ?: "当前版本 ${BuildConfig.VERSION_NAME}") {
                if (update != null) updateDialog = true else vm.checkUpdate { if (vm.availableUpdate.value != null) updateDialog = true }
            }
            SettingRow("link", "开源项目", "github.com/caiheyu/Spendy") { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/caiheyu/Spendy"))) }
            SettingRow("trash", "重置本机账本", "清空账目并恢复默认设置", color = Expense) { reset = true }
        } }
        item { Text("Spendy · 让每一笔，都有迹可循\nv${BuildConfig.VERSION_NAME}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, lineHeight = 20.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) }
    }
    if (sheet == "theme") SimpleSheet("外观模式", { sheet = null }) {
        ChoiceChips(listOf("system" to "跟随系统", "light" to "浅色", "dark" to "深色"), settings.theme) { value -> vm.changeSettings { it.copy(theme = value) }; sheet = null }
    }
    if (sheet == "color") SimpleSheet("主题颜色", { sheet = null }) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text("使用系统动态取色", modifier = Modifier.weight(1f)); SpendySwitch(settings.dynamicColor, { value -> vm.changeSettings { it.copy(dynamicColor = value) } }) }
        Spacer(Modifier.height(16.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Palette.forEach { value -> Box(Modifier.size(40.dp).clip(CircleShape).background(colorOf(value)).clickable { vm.changeSettings { it.copy(accent = value, dynamicColor = false) } }, contentAlignment = Alignment.Center) { if (settings.accent == value && !settings.dynamicColor) SIcon("check", modifier = Modifier.size(22.dp), tint = Color.White) } }
        }
    }
    if (reset) ConfirmDialog("重置本机账本？", "将移除 ${s.transactions.size} 笔账单、${s.accounts.size} 个账户和自定义项目，恢复默认分类与设置。请先导出备份；本次操作会保留一个本机恢复快照。", "重置账本", { reset = false }) { vm.action("账本已重置", done = { reset = false }) { vm.repository.reset() } }
    if (updateDialog && update != null) AlertDialog(onDismissRequest = { updateDialog = false }, title = { Text("Spendy ${update!!.versionName}") }, text = { Column(Modifier.verticalScroll(rememberScrollState())) { Hint(update!!.publishedAt); Text(update!!.releaseNotes, fontSize = 14.sp, modifier = Modifier.padding(top = 12.dp)); if (downloadedApk != null) Hint("安装包已通过摘要、包名、版本号及签名证书校验。") } },
        confirmButton = { TextButton(onClick = {
            val file = downloadedApk
            if (file == null) vm.action("下载及安全校验完成") { vm.updateApk.value = vm.updates.download(update!!) }
            else if (!vm.updates.canInstall()) { vm.message("请允许 Spendy 安装更新，然后返回点击安装"); context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))) }
            else context.startActivity(vm.updates.installIntent(file))
        }) { Text(if (downloadedApk == null) "下载并校验" else "安装更新") } },
        dismissButton = { TextButton(onClick = { updateDialog = false }) { Text("稍后") } })
}
