package com.github.caiheyu.spendy

import androidx.activity.ComponentActivity
import androidx.core.view.WindowInsetsControllerCompat
import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.caiheyu.spendy.data.*
import com.github.caiheyu.spendy.domain.*
import com.github.caiheyu.spendy.ui.*
import com.github.caiheyu.spendy.ui.theme.SpendyTheme
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File

/** Each UI test has an isolated in-memory ledger; production storage is never cleared. */
@RunWith(AndroidJUnit4::class)
class UiFlowTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var db: SpendyDatabase
    private lateinit var prefsScope: CoroutineScope
    private lateinit var vm: SpendyViewModel
    private lateinit var viewModelStore: ViewModelStore
    private lateinit var prefFile: File
    private lateinit var context: Context
    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, SpendyDatabase::class.java).build()
        prefsScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        prefFile = File(context.cacheDir, "ui-${newId()}.preferences_pb")
        val prefs = Preferences(PreferenceDataStoreFactory.create(scope = prefsScope) { prefFile })
        val repo = LedgerRepository(db, prefs)
        viewModelStore = ViewModelStore()
        vm = ViewModelProvider(viewModelStore, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = SpendyViewModel(repo, prefs, UpdateService(context)) as T
        })[SpendyViewModel::class.java]
        compose.setContent {
            val settings by vm.settings.collectAsStateWithLifecycle()
            SpendyTheme(settings) { SpendyApp(vm) }
        }
        compose.waitUntil(15_000) { vm.ledger.value != null }
    }
    @After fun teardown() { viewModelStore.clear(); db.close(); prefsScope.cancel(); prefFile.delete() }
    private fun capture(name: String) {
        compose.mainClock.advanceTimeBy(400)
        compose.waitForIdle()
        val directory = File(context.getExternalFilesDir(null), "qa").apply { mkdirs() }
        // Capture Compose directly so launcher/activity transitions cannot leak into the preview.
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    @Test fun calculatorSaveAndPartialRefundWorkThroughNativeUi() {
        compose.onNodeWithContentDescription("记一笔").performClick()
        listOf("1", "8", ".", "5", "0").forEach { compose.onNodeWithText(it).performClick() }
        capture("entry")
        compose.onNodeWithTag("entry-save").assertIsEnabled()
        compose.onNodeWithTag("entry-save").performClick()
        compose.waitUntil(10_000) { vm.ledger.value?.transactions?.size == 1 && vm.editor.value == null }
        compose.onNodeWithText("−18.50").assertExists()
        capture("home-with-entry")
        compose.onNodeWithText("餐饮").performClick()
        compose.onNodeWithText("账单详情").assertExists()
        compose.onNodeWithText("登记退款").performClick()
        compose.waitUntil { vm.editor.value?.kind == "refund" }
        compose.onNodeWithText("实际到账金额").assertExists()
        repeat(5) { compose.onNodeWithContentDescription("退格").performClick() }
        compose.onNodeWithText("5").performClick()
        compose.waitUntil { !vm.busy.value }
        val refund = vm.editor.value!!.toEntry()
        assertEquals(-500L, refund.amountMinor)
        compose.waitForIdle()
        compose.onNodeWithText("实际到账金额").assertExists()
        compose.onNodeWithTag("entry-save").assertIsEnabled()
        compose.onNodeWithTag("entry-save").performClick()
        compose.waitUntil(10_000) { vm.ledger.value?.transactions?.size == 2 && vm.editor.value == null }
        val s = vm.ledger.value!!
        assertEquals(1350L, Ledger.project(s).sumOf { it.amount })
        assertEquals(-1350L, Ledger.net(s))
        capture("refund-details")
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithText("图表").performClick()
        compose.onNodeWithText("本期净支出").assertExists()
        capture("charts")
    }
    @Test fun accountsPrivacyAndDarkThemeArePersistent() {
        compose.onNodeWithText("账户").performClick()
        compose.onNodeWithText("已记录账户净额").assertExists()
        compose.onNodeWithContentDescription("显示金额").performClick()
        compose.waitUntil { !vm.settings.value.hideAmounts }
        capture("accounts")
        compose.onNodeWithText("我的").performClick()
        compose.onNodeWithText("外观模式").performScrollTo().performClick()
        compose.onNodeWithText("深色").performClick()
        compose.waitUntil { vm.settings.value.theme == "dark" }
        val bars = WindowInsetsControllerCompat(compose.activity.window, compose.activity.window.decorView)
        assertFalse(bars.isAppearanceLightStatusBars)
        assertFalse(bars.isAppearanceLightNavigationBars)
        capture("settings-dark")
    }
    @Test fun managementCreatesRealCategory() {
        compose.onNodeWithText("我的").performClick()
        compose.onNodeWithText("分类管理").performScrollTo().performClick()
        compose.onNodeWithContentDescription("新建").performClick()
        compose.onNode(hasSetTextAction()).performTextInput("测试分类")
        compose.onNodeWithText("保存").performScrollTo().performClick()
        compose.waitUntil(10_000) { vm.ledger.value?.categories?.any { it.name == "测试分类" } == true }
        assertEquals(21, vm.ledger.value!!.categories.size)
    }
    @Test fun backupPasswordMustBeConfirmedBeforeExport() {
        compose.onNodeWithText("我的").performClick()
        compose.onNodeWithTag("settings-list").performScrollToNode(hasText("数据管理"))
        compose.onNodeWithText("数据管理").performScrollTo().performClick()
        compose.onNodeWithText("导出 .spendy 备份").performScrollTo().performClick()
        compose.onNodeWithText("选择保存位置").assertIsNotEnabled()
        compose.onNodeWithText("密码 · 至少 8 个字符").performTextInput("12345678")
        compose.onNodeWithText("再次输入密码").performTextInput("12345678")
        compose.onNodeWithText("选择保存位置").assertIsEnabled()
    }

    @Test fun prototypePaletteAndLedgerSelectionWorkInBothThemes() {
        listOf("light", "dark").forEach { theme ->
            vm.changeSettings { it.copy(theme = theme, dynamicColor = false, accent = "#5560E9") }
            compose.waitUntil { vm.settings.value.theme == theme }
            compose.waitForIdle()
            val pixels = compose.onRoot().captureToImage().toPixelMap()
            val background = Color(if (theme == "light") 0xFFF4F5FA else 0xFF0E0F16).toArgb()
            val card = Color(if (theme == "light") 0xFFFFFFFF else 0xFF171923).toArgb()
            var backgrounds = 0
            var cards = 0
            for (y in 0 until pixels.height step 5) for (x in 0 until pixels.width step 5) {
                when (pixels[x, y].toArgb()) { background -> backgrounds++; card -> cards++ }
            }
            assertTrue("$theme background must use the prototype color", backgrounds > 100)
            assertTrue("$theme cards must not receive a Material tonal overlay", cards > 100)
            capture("palette-home-$theme")

            compose.onNodeWithContentDescription("记一笔").performClick()
            compose.onNodeWithText("支出").assertIsSelected()
            capture("palette-entry-$theme")
            compose.onNodeWithText("收入").performClick()
            compose.onNodeWithText("收入").assertIsSelected()
            compose.onNodeWithContentDescription("返回").performClick()

            compose.onNodeWithText("图表").performClick()
            compose.onNodeWithText("支出").performClick()
            compose.onNodeWithText("支出").assertIsSelected()
            capture("palette-charts-expense-$theme")
            compose.onNodeWithText("收入").performClick()
            compose.onNodeWithText("收入").assertIsSelected()
            capture("palette-charts-income-$theme")
            compose.onNodeWithText("明细").performClick()
        }
    }
}
