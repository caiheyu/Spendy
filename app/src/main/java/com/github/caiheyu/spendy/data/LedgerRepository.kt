package com.github.caiheyu.spendy.data

import androidx.room.withTransaction
import com.github.caiheyu.spendy.domain.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class Backup(val schemaVersion: Int = 5, val exportedAt: String = java.time.Instant.now().toString(),
    val data: LedgerState, val settings: AppSettings)
@Serializable
private data class Undo(val data: LedgerState, val expectedRevision: Long, val expiresAt: Long = 0)

@Singleton
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class LedgerRepository @Inject constructor(private val db: SpendyDatabase, val preferences: Preferences) {
    private val dao = db.ledger()
    private val lock = Mutex()
    private val ready = MutableStateFlow(false)
    val state: Flow<LedgerState> = ready.filter { it }.flatMapLatest { dao.observeRevision().map { db.withTransaction { read() } } }
    val canUndo: Flow<Boolean> = state.transformLatest { state ->
        val undo = dao.control("undo")?.let { LedgerJson.decodeFromString<Undo>(it) }
        val remaining = (undo?.expiresAt ?: 0) - now()
        val available = undo?.expectedRevision == state.meta.revision && remaining > 0
        emit(available)
        if (available) { kotlinx.coroutines.delay(remaining); emit(false) }
    }
    val canUndoRestore: Flow<Boolean> = state.map { dao.control("restore_snapshot") != null }
    suspend fun totals(start: String, end: String): Totals = dao.totals(start, end).let { Totals(it.expense, it.income) }

    suspend fun initialize() = lock.withLock {
        db.withTransaction {
            if (dao.control("meta") == null) persist(LedgerState(), Ledger.seed())
        }
        // Room contains the authoritative committed destination. Reapply settings if a crash
        // occurred between the database transaction and the DataStore write.
        dao.control("restore_journal")?.let {
            preferences.setSettings(LedgerJson.decodeFromString(it))
            preferences.saveDraft(null)
            dao.deleteControl("restore_journal")
        }
        ready.value = true
    }
    private suspend fun read(): LedgerState {
        val tags = dao.links().groupBy { it.eventId }
        return LedgerState(dao.control("meta")?.let { LedgerJson.decodeFromString(it) } ?: LedgerMeta(),
            dao.accounts(), dao.categories(), dao.subcategories(), dao.tags(),
            dao.events().map { it.domain(tags[it.id].orEmpty().map { t -> t.tagId }) })
    }
    private suspend fun persist(before: LedgerState, after: LedgerState) {
        val oldEvents = before.transactions.associateBy { it.id }
        val changed = after.transactions.filter { oldEvents[it.id] != it }
        val removed = before.transactions.map { it.id } - after.transactions.map { it.id }.toSet()
        dao.deleteLinks(removed + changed.map { it.id })
        dao.deleteEvents(removed)
        dao.accounts(after.accounts); dao.categories(after.categories); dao.subcategories(after.subcategories); dao.tags(after.tags)
        dao.events(changed.map(EventRecord::from))
        dao.links(changed.flatMap { e -> e.tagIds.map { EventTag(e.id, it) } })
        dao.deleteSubcategories(before.subcategories.map { it.id } - after.subcategories.map { it.id }.toSet())
        dao.deleteCategories(before.categories.map { it.id } - after.categories.map { it.id }.toSet())
        dao.deleteAccounts(before.accounts.map { it.id } - after.accounts.map { it.id }.toSet())
        dao.deleteTags(before.tags.map { it.id } - after.tags.map { it.id }.toSet())
        dao.control(Control("meta", LedgerJson.encodeToString(after.meta)))
    }
    suspend fun mutate(undoable: Boolean = false, expectedRevision: Long? = null, transform: (LedgerState) -> LedgerState) = lock.withLock {
        check(ready.value) { "账本尚未加载完成" }
        db.withTransaction {
            val before = read()
            require(expectedRevision == null || expectedRevision == before.meta.revision) { "账本已变更，请重新预览" }
            val after = transform(before).copy(meta = before.meta.copy(revision = before.meta.revision + 1, updatedAt = now()))
            Ledger.validate(after)
            if (undoable) dao.control(Control("undo", LedgerJson.encodeToString(Undo(before, after.meta.revision, now() + 30_000)))) else dao.deleteControl("undo")
            persist(before, after)
        }
    }
    suspend fun save(e: Entry) = mutate { Ledger.save(it, e) }
    suspend fun delete(ids: Set<String>) = mutate(true) { Ledger.delete(it, ids) }
    suspend fun undo() = lock.withLock {
        db.withTransaction {
            val undo = dao.control("undo")?.let { LedgerJson.decodeFromString<Undo>(it) } ?: error("没有可以撤销的操作")
            require(undo.expiresAt > now()) { "撤销已超过 30 秒，请重新核对账本" }
            val before = read()
            require(before.meta.revision == undo.expectedRevision) { "后续修改已发生，不能覆盖式撤销" }
            val after = undo.data.copy(meta = before.meta.copy(revision = before.meta.revision + 1, updatedAt = now()))
            Ledger.validate(after); persist(before, after); dao.deleteControl("undo")
        }
    }
    suspend fun saveAccount(a: Account) = mutate { s ->
        val old = s.account(a.id)
        s.copy(accounts = s.accounts.filterNot { it.id == a.id } + a.copy(createdAt = old?.createdAt ?: a.createdAt, updatedAt = now()))
    }
    suspend fun saveCategory(c: Category) = mutate { s ->
        val old = s.category(c.id)
        s.copy(categories = s.categories.filterNot { it.id == c.id } + c.copy(createdAt = old?.createdAt ?: c.createdAt, updatedAt = now()))
    }
    suspend fun saveTag(t: Tag) = mutate { s ->
        val old = s.tags.find { it.id == t.id }
        s.copy(tags = s.tags.filterNot { it.id == t.id } + t.copy(createdAt = old?.createdAt ?: t.createdAt, updatedAt = now()))
    }
    suspend fun saveSubcategory(c: Subcategory) = mutate { s ->
        val old = s.subcategory(c.id)
        s.copy(subcategories = s.subcategories.filterNot { it.id == c.id } + c.copy(createdAt = old?.createdAt ?: c.createdAt, updatedAt = now()))
    }
    suspend fun deleteEntity(kind: String, id: String) = mutate { s ->
        when (kind) {
            "account" -> { require(s.transactions.none { it.accountId == id || it.toAccountId == id }) { "有流水的账户不能删除，请归档" }; s.copy(accounts = s.accounts.filterNot { it.id == id }) }
            "category" -> {
                require(s.transactions.none { it.categoryId == id || it.feeCategoryId == id }) { "有流水的分类不能删除，请归档" }
                s.copy(categories = s.categories.filterNot { it.id == id }, subcategories = s.subcategories.filterNot { it.categoryId == id })
            }
            "subcategory" -> s.copy(subcategories = s.subcategories.filterNot { it.id == id }, transactions = s.transactions.map { if (it.subcategoryId == id) it.copy(subcategoryId = null, updatedAt = now()) else it })
            "tag" -> s.copy(tags = s.tags.filterNot { it.id == id }, transactions = s.transactions.map { if (id in it.tagIds) it.copy(tagIds = it.tagIds - id, updatedAt = now()) else it })
            else -> error("未知管理类型")
        }
    }
    suspend fun organizeDefaults() = mutate { s ->
        val defaults = Ledger.seed()
        s.copy(categories = s.categories + defaults.categories.filter { s.category(it.id) == null },
            subcategories = s.subcategories + defaults.subcategories.filter { s.subcategory(it.id) == null },
            tags = s.tags + defaults.tags.filter { d -> s.tags.none { it.id == d.id } })
    }
    suspend fun reorder(kind: String, id: String, steps: Int) = mutate { s ->
        fun reordered(ids: List<String>): Map<String, Int> {
            val list = ids.toMutableList(); val from = list.indexOf(id)
            require(from >= 0) { "项目不存在" }
            val to = (from + steps).coerceIn(0, list.lastIndex)
            list.add(to, list.removeAt(from))
            return list.withIndex().associate { it.value to it.index }
        }
        when (kind) {
            "account" -> { val order = reordered(s.accounts.sortedBy { it.sortOrder }.map { it.id }); s.copy(accounts = s.accounts.map { it.copy(sortOrder = order.getValue(it.id)) }) }
            "tag" -> { val order = reordered(s.tags.sortedBy { it.sortOrder }.map { it.id }); s.copy(tags = s.tags.map { it.copy(sortOrder = order.getValue(it.id)) }) }
            else -> { val type = s.category(id)?.type ?: error("分类不存在"); val order = reordered(s.categories.filter { it.type == type }.sortedBy { it.sortOrder }.map { it.id }); s.copy(categories = s.categories.map { if (it.id in order) it.copy(sortOrder = order.getValue(it.id)) else it }) }
        }
    }
    suspend fun batch(ids: Set<String>, categoryId: String?, accountId: String?, addTags: Set<String>, removeTags: Set<String>, stats: Boolean?) = mutate(true) { s ->
        val selected = s.transactions.filter { it.id in ids }
        require(selected.size == ids.size && selected.all { it.kind == "normal" && it.type in listOf("income", "expense") }) { "仅可批量修改普通收支" }
        require(addTags.all { id -> s.tags.any { it.id == id && !it.archived } }) { "不能添加已归档标签" }
        selected.fold(s) { result, e -> Ledger.save(result, e.copy(categoryId = categoryId ?: e.categoryId,
            subcategoryId = if (categoryId != null && categoryId != e.categoryId) null else e.subcategoryId,
            accountId = accountId ?: e.accountId, tagIds = (e.tagIds + addTags).distinct() - removeTags,
            countInStats = stats ?: e.countInStats)) }
    }
    suspend fun export(): Backup = lock.withLock {
        Backup(data = db.withTransaction { read() }, settings = preferences.settings.first())
    }
    suspend fun restore(backup: Backup, rollback: Boolean = false) = lock.withLock {
        BackupCodec.validate(backup)
        val oldSettings = preferences.settings.first()
        lateinit var newSettings: AppSettings
        db.withTransaction {
            val old = read()
            val restored = backup.data.copy(meta = backup.data.meta.copy(revision = old.meta.revision + 1, updatedAt = now()))
            val wasClean = backup.settings.backedUpRevision == backup.data.meta.revision
            newSettings = if (rollback) backup.settings.copy(backedUpRevision = if (wasClean) restored.meta.revision else backup.settings.backedUpRevision,
                backedUpPreferencesHash = if (wasClean) backup.settings.preferencesHash() else backup.settings.backedUpPreferencesHash)
            else backup.settings.copy(lastBackupAt = java.time.Instant.parse(backup.exportedAt).toEpochMilli(),
                backedUpRevision = restored.meta.revision, backedUpEntryCount = restored.transactions.size,
                backedUpPreferencesHash = backup.settings.preferencesHash())
            dao.control(Control("restore_snapshot", LedgerJson.encodeToString(Backup(data = old, settings = oldSettings))))
            dao.control(Control("restore_journal", LedgerJson.encodeToString(newSettings)))
            dao.deleteControl("undo")
            // Every event must be reinserted after complete replacement, even with equal IDs.
            dao.deleteLinks(old.transactions.map { it.id }); dao.deleteEvents(old.transactions.map { it.id })
            persist(old.copy(transactions = emptyList()), restored)
        }
        preferences.setSettings(newSettings); preferences.saveDraft(null)
        dao.deleteControl("restore_journal")
    }
    suspend fun undoRestore() {
        val backup = dao.control("restore_snapshot")?.let { LedgerJson.decodeFromString<Backup>(it) } ?: error("暂无恢复快照")
        restore(backup, rollback = true)
    }
    suspend fun reset() = restore(Backup(data = Ledger.seed(), settings = AppSettings()), rollback = true)
    companion object {
        fun validateSettings(s: AppSettings) {
            require(s.theme in listOf("system", "light", "dark") && Regex("#[0-9a-fA-F]{6}").matches(s.accent) && s.lastBackupAt >= 0 && s.backedUpRevision >= -1 && s.backedUpEntryCount >= 0 && (s.backedUpPreferencesHash.isEmpty() || Regex("[0-9a-f]{64}").matches(s.backedUpPreferencesHash))) { "备份偏好设置无效" }
        }
    }
}
