package com.github.caiheyu.spendy.domain

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.util.UUID

fun newId(): String = UUID.randomUUID().toString()
fun now(): Long = System.currentTimeMillis()

@Serializable
@Entity(tableName = "accounts")
data class Account(
    @PrimaryKey val id: String = newId(), val name: String,
    val icon: String = "wallet", val color: String = "#5560E9",
    val openingBalanceMinor: Long = 0, val openedOn: String = LocalDate.now().toString(),
    val archived: Boolean = false, val sortOrder: Int = 0,
    val createdAt: Long = now(), val updatedAt: Long = now(),
)

@Serializable
@Entity(tableName = "categories")
data class Category(
    @PrimaryKey val id: String = newId(), val name: String, val type: String = "expense",
    val icon: String = "bag", val color: String = "#FF7A59",
    val archived: Boolean = false, val sortOrder: Int = 0,
    val createdAt: Long = now(), val updatedAt: Long = now(),
)

@Serializable
@Entity(tableName = "subcategories", foreignKeys = [ForeignKey(
    entity = Category::class, parentColumns = ["id"], childColumns = ["categoryId"],
    onDelete = ForeignKey.RESTRICT, deferred = true,
)], indices = [Index("categoryId")])
data class Subcategory(
    @PrimaryKey val id: String = newId(), val categoryId: String, val name: String,
    val archived: Boolean = false, val sortOrder: Int = 0,
    val createdAt: Long = now(), val updatedAt: Long = now(),
)

@Serializable
@Entity(tableName = "tags")
data class Tag(
    @PrimaryKey val id: String = newId(), val name: String,
    val icon: String = "tag", val color: String = "#5560E9",
    val archived: Boolean = false, val sortOrder: Int = 0,
    val createdAt: Long = now(), val updatedAt: Long = now(),
)

@Serializable
data class Entry(
    val id: String = newId(), val type: String = "expense", val kind: String = "normal",
    val amountMinor: Long, val date: String = LocalDate.now().toString(), val accountId: String,
    val categoryId: String? = null, val subcategoryId: String? = null,
    val tagIds: List<String> = emptyList(), val note: String = "",
    val countInStats: Boolean = true, val reimbursable: Boolean = false,
    val toAccountId: String? = null, val feeMinor: Long = 0, val feeCategoryId: String? = null,
    val originalId: String? = null, val externalSource: String? = null, val externalId: String? = null,
    val createdAt: Long = now(), val updatedAt: Long = now(),
)

@Serializable
data class LedgerMeta(
    val schemaVersion: Int = 5, val revision: Long = 0,
    val createdAt: Long = now(), val updatedAt: Long = now(),
)

@Serializable
data class LedgerState(
    val meta: LedgerMeta = LedgerMeta(), val accounts: List<Account> = emptyList(),
    val categories: List<Category> = emptyList(), val subcategories: List<Subcategory> = emptyList(),
    val tags: List<Tag> = emptyList(), val transactions: List<Entry> = emptyList(),
) {
    fun account(id: String?) = accounts.find { it.id == id }
    fun category(id: String?) = categories.find { it.id == id }
    fun subcategory(id: String?) = subcategories.find { it.id == id }
    fun entry(id: String?) = transactions.find { it.id == id }
}

@Serializable
data class AppSettings(
    val theme: String = "system", val accent: String = "#5560E9", val dynamicColor: Boolean = false,
    val hideAmounts: Boolean = true, val lastBackupAt: Long = 0, val backedUpRevision: Long = -1,
    val backedUpEntryCount: Int = 0,
    val backedUpPreferencesHash: String = "",
) {
    fun preferencesHash(): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest("$theme|$accent|$dynamicColor|$hideAmounts".toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    fun isBackupCurrent(revision: Long) = backedUpRevision == revision && backedUpPreferencesHash == preferencesHash()
}

@Serializable
data class EntryDraft(
    val id: String? = null, val type: String = "expense", val kind: String = "normal",
    val expression: String = "", val accountId: String = "", val categoryId: String? = "c-food",
    val subcategoryId: String? = null, val tagIds: List<String> = emptyList(),
    val date: String = LocalDate.now().toString(), val note: String = "",
    val countInStats: Boolean = true, val reimbursable: Boolean = false,
    val toAccountId: String? = null, val fee: String = "", val originalId: String? = null,
    val newEntryId: String = newId(),
) {
    fun toEntry(): Entry = Entry(
        id = id ?: newEntryId, type = type, kind = kind,
        amountMinor = Money.evaluate(expression).let { if (kind != "normal") -it else it },
        accountId = accountId, categoryId = if (type in listOf("expense", "income")) categoryId else null,
        subcategoryId = if (type in listOf("expense", "income")) subcategoryId else null,
        tagIds = if (type in listOf("expense", "income")) tagIds else emptyList(),
        date = date, note = note, countInStats = if (type == "transfer") true else if (type == "adjustment") false else countInStats,
        reimbursable = type == "expense" && kind == "normal" && reimbursable,
        toAccountId = if (type == "transfer") toAccountId else null,
        feeMinor = if (type == "transfer" && fee.isNotBlank()) Money.parse(fee) else 0,
        feeCategoryId = if (type == "transfer" && fee.isNotBlank() && Money.parse(fee) > 0) "c-fee" else null,
        originalId = originalId,
    )
    companion object {
        fun from(e: Entry) = EntryDraft(e.id, e.type, e.kind, Money.decimal(if (e.type == "adjustment") e.amountMinor else kotlin.math.abs(e.amountMinor)),
            e.accountId, e.categoryId, e.subcategoryId, e.tagIds, e.date, e.note, e.countInStats,
            e.reimbursable, e.toAccountId, if (e.feeMinor == 0L) "" else Money.decimal(e.feeMinor), e.originalId)
    }
}

object Money {
    const val MAX = 999_999_999_999_99L
    fun parse(raw: String): Long {
        require(Regex("[+-]?\\d{1,12}(\\.\\d{1,2})?").matches(raw.trim())) { "请输入最多两位小数的金额" }
        return BigDecimal(raw.trim()).movePointRight(2).longValueExact().also {
            require(it in -MAX..MAX) { "金额超出范围" }
        }
    }
    fun decimal(minor: Long): String = BigDecimal.valueOf(minor, 2).toPlainString()
    fun format(minor: Long): String = String.format(java.util.Locale.CHINA, "%,.2f", BigDecimal.valueOf(minor, 2))
    fun evaluate(expression: String): Long {
        val raw = expression.trim().trimEnd('+', '-', '.')
        require(raw.isNotEmpty() && Regex("[+-]?\\d+(?:\\.\\d{1,2})?(?:[+-]\\d+(?:\\.\\d{1,2})?)*").matches(raw)) { "请填写有效金额，最多两位小数" }
        return Regex("[+-]?\\d+(?:\\.\\d{1,2})?").findAll(raw).fold(0L) { sum, part ->
            Math.addExact(sum, parse(part.value))
        }.also { require(it in -MAX..MAX) { "金额超出范围" } }
    }
    fun percent(n: Long, d: Long): String = if (d == 0L) "—" else
        BigDecimal.valueOf(n).multiply(BigDecimal(100)).divide(BigDecimal.valueOf(d), 1, RoundingMode.HALF_UP).toPlainString() + "%"
}
