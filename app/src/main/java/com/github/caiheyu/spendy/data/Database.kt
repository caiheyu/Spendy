package com.github.caiheyu.spendy.data

import androidx.room.*
import com.github.caiheyu.spendy.domain.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "events", foreignKeys = [
    ForeignKey(entity = Account::class, parentColumns = ["id"], childColumns = ["accountId"], deferred = true),
    ForeignKey(entity = Account::class, parentColumns = ["id"], childColumns = ["toAccountId"], deferred = true),
    ForeignKey(entity = Category::class, parentColumns = ["id"], childColumns = ["categoryId"], deferred = true),
    ForeignKey(entity = Category::class, parentColumns = ["id"], childColumns = ["feeCategoryId"], deferred = true),
    ForeignKey(entity = Subcategory::class, parentColumns = ["id"], childColumns = ["subcategoryId"], deferred = true),
    ForeignKey(entity = EventRecord::class, parentColumns = ["id"], childColumns = ["originalId"], deferred = true),
], indices = [Index("accountId"), Index("toAccountId"), Index("categoryId"), Index("subcategoryId"),
    Index("feeCategoryId"), Index("originalId"), Index("date"), Index(value = ["externalSource", "externalId"], unique = true)])
data class EventRecord(
    @PrimaryKey val id: String, val type: String, val kind: String,
    val amountMinor: Long, val date: String, val accountId: String,
    val categoryId: String?, val subcategoryId: String?, val note: String,
    val countInStats: Boolean, val reimbursable: Boolean,
    val toAccountId: String?, val feeMinor: Long, val feeCategoryId: String?, val originalId: String?,
    val externalSource: String?, val externalId: String?, val createdAt: Long, val updatedAt: Long,
) {
    fun domain(tags: List<String>) = Entry(id, type, kind, amountMinor, date, accountId, categoryId,
        subcategoryId, tags, note, countInStats, reimbursable, toAccountId, feeMinor, feeCategoryId,
        originalId, externalSource, externalId, createdAt, updatedAt)
    companion object {
        fun from(e: Entry) = EventRecord(e.id, e.type, e.kind, e.amountMinor, e.date, e.accountId,
            e.categoryId, e.subcategoryId, e.note, e.countInStats, e.reimbursable, e.toAccountId,
            e.feeMinor, e.feeCategoryId, e.originalId, e.externalSource, e.externalId, e.createdAt, e.updatedAt)
    }
}

@Entity(tableName = "event_tags", primaryKeys = ["eventId", "tagId"], foreignKeys = [
    ForeignKey(entity = EventRecord::class, parentColumns = ["id"], childColumns = ["eventId"], onDelete = ForeignKey.CASCADE, deferred = true),
    ForeignKey(entity = Tag::class, parentColumns = ["id"], childColumns = ["tagId"], onDelete = ForeignKey.CASCADE, deferred = true),
], indices = [Index("tagId")])
data class EventTag(val eventId: String, val tagId: String)

@Entity(tableName = "control")
data class Control(@PrimaryKey val key: String, val value: String)

data class SqlTotals(val expense: Long, val income: Long)

@Dao
interface LedgerDao {
    @Query("""SELECT
        COALESCE(SUM(CASE WHEN type = 'expense' AND countInStats = 1 THEN amountMinor
            WHEN type = 'transfer' THEN feeMinor ELSE 0 END), 0) AS expense,
        COALESCE(SUM(CASE WHEN type = 'income' AND countInStats = 1 THEN amountMinor ELSE 0 END), 0) AS income
        FROM events WHERE date BETWEEN :start AND :end""")
    suspend fun totals(start: String, end: String): SqlTotals
    @Query("SELECT * FROM accounts ORDER BY sortOrder, createdAt") suspend fun accounts(): List<Account>
    @Query("SELECT * FROM categories ORDER BY sortOrder, createdAt") suspend fun categories(): List<Category>
    @Query("SELECT * FROM subcategories ORDER BY sortOrder, createdAt") suspend fun subcategories(): List<Subcategory>
    @Query("SELECT * FROM tags ORDER BY sortOrder, createdAt") suspend fun tags(): List<Tag>
    @Query("SELECT * FROM events ORDER BY date DESC, createdAt DESC") suspend fun events(): List<EventRecord>
    @Query("SELECT * FROM event_tags ORDER BY tagId") suspend fun links(): List<EventTag>
    @Query("SELECT value FROM control WHERE `key` = :key") suspend fun control(key: String): String?
    @Query("SELECT value FROM control WHERE `key` = 'meta'") fun observeRevision(): Flow<String?>
    @Upsert suspend fun accounts(values: List<Account>)
    @Upsert suspend fun categories(values: List<Category>)
    @Upsert suspend fun subcategories(values: List<Subcategory>)
    @Upsert suspend fun tags(values: List<Tag>)
    @Upsert suspend fun events(values: List<EventRecord>)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun links(values: List<EventTag>)
    @Upsert suspend fun control(value: Control)
    @Query("DELETE FROM control WHERE `key` = :key") suspend fun deleteControl(key: String)
    @Query("DELETE FROM event_tags WHERE eventId IN (:ids)") suspend fun deleteLinks(ids: List<String>)
    @Query("DELETE FROM events WHERE id IN (:ids)") suspend fun deleteEvents(ids: List<String>)
    @Query("DELETE FROM accounts WHERE id IN (:ids)") suspend fun deleteAccounts(ids: List<String>)
    @Query("DELETE FROM categories WHERE id IN (:ids)") suspend fun deleteCategories(ids: List<String>)
    @Query("DELETE FROM subcategories WHERE id IN (:ids)") suspend fun deleteSubcategories(ids: List<String>)
    @Query("DELETE FROM tags WHERE id IN (:ids)") suspend fun deleteTags(ids: List<String>)
}

@Database(entities = [Account::class, Category::class, Subcategory::class, Tag::class,
    EventRecord::class, EventTag::class, Control::class], version = 1, exportSchema = true)
abstract class SpendyDatabase : RoomDatabase() { abstract fun ledger(): LedgerDao }
