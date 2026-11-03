package com.github.caiheyu.spendy.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.*
import com.github.caiheyu.spendy.domain.*
import com.github.caiheyu.spendy.ui.theme.LocalSpendyColors

@Composable
fun SpendyApp(vm: SpendyViewModel) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    CompositionLocalProvider(LocalIconLibrary provides settings.iconLibrary) {
        SpendyContent(vm)
    }
}

@Composable
private fun SpendyContent(vm: SpendyViewModel) {
    val s by vm.ledger.collectAsStateWithLifecycle()
    val fatal by vm.fatal.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val nav = rememberNavController()
    val current by nav.currentBackStackEntryAsState()
    val route = current?.destination?.route ?: "home"
    val snackbar = remember { SnackbarHostState() }
    var drill by remember { mutableStateOf(EntryFilter()) }
    LaunchedEffect(vm) {
        for (message in vm.messages) {
            if (current?.destination?.route == "entry") continue
            snackbar.showSnackbar(message)
        }
    }
    fun edit(e: Entry? = null, copy: Boolean = false, kind: String? = null) { vm.openEditor(e, copy, kind); nav.navigate("entry") }
    fun browse(f: EntryFilter) { drill = f; nav.navigate("drill") }
    Scaffold(containerColor = MaterialTheme.colorScheme.background, snackbarHost = { SnackbarHost(snackbar) }, bottomBar = {
        if (route in listOf("home", "charts", "accounts", "me") && s != null) BottomBar(route, onNavigate = {
            nav.navigate(it) { popUpTo("home") { saveState = true }; launchSingleTop = true; restoreState = true }
        }, onAdd = { edit() })
    }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            val state = s
            if (state == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    if (fatal == null) CircularProgressIndicator() else Text("账本加载失败\n$fatal", Modifier.padding(28.dp))
                }
            } else NavHost(nav, startDestination = "home") {
                composable("home") { TransactionsScreen(vm, state, onEntry = { nav.navigate("detail/${it.id}") }, onAdd = { edit() }, onSearch = { nav.navigate("search") }, onPending = { browse(EntryFilter(pendingOnly = true)) }) }
                composable("charts") { ChartsScreen(vm, state, ::browse) }
                composable("accounts") { AccountsScreen(vm, state, ::browse, onManage = { nav.navigate("manage/account") }) }
                composable("me") { SettingsScreen(vm, state, onManage = { nav.navigate("manage/$it") }, onData = { nav.navigate("data") }, onIcons = { nav.navigate("icons") }) }
                composable("icons") { IconLibraryScreen(vm, state, onBack = { nav.popBackStack() }) }
                composable("entry") { EntryScreen(vm, state, onClose = { nav.popBackStack() }, onNewAccount = { nav.navigate("manage/account") }) }
                composable("detail/{id}") { b ->
                    val entry = state.entry(b.arguments?.getString("id"))
                    if (entry == null) LaunchedEffect(Unit) { nav.popBackStack() }
                    else EntryDetails(vm, state, entry, onBack = { nav.popBackStack() }, onEdit = { edit(entry) }, onCopy = { edit(entry, true) }, onRecover = { edit(entry, kind = it) })
                }
                composable("search") { SearchScreen(vm, state, onBack = { nav.popBackStack() }, onEntry = { nav.navigate("detail/${it.id}") }) }
                composable("drill") { FilteredScreen(state, drill, onBack = { nav.popBackStack() }, onEntry = { nav.navigate("detail/${it.id}") }) }
                composable("manage/{kind}") { b -> ManagementScreen(vm, state, b.arguments?.getString("kind") ?: "category", onBack = { nav.popBackStack() }) }
                composable("data") { DataScreen(vm, state, onBack = { nav.popBackStack() }) }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
        }
    }
}

@Composable
private fun BottomBar(route: String, onNavigate: (String) -> Unit, onAdd: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 0.dp) {
        Column {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(Modifier.fillMaxWidth().navigationBarsPadding().height(76.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            val items = listOf(Triple("home", "list", "明细"), Triple("charts", "chart", "图表"), Triple("add", "plus", "记账"), Triple("accounts", "wallet", "账户"), Triple("me", "user", "我的"))
            items.forEach { (key, icon, text) ->
                if (key == "add") Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(56.dp).shadow(9.dp, CircleShape).clip(CircleShape)
                        .background(Brush.linearGradient(listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.secondary))).clickable(onClick = onAdd), contentAlignment = Alignment.Center) {
                        SIcon("plus", "记一笔", Modifier.size(28.dp), Color.White)
                    }
                } else Column(Modifier.weight(1f).clip(CardShape).clickable { onNavigate(key) }.padding(vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    val color = if (route == key) MaterialTheme.colorScheme.primary else LocalSpendyColors.current.mutedInk
                    SIcon(icon, text, Modifier.size(23.dp), color)
                    Text(text, fontSize = 11.sp, color = color, fontWeight = if (route == key) FontWeight.Bold else FontWeight.Normal)
                }
            }
        }
        }
    }
}
