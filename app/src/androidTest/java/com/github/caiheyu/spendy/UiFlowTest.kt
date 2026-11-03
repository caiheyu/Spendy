package com.github.caiheyu.spendy

import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.core.app.ActivityOptionsCompat
import androidx.core.view.WindowInsetsControllerCompat
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import android.view.View
import android.widget.LinearLayout
import android.widget.NumberPicker
import androidx.compose.runtime.getValue
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.action.GeneralClickAction
import androidx.test.espresso.action.GeneralLocation
import androidx.test.espresso.action.Press
import androidx.test.espresso.action.Tap
import androidx.test.espresso.matcher.ViewMatchers.*
import com.github.caiheyu.spendy.data.*
import com.github.caiheyu.spendy.domain.*
import com.github.caiheyu.spendy.ui.*
import com.github.caiheyu.spendy.ui.theme.SpendyTheme
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDate
import org.hamcrest.Matcher
import org.hamcrest.Matchers.allOf

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
    @After fun teardown() {
        val vmJob = vm.viewModelScope.coroutineContext[Job]
        compose.runOnIdle { compose.activity.setContent { }; viewModelStore.clear() }
        compose.waitForIdle()
        runBlocking { vmJob?.join(); prefsScope.coroutineContext[Job]?.cancelAndJoin() }
        db.close(); prefFile.delete()
    }
    private fun capture(name: String, node: SemanticsNodeInteraction = compose.onRoot()) {
        compose.mainClock.advanceTimeBy(400)
        compose.waitForIdle()
        val directory = File(context.getExternalFilesDir(null), "qa").apply { mkdirs() }
        // Capture Compose directly so launcher/activity transitions cannot leak into the preview.
        val bitmap = node.captureToImage().asAndroidBitmap()
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    private fun selectLibraryScope(label: String) {
        val chip = hasText(label) and hasAnyAncestor(hasTestTag("icon-library-scope-filter"))
        compose.onNodeWithTag("icon-library").performScrollToNode(chip)
        compose.onNode(chip).performClick()
    }
    private fun selectLibraryType(label: String) {
        compose.onNodeWithTag("icon-library").performScrollToNode(hasText(label))
        compose.onNodeWithText(label).performClick()
    }
    private fun selectEntryDate(date: LocalDate) {
        compose.onNodeWithText("日期").performScrollTo().performClick()
        onView(allOf(isAssignableFrom(LinearLayout::class.java), withChild(isAssignableFrom(NumberPicker::class.java))))
            .perform(object : ViewAction {
                override fun getConstraints(): Matcher<View> = isDisplayed()
                override fun getDescription() = "Select ledger date through native number pickers"
                override fun perform(uiController: UiController, view: View) {
                    val row = view as LinearLayout
                    val year = row.getChildAt(0) as NumberPicker
                    val month = row.getChildAt(1) as NumberPicker
                    val day = row.getChildAt(2) as NumberPicker
                    year.value = date.year
                    month.value = date.monthValue
                    day.maxValue = date.lengthOfMonth()
                    // The final native click invokes DateWheel's actual change listener.
                    day.value = if (date.dayOfMonth == 1) 2 else date.dayOfMonth - 1
                    GeneralClickAction(Tap.SINGLE, if (date.dayOfMonth == 1)
                        GeneralLocation.TOP_CENTER else GeneralLocation.BOTTOM_CENTER, Press.FINGER).perform(uiController, day)
                    uiController.loopMainThreadUntilIdle()
                }
            })
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("${date.year} 年 ${date.monthValue} 月 ${date.dayOfMonth} 日").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("确定").performClick()
        compose.onNodeWithText(date.toString()).assertExists()
    }
    @Test fun swipeOnlyRevealsDeleteAndRequiresExplicitConfirmation() {
        runBlocking { vm.repository.save(Entry(amountMinor = 1850, accountId = "a-cash", categoryId = "c-food")) }
        compose.waitUntil { vm.ledger.value?.transactions?.size == 1 }
        val row = compose.onNodeWithText("餐饮")
        row.performTouchInput { swipeLeft() }
        compose.onNodeWithText("删除这笔账单？").assertDoesNotExist()
        compose.onNodeWithContentDescription("删除账单").assertIsDisplayed()
        assertEquals(1, vm.ledger.value!!.transactions.size)
        // A right swipe closes the action; tapping the closed row still opens details.
        row.performTouchInput { swipeRight() }
        compose.onNodeWithContentDescription("删除账单").assertDoesNotExist()
        row.performClick()
        compose.onNodeWithText("账单详情").assertExists()
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithText("餐饮").performTouchInput { swipeLeft() }
        compose.onNodeWithContentDescription("删除账单").performClick()
        compose.onNodeWithText("删除这笔账单？").assertExists()
        compose.onNodeWithText("取消").performClick()
        assertEquals(1, vm.ledger.value!!.transactions.size)
        compose.onNodeWithText("餐饮").performTouchInput { swipeLeft() }
        compose.onNodeWithContentDescription("删除账单").performClick()
        compose.onNodeWithText("删除", useUnmergedTree = false).performClick()
        compose.waitUntil { vm.ledger.value?.transactions?.isEmpty() == true }
    }
    @Test fun historicalDateCanBeSavedForNewAndEditedEntries() {
        val earlier = LocalDate.now().minusDays(2)
        compose.onNodeWithContentDescription("记一笔").performClick()
        compose.onNodeWithText("8").performClick()
        selectEntryDate(earlier)
        compose.onNodeWithTag("entry-save").performClick()
        compose.waitUntil(10_000) { vm.ledger.value?.transactions?.size == 1 && vm.editor.value == null }
        val id = vm.ledger.value!!.transactions.single().id
        assertEquals(earlier.toString(), vm.ledger.value!!.entry(id)?.date)
        compose.onNodeWithContentDescription("搜索与批量修改").performClick()
        compose.onNodeWithText("餐饮").performClick()
        compose.onNodeWithContentDescription("编辑").performClick()
        selectEntryDate(earlier.minusDays(2))
        compose.onNodeWithTag("entry-save").performClick()
        compose.waitUntil(10_000) { vm.editor.value == null && vm.ledger.value?.entry(id)?.date == earlier.minusDays(2).toString() }
        assertEquals(1, vm.ledger.value!!.transactions.size)
        assertEquals(-800L, Ledger.balance(vm.ledger.value!!, "a-cash", earlier.minusDays(2).toString()))
    }
    @Test fun saveFailureShowsOpeningDateReasonAndKeepsDraft() {
        runBlocking { vm.repository.saveAccount(vm.ledger.value!!.account("a-cash")!!.copy(openingBalanceMinor = 1000)) }
        compose.waitUntil { vm.ledger.value?.account("a-cash")?.openingBalanceMinor == 1000L }
        compose.onNodeWithContentDescription("记一笔").performClick()
        compose.onNodeWithText("8").performClick()
        selectEntryDate(LocalDate.now().minusDays(2))
        compose.onNodeWithTag("entry-save").performClick()
        compose.waitUntil { vm.editorError.value != null }
        compose.onNodeWithText("账单日期早于「现金」的期初生效日（${LocalDate.now()}），请在账户管理中调整期初日期").assertIsDisplayed()
        assertNotNull(vm.editor.value)
        assertTrue(vm.ledger.value!!.transactions.isEmpty())
        compose.onNodeWithTag("entry-save").assertIsEnabled()
        selectEntryDate(LocalDate.now())
        compose.onNodeWithTag("entry-save").performClick()
        compose.waitUntil(10_000) { vm.ledger.value?.transactions?.size == 1 && vm.editor.value == null }
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
        compose.onNode(hasSetTextAction() and hasText("名称")).performTextInput("测试分类")
        compose.onNodeWithTag("icon-option-piggy").assertDoesNotExist()
        compose.onNodeWithTag("icon-picker-search").assertDoesNotExist()
        compose.onNodeWithTag("icon-picker-more").performScrollTo().performClick()
        compose.onNodeWithTag("icon-picker-search").performScrollTo().performTextInput("储蓄")
        compose.onNodeWithTag("icon-option-piggy").performScrollTo().performClick()
        compose.onNodeWithTag("icon-option-coffee").assertDoesNotExist()
        compose.onNodeWithTag("icon-picker-more").performScrollTo().performClick()
        compose.onNodeWithTag("icon-picker-search").assertDoesNotExist()
        compose.onNodeWithTag("icon-option-piggy").assertExists()
        compose.onNodeWithText("保存").performScrollTo().performClick()
        compose.waitUntil(10_000) { vm.ledger.value?.categories?.any { it.name == "测试分类" } == true }
        assertEquals(21, vm.ledger.value!!.categories.size)
        assertEquals("piggy", vm.ledger.value!!.categories.single { it.name == "测试分类" }.icon)
    }
    @Test fun iconLibraryIsSharedAndRemovalKeepsAssignedImages() {
        val file = File(context.cacheDir, "favorite-${newId()}.png")
        val source = Bitmap.createBitmap(64, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.RED) }
        val icon = try {
            file.outputStream().use { source.compress(Bitmap.CompressFormat.PNG, 100, it) }
            IconImageImporter.read(context, Uri.fromFile(file))
        } finally { source.recycle(); file.delete() }
        val bytes = Base64.decode(icon.value.substringAfter(','), Base64.NO_WRAP)
        val normalized = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        assertEquals(96, normalized.width); assertEquals(96, normalized.height)
        assertEquals(0, android.graphics.Color.alpha(normalized.getPixel(48, 0)))
        assertEquals(android.graphics.Color.RED, normalized.getPixel(48, 48))
        normalized.recycle()
        runBlocking { vm.preferences.change { it.copy(iconLibrary = it.iconLibrary.add(icon)) } }
        compose.waitUntil { vm.settings.value.iconLibrary.customIcons.isNotEmpty() }
        compose.onNodeWithText("我的").performClick()
        compose.onNodeWithText("图标管理").performScrollTo().performClick()
        selectLibraryType("自定义")
        compose.onNodeWithTag("icon-library").performScrollToNode(hasText(icon.name))
        compose.onNodeWithText(icon.name).performClick()
        compose.onNode(hasSetTextAction() and hasText("名称")).performTextReplacement("通用图标")
        compose.onNodeWithTag("icon-scope-category").performScrollTo().performClick()
        compose.onNodeWithTag("icon-scope-account").performScrollTo().performClick()
        compose.onNodeWithText("保存").performClick()
        compose.waitUntil { vm.settings.value.iconLibrary.let {
            it.customIcons.single().name == "通用图标" && it.scopesFor(icon) == listOf("tag")
        } }
        compose.onNodeWithTag("icon-library").performScrollToNode(hasTestTag("icon-library-search"))
        compose.onNodeWithTag("icon-library-search").performTextInput("通用")
        selectLibraryScope("账户")
        compose.onNodeWithTag("icon-library").performScrollToNode(hasText("没有符合条件的图标"))
        compose.onNodeWithText("没有符合条件的图标").assertExists()
        compose.onNodeWithText("通用图标").assertDoesNotExist()
        selectLibraryScope("标签")
        compose.onNodeWithTag("icon-library").performScrollToNode(hasText("通用图标"))
        compose.onNodeWithText("通用图标").assertExists()
        selectLibraryType("内置")
        compose.onNodeWithTag("icon-library").performScrollToNode(hasText("没有符合条件的图标"))
        compose.onNodeWithText("没有符合条件的图标").assertExists()
        compose.onNodeWithTag("icon-library").performScrollToNode(hasTestTag("icon-library-search"))
        compose.onNodeWithTag("icon-library-search").performTextReplacement("钱包")
        compose.onNodeWithTag("icon-library").performScrollToNode(hasTestTag("library-icon-wallet"))
        compose.onNodeWithTag("library-icon-wallet").performClick()
        compose.onNodeWithText("移除此图标").performScrollTo().performClick()
        compose.onNodeWithText("移除").performClick()
        compose.waitUntil { "wallet" in vm.settings.value.iconLibrary.hiddenIconIds }
        compose.onNodeWithTag("icon-library-search").performTextClearance()
        compose.onNodeWithTag("icon-library").performScrollToNode(hasContentDescription("返回"))
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithText("标签管理").performScrollTo().performClick()
        compose.onNodeWithContentDescription("新建").performClick()
        compose.onNodeWithTag("icon-option-wallet").assertDoesNotExist()
        compose.onNode(hasSetTextAction() and hasText("名称")).performTextInput("图片标签")
        compose.onNodeWithTag("icon-picker-more").performScrollTo().performClick()
        compose.onNodeWithText("自定义").performScrollTo().performClick()
        compose.onNodeWithTag("icon-option-${icon.key}").performScrollTo().performClick()
        compose.onNodeWithText("保存").performScrollTo().performClick()
        compose.waitUntil(10_000) { vm.ledger.value?.tags?.any { it.name == "图片标签" && it.icon == icon.value } == true }
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithText("图标管理").performScrollTo().performClick()
        selectLibraryType("自定义")
        compose.onNodeWithTag("icon-library").performScrollToNode(hasTestTag("remove-icon-${icon.key}"))
        compose.onNodeWithTag("remove-icon-${icon.key}").performClick()
        compose.onNodeWithText("移除").performClick()
        compose.waitUntil { icon.key in vm.settings.value.iconLibrary.hiddenIconIds }
        assertEquals(icon.value, vm.ledger.value!!.tags.single { it.name == "图片标签" }.icon)
        assertFalse(vm.settings.value.iconLibrary.available(vm.ledger.value!!).any { it.key == icon.key })
        compose.onNodeWithTag("icon-library").performScrollToNode(hasText("恢复全部内置图标"))
        compose.onNodeWithText("恢复全部内置图标").performClick()
        compose.waitUntil { "wallet" !in vm.settings.value.iconLibrary.hiddenIconIds }
        assertTrue(vm.settings.value.iconLibrary.available(vm.ledger.value!!).any { it.key == "wallet" })
    }
    @Test fun iconVisibilityControlsAllThreeEditorsAndKeepsExistingAssignments() {
        val wallet = BuiltInIcons.first { it.key == "wallet" }
        fun openWalletSettings() {
            compose.onNodeWithText("图标管理").performScrollTo().performClick()
            compose.onNodeWithTag("icon-library-search").performTextInput("钱包")
            compose.onNodeWithTag("icon-library").performScrollToNode(hasTestTag("library-icon-wallet"))
            compose.onNodeWithTag("library-icon-wallet").performClick()
        }
        fun leaveLibrary() {
            compose.onNodeWithTag("icon-library").performScrollToNode(hasContentDescription("返回"))
            compose.onNodeWithContentDescription("返回").performClick()
        }
        compose.onNodeWithText("我的").performClick()
        openWalletSettings()
        compose.onNodeWithTag("icon-scope-category").assertIsOn().performClick()
        compose.onNodeWithTag("icon-scope-tag").assertIsOn().performClick()
        compose.onNodeWithTag("icon-scope-account").assertIsOn()
        compose.onNodeWithText("保存").performClick()
        compose.waitUntil { vm.settings.value.iconLibrary.scopesFor(wallet) == listOf("account") }
        listOf("分类", "标签").forEach { scope ->
            selectLibraryScope(scope)
            compose.onNodeWithTag("library-icon-wallet").assertDoesNotExist()
            compose.onNodeWithTag("icon-library").performScrollToNode(hasText("没有符合条件的图标"))
            compose.onNodeWithText("没有符合条件的图标").assertExists()
        }
        selectLibraryScope("账户")
        compose.onNodeWithTag("icon-library").performScrollToNode(hasTestTag("library-icon-wallet"))
        compose.onNodeWithTag("library-icon-wallet").assertIsDisplayed()
        selectLibraryScope("全部范围")
        leaveLibrary()
        listOf("分类", "标签", "账户").forEach { label ->
            compose.onNodeWithText("${label}管理").performScrollTo().performClick()
            compose.onNodeWithContentDescription("新建").performClick()
            compose.onNode(hasSetTextAction() and hasText("名称")).performTextInput("范围$label")
            compose.onNodeWithTag("icon-picker-more").performScrollTo().performClick()
            if (label == "账户") compose.onNodeWithTag("icon-option-wallet").performScrollTo().performClick()
            else compose.onNodeWithTag("icon-option-wallet").assertDoesNotExist()
            compose.onNodeWithText("保存").performScrollTo().performClick()
            compose.waitUntil(10_000) { vm.ledger.value!!.let {
                when (label) {
                    "分类" -> it.categories.any { item -> item.name == "范围$label" }
                    "标签" -> it.tags.any { item -> item.name == "范围$label" }
                    else -> it.accounts.any { item -> item.name == "范围$label" && item.icon == "wallet" }
                }
            } }
            compose.onNodeWithContentDescription("返回").performClick()
        }
        openWalletSettings()
        compose.onNodeWithTag("icon-scope-category").assertIsOff()
        compose.onNodeWithTag("icon-scope-tag").assertIsOff()
        compose.onNodeWithTag("icon-scope-account").assertIsOn().performClick()
        compose.onNodeWithText("保存").performClick()
        compose.waitUntil { vm.settings.value.iconLibrary.scopesFor(wallet).isEmpty() }
        selectLibraryScope("账户")
        compose.onNodeWithTag("library-icon-wallet").assertDoesNotExist()
        selectLibraryScope("均不可见")
        compose.onNodeWithTag("icon-library").performScrollToNode(hasTestTag("library-icon-wallet"))
        compose.onNodeWithTag("library-icon-wallet").assertIsDisplayed()
        leaveLibrary()
        compose.onNodeWithText("账户管理").performScrollTo().performClick()
        compose.onNodeWithText("范围账户").performScrollTo().performClick()
        compose.onNodeWithTag("icon-picker-more").performScrollTo().performClick()
        compose.onNodeWithTag("icon-option-wallet").assertDoesNotExist()
        compose.onNode(hasSetTextAction() and hasText("名称")).performScrollTo().performTextReplacement("保留原图标的账户")
        compose.onNodeWithText("保存").performScrollTo().performClick()
        compose.waitUntil(10_000) { vm.ledger.value!!.accounts.any { it.name == "保留原图标的账户" && it.icon == "wallet" } }
    }
    @Test fun replacingIconUpdatesEveryAssignmentAndConfirmsAffectedArchivedItems() {
        val source = LibraryIcon("统一图案", "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aXpAAAAAASUVORK5CYII=")
        runBlocking {
            vm.preferences.change { it.copy(iconLibrary = it.iconLibrary.add(source).withScopes(source, listOf("account"))) }
            vm.repository.save(Entry(id = "icon-replacement-entry", amountMinor = 100, accountId = "a-cash", categoryId = "c-food", tagIds = listOf("t-work")))
            vm.repository.mutate { ledger ->
                ledger.copy(
                    accounts = ledger.accounts.map { if (it.id == "a-cash") it.copy(icon = source.value, archived = true) else it },
                    categories = ledger.categories.map { if (it.id == "c-food") it.copy(icon = source.value, archived = true) else it },
                    tags = ledger.tags.map { if (it.id == "t-work") it.copy(icon = source.value, archived = true) else it },
                )
            }
        }
        compose.waitUntil { vm.ledger.value!!.let { it.category("c-food")?.icon == source.value && it.transactions.any { entry -> entry.id == "icon-replacement-entry" } } }
        val before = vm.ledger.value!!
        compose.onNodeWithText("我的").performClick()
        compose.onNodeWithText("图标管理").performScrollTo().performClick()
        compose.onNodeWithText("自定义").performClick()
        compose.onNodeWithTag("icon-library-search").performTextInput("统一图案")
        fun previewWallet() {
            val icon = hasText(source.name) and !hasSetTextAction()
            compose.onNodeWithTag("icon-library").performScrollToNode(icon)
            compose.onNode(icon).performClick()
            compose.onNodeWithText("替换图标").performScrollTo().performClick()
            compose.onNodeWithTag("icon-picker-more").performScrollTo().performClick()
            compose.onNodeWithTag("icon-picker-search").performScrollTo().performTextInput("钱包")
            compose.onNodeWithTag("icon-option-wallet").performScrollTo().performClick()
            compose.onNodeWithTag("preview-icon-replacement").performScrollTo().performClick()
        }
        previewWallet()
        compose.onNodeWithText("将更新 1 个分类、1 个标签和 1 个账户，共 3 个项目（含已归档项目）。").assertExists()
        compose.onNodeWithText("历史账单会同步显示新图案，原图标名称和可见范围保留。").assertExists()
        compose.onNodeWithText("返回选择").performClick()
        compose.onNodeWithText("取消").performScrollTo().performClick()
        assertTrue(vm.settings.value.iconLibrary.imageOverrides.isEmpty())
        assertEquals(before, vm.ledger.value)
        previewWallet()
        compose.onNodeWithText("确认替换").performClick()
        compose.waitUntil(10_000) {
            vm.settings.value.iconLibrary.imageFor(source.value) == "wallet" && !vm.busy.value
        }
        assertEquals(before, vm.ledger.value)
        assertEquals("wallet", vm.settings.value.iconLibrary.imageFor(source.value))
        assertEquals(source.name, vm.settings.value.iconLibrary.customIcons.single { it.key == source.key }.name)
        assertEquals(listOf("account"), vm.settings.value.iconLibrary.scopesFor(source))
        assertEquals("wallet", vm.settings.value.iconLibrary.imageFor("wallet"))
    }

    @Test fun uploadedReplacementIsStagedUntilConfirmedAndRendersAcrossAllAssignments() {
        val source = BuiltInIcons.first { it.key == "utensils" }
        val file = File(context.cacheDir, "replacement-${newId()}.png")
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.RED) }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        try {
            val uri = Uri.fromFile(file)
            val expectedImage = IconImageImporter.read(context, uri).value
            // Supply the native picker result while exercising the real upload callback and decoder.
            val pickerOwner = object : ActivityResultRegistryOwner {
                override val activityResultRegistry = object : ActivityResultRegistry() {
                    override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
                        dispatchResult(requestCode, Activity.RESULT_OK, Intent().setData(uri))
                    }
                }
            }
            runBlocking {
                vm.preferences.change { it.copy(iconLibrary = it.iconLibrary.withScopes(source, listOf("category", "tag"))) }
                vm.repository.save(Entry(id = "image-entry", amountMinor = 700, accountId = "a-cash", categoryId = "c-food", tagIds = listOf("t-work")))
                vm.repository.mutate { ledger -> ledger.copy(
                    accounts = ledger.accounts.map { it.copy(icon = source.value, archived = true) },
                    categories = ledger.categories.map { if (it.id == "c-food") it.copy(archived = true) else it },
                    tags = ledger.tags.map { if (it.id == "t-work") it.copy(icon = source.value, archived = true) else it },
                ) }
            }
            compose.runOnIdle {
                compose.activity.setContent {
                    val settings by vm.settings.collectAsStateWithLifecycle()
                    SpendyTheme(settings) {
                        CompositionLocalProvider(LocalActivityResultRegistryOwner provides pickerOwner) { SpendyApp(vm) }
                    }
                }
            }
            compose.waitUntil { vm.ledger.value!!.accounts.single().archived }
            val before = vm.ledger.value!!
            compose.onNodeWithText("我的").performClick()
            compose.onNodeWithText("图标管理").performScrollTo().performClick()
            compose.onNodeWithTag("icon-library-search").performTextInput("餐饮")
            fun chooseUpload() {
                compose.onNodeWithTag("icon-library").performScrollToNode(hasTestTag("library-icon-utensils"))
                compose.onNodeWithTag("library-icon-utensils").performClick()
                compose.onNodeWithText("替换图标").performScrollTo().performClick()
                compose.onNodeWithText("选择替换图片", substring = true).performScrollTo().performClick()
                compose.waitUntil(10_000) { compose.onAllNodesWithText("替换预览").fetchSemanticsNodes().isNotEmpty() && !vm.busy.value }
            }
            chooseUpload()
            assertTrue(vm.settings.value.iconLibrary.imageOverrides.isEmpty())
            assertTrue(vm.settings.value.iconLibrary.customIcons.isEmpty())
            compose.onNodeWithText("取消").performScrollTo().performClick()
            assertEquals(before, vm.ledger.value)
            assertTrue(vm.settings.value.iconLibrary.imageOverrides.isEmpty())
            chooseUpload()
            compose.onNodeWithTag("preview-icon-replacement").performScrollTo().performClick()
            compose.onNodeWithText("将更新 1 个分类、1 个标签和 1 个账户，共 3 个项目（含已归档项目）。").assertExists()
            capture("icon-replacement-confirm", compose.onNode(androidx.compose.ui.test.isRoot() and hasAnyDescendant(hasText("确认替换图标？"))))
            compose.onNodeWithText("确认替换").performClick()
            compose.waitUntil(10_000) { vm.settings.value.iconLibrary.imageFor(source.value) == expectedImage && !vm.busy.value }
            assertEquals(before, vm.ledger.value)
            assertTrue(vm.settings.value.iconLibrary.customIcons.isEmpty())
            assertEquals(listOf("category", "tag"), vm.settings.value.iconLibrary.scopesFor(source))
            compose.onNodeWithTag("icon-library").performScrollToNode(hasContentDescription("返回"))
            compose.onNodeWithContentDescription("返回").performClick()
            fun assertRedArt(node: SemanticsNodeInteraction) {
                val pixels = node.captureToImage().toPixelMap()
                var redPixels = 0
                for (y in 0 until pixels.height step 2) for (x in 0 until pixels.width step 2) {
                    val pixel = pixels[x, y]
                    if (pixel.red > .99f && pixel.green < .01f && pixel.blue < .01f) redPixels++
                }
                assertTrue("Assigned icon must render the uploaded red image", redPixels > 20)
            }
            listOf("分类" to "餐饮", "标签" to "工作", "账户" to "现金").forEach { (kind, name) ->
                compose.onNodeWithText("${kind}管理").performScrollTo().performClick()
                assertRedArt(compose.onNodeWithText("$name · 已归档").performScrollTo())
                compose.onNodeWithContentDescription("返回").performClick()
            }
            compose.onNodeWithText("明细").performClick()
            assertRedArt(compose.onNodeWithText("餐饮"))
        } finally { file.delete() }
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
