package com.github.caiheyu.spendy

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.caiheyu.spendy.data.*
import com.github.caiheyu.spendy.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class RepositoryTest {
    private lateinit var db: SpendyDatabase
    private lateinit var repo: LedgerRepository
    private lateinit var prefs: Preferences
    private lateinit var prefScope: CoroutineScope
    private lateinit var prefFile: File
    @Before fun setup() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, SpendyDatabase::class.java).build()
        prefScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        prefFile = File(context.cacheDir, "test-${UUID.randomUUID()}.preferences_pb")
        prefs = Preferences(PreferenceDataStoreFactory.create(scope = prefScope) { prefFile })
        repo = LedgerRepository(db, prefs); repo.initialize()
    }
    @After fun close() { db.close(); prefScope.cancel(); prefFile.delete() }
    private fun expense(id: String = "test-expense") = Entry(id = id, amountMinor = 10_000, accountId = "a-cash", categoryId = "c-food", tagIds = listOf("t-work"), reimbursable = true)
    @Test fun databasePersistsStableReferencesAndTagLinks() = runBlocking {
        repo.save(expense()); val state = repo.state.first(); assertEquals(listOf("t-work"), state.entry("test-expense")?.tagIds)
        repo.saveCategory(state.category("c-food")!!.copy(name = "吃饭"))
        assertEquals("吃饭", repo.state.first().category(repo.state.first().entry("test-expense")?.categoryId)?.name)
    }
    @Test fun transferBothEndsAndFeeAreAtomic() = runBlocking {
        repo.saveAccount(Account(id = "b", name = "卡"))
        repo.save(Entry(id = "transfer", type = "transfer", amountMinor = 10000, accountId = "a-cash", toAccountId = "b", feeMinor = 100, feeCategoryId = "c-fee"))
        assertEquals(-100L, Ledger.net(repo.state.first()))
        repo.delete(setOf("transfer")); assertEquals(0L, Ledger.net(repo.state.first()))
        repo.undo(); assertEquals(-100L, Ledger.net(repo.state.first()))
        val state = repo.state.first()
        assertEquals(Ledger.totals(state, "1900-01-01", "9999-12-31"), repo.totals("1900-01-01", "9999-12-31"))
    }
    @Test fun refundAndReimbursementSurviveRoundTrip() = runBlocking {
        repo.save(expense())
        repo.save(Entry(id = "refund", amountMinor = -3000, accountId = "a-cash", kind = "refund", originalId = "test-expense"))
        repo.save(Entry(id = "recovery", amountMinor = -7000, accountId = "a-cash", kind = "reimbursement", originalId = "test-expense"))
        val state = repo.state.first(); assertEquals(0L, Ledger.pending(state, state.entry("test-expense")!!)); assertEquals(0L, Ledger.net(state))
        repo.delete(setOf("test-expense")); assertTrue(repo.state.first().transactions.isEmpty()); repo.undo(); assertEquals(3, repo.state.first().transactions.size)
    }
    @Test fun invalidBatchRollsBackEveryRow() = runBlocking {
        repo.save(expense("a")); repo.save(Entry(id = "b", type = "income", amountMinor = 1000, accountId = "a-cash", categoryId = "c-salary"))
        val before = repo.state.first()
        try { repo.batch(setOf("a", "b"), "c-shop", null, emptySet(), emptySet(), null); fail("expected failure") } catch (_: IllegalArgumentException) { }
        assertEquals(before, repo.state.first())
    }
    @Test fun subsequentWriteInvalidatesUndo() = runBlocking {
        repo.save(expense()); repo.delete(setOf("test-expense")); repo.save(expense("new"))
        try { repo.undo(); fail("undo must be invalidated") } catch (_: IllegalStateException) { }
        assertEquals(listOf("new"), repo.state.first().transactions.map { it.id })
    }
    @Test fun deleteSubcategoryKeepsBillsAndParent() = runBlocking {
        repo.save(expense().copy(subcategoryId = "s-food-0")); repo.deleteEntity("subcategory", "s-food-0")
        val e = repo.state.first().entry("test-expense")!!; assertNull(e.subcategoryId); assertEquals("c-food", e.categoryId)
    }
    @Test fun referencedAccountCannotBeDeleted() = runBlocking {
        repo.save(expense()); try { repo.deleteEntity("account", "a-cash"); fail("expected constraint") } catch (_: IllegalArgumentException) { }
        assertNotNull(repo.state.first().account("a-cash"))
    }
    @Test fun restoreAndRollbackPreserveSettingsAndMonotonicRevision() = runBlocking {
        repo.save(expense()); prefs.setSettings(AppSettings(theme = "dark"))
        val backup = repo.export(); repo.save(expense("later")); prefs.setSettings(AppSettings(theme = "light"))
        val revision = repo.state.first().meta.revision
        repo.restore(backup)
        assertEquals(1, repo.state.first().transactions.size); assertEquals("dark", prefs.settings.first().theme)
        assertTrue(repo.state.first().meta.revision > revision); assertEquals(repo.state.first().meta.revision, prefs.settings.first().backedUpRevision)
        repo.undoRestore(); assertEquals(2, repo.state.first().transactions.size); assertEquals("light", prefs.settings.first().theme)
    }
    @Test fun startupCompletesInterruptedPreferenceRestore() = runBlocking {
        val target = AppSettings(theme = "dark", hideAmounts = false)
        db.ledger().control(Control("restore_journal", LedgerJson.encodeToString(target)))
        val restarted = LedgerRepository(db, prefs); restarted.initialize()
        assertEquals(target, prefs.settings.first()); assertNull(db.ledger().control("restore_journal"))
    }
    @Test fun corruptRestoreLeavesCurrentDatabaseUntouched() = runBlocking {
        repo.save(expense()); val before = repo.state.first()
        val invalid = before.copy(transactions = before.transactions.map { it.copy(accountId = "missing") })
        try { repo.restore(Backup(data = invalid, settings = AppSettings())); fail("expected failure") } catch (_: IllegalStateException) { }
        assertEquals(before, repo.state.first())
    }
}
