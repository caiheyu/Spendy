package com.github.caiheyu.spendy.data

import com.github.caiheyu.spendy.domain.*

data class CsvTable(val headers: List<String>, val rows: List<List<String>>)
data class ImportRow(val index: Int, val entry: Entry? = null, val error: String? = null,
    val exactDuplicate: Boolean = false, val suspectedDuplicate: Boolean = false, val description: String = "") {
    val selected get() = error == null && !exactDuplicate && !suspectedDuplicate && entry != null
}
data class ImportPreview(val revision: Long, val rows: List<ImportRow>, val entities: LedgerState)

object Csv {
    val fields = linkedMapOf("date" to "日期", "type" to "类型", "amount" to "金额", "account" to "账户", "category" to "分类", "subcategory" to "子分类", "tags" to "标签", "note" to "备注", "id" to "源流水 ID", "countInStats" to "计入统计", "reimbursable" to "可报销", "kind" to "事件性质", "schemaVersion" to "格式版本")
    private val aliases = mapOf("date" to listOf("日期", "date"), "type" to listOf("类型", "type"), "amount" to listOf("金额", "amount"), "account" to listOf("账户", "account"), "category" to listOf("分类", "category"), "subcategory" to listOf("子分类", "subcategory", "sub"), "tags" to listOf("标签", "tags"), "note" to listOf("备注", "note"), "id" to listOf("id", "流水ID", "流水 ID"), "countInStats" to listOf("countInStats", "计入统计", "stats"), "reimbursable" to listOf("reimbursable", "可报销"), "kind" to listOf("kind", "事件性质"), "schemaVersion" to listOf("schemaVersion", "格式版本"))
    fun mapping(table: CsvTable) = fields.keys.associateWith { key -> table.headers.indexOfFirst { header -> aliases[key].orEmpty().any { it.equals(header.trim(), true) } }.takeIf { it >= 0 } }
    fun parse(text: String): CsvTable {
        require(text.length <= 20 * 1024 * 1024) { "CSV 文件不能超过 20 MB" }
        val source = text.removePrefix("\uFEFF")
        val rows = mutableListOf<List<String>>(); var row = mutableListOf<String>(); val cell = StringBuilder()
        var quoted = false; var closed = false; var i = 0
        fun pushCell() { row.add(cell.toString()); cell.clear(); closed = false }
        fun pushRow() { pushCell(); if (row.any { it.isNotEmpty() }) rows.add(row); row = mutableListOf(); require(rows.size <= 20_001) { "CSV 最多导入 20,000 笔" } }
        while (i < source.length) {
            val c = source[i]
            if (quoted) {
                if (c == '"') { if (i + 1 < source.length && source[i + 1] == '"') { cell.append('"'); i++ } else { quoted = false; closed = true } }
                else cell.append(c)
            } else when (c) {
                '"' -> { require(cell.isEmpty() && !closed) { "CSV 引号位置无效" }; quoted = true }
                ',' -> pushCell()
                '\r', '\n' -> { if (c == '\r' && i + 1 < source.length && source[i + 1] == '\n') i++; pushRow() }
                else -> { require(!closed) { "CSV 引号后有无效字符" }; cell.append(c) }
            }
            i++
        }
        require(!quoted) { "CSV 引号没有闭合" }
        if (cell.isNotEmpty() || row.isNotEmpty() || closed) pushRow()
        require(rows.size in 2..20_001) { "CSV 需包含表头及 1—20,000 条记录" }
        require(rows[0].distinct().size == rows[0].size && rows[0].all { it.isNotBlank() }) { "CSV 表头存在重复或空列名" }
        return CsvTable(rows.first(), rows.drop(1))
    }
    private fun cell(value: String): String = if (value.any { it in ",\"\r\n" }) "\"${value.replace("\"", "\"\"")}\"" else value
    fun encode(rows: List<List<String>>): String = "\uFEFF" + rows.joinToString("\r\n") { row -> row.joinToString(",") { cell(it) } } + "\r\n"
    fun export(s: LedgerState, start: String, end: String): String {
        val headers = listOf("schemaVersion", "id", "date", "type", "kind", "amount", "account", "category", "subcategory", "tags", "note", "countInStats", "reimbursable", "toAccount", "fee", "originalId")
        val rows = s.transactions.filter { it.date in start..end }.sortedWith(compareBy<Entry> { it.date }.thenBy { it.createdAt }).map { e ->
            listOf("5", e.id, e.date, e.type, e.kind, Money.decimal(e.amountMinor), s.account(e.accountId)?.name.orEmpty(), s.category(e.categoryId)?.name.orEmpty(),
                s.subcategory(e.subcategoryId)?.name.orEmpty(), s.tags.filter { it.id in e.tagIds }.joinToString("|") { it.name }, e.note, e.countInStats.toString(), e.reimbursable.toString(), s.account(e.toAccountId)?.name.orEmpty(), Money.decimal(e.feeMinor), e.originalId.orEmpty())
        }
        return encode(listOf(headers) + rows)
    }
    fun template(): String = encode(listOf(listOf("date", "type", "amount", "account", "category", "subcategory", "tags", "note", "id", "countInStats", "reimbursable"),
        listOf(java.time.LocalDate.now().toString(), "支出", "18.50", "现金", "餐饮", "午餐", "", "示例，导入前请删除或修改", "", "true", "false")))
    private fun flag(raw: String, default: Boolean): Boolean = when (raw.lowercase()) { "" -> default; "是", "true", "1", "yes" -> true; "否", "false", "0", "no" -> false; else -> error("布尔字段应为 true / false 或 是 / 否") }
    fun preview(s: LedgerState, table: CsvTable, mapping: Map<String, Int?>, defaultAccountId: String?): ImportPreview {
        require(listOf("date", "type", "amount").all { mapping[it] != null }) { "请映射日期、类型与金额列" }
        var entities = s.copy(transactions = emptyList())
        fun signature(e: Entry) = listOf(e.date, e.type, e.amountMinor.toString(), e.accountId, e.categoryId.orEmpty(), e.note)
        val signatures = s.transactions.map(::signature).toMutableSet()
        val sourceIds = s.transactions.mapNotNull { if (it.externalSource == "csv") it.externalId else null }.toMutableSet()
        val internalIds = s.transactions.map { it.id }.toSet()
        val rows = table.rows.mapIndexed { index, cells ->
            fun get(key: String) = mapping[key]?.let { cells.getOrNull(it) }?.trim().orEmpty()
            runCatching {
                require(cells.size == table.headers.size) { "列数与表头不一致" }
                require(get("schemaVersion").let { it.isBlank() || it == "5" }) { "不支持此 CSV 格式版本" }
                require(get("kind").let { it.isBlank() || it == "normal" }) { "特殊流水请使用完整备份恢复" }
                val type = when (get("type").lowercase()) { "收入", "income" -> "income"; "支出", "expense" -> "expense"; else -> error("仅支持普通收入或支出") }
                val amount = Money.parse(get("amount")); require(amount > 0) { "导入金额必须为正数" }
                val date = get("date"); val accountName = get("account")
                val matches = s.accounts.filter { it.name == accountName }
                require(accountName.isBlank() || matches.size <= 1) { "存在同名账户，请先改名" }
                val account = if (accountName.isBlank()) s.account(defaultAccountId) else matches.singleOrNull()
                require(account != null && !account.archived) { "账户不存在或已归档，请先新建 / 启用" }
                val categoryName = get("category").ifBlank { if (type == "expense") "其他支出" else "其他收入" }
                val matchedCats = entities.categories.filter { it.type == type && it.name == categoryName }
                require(matchedCats.size <= 1) { "存在同名分类，请先改名" }
                val category = matchedCats.singleOrNull() ?: Category(name = categoryName, type = type, sortOrder = entities.categories.size)
                require(!category.archived) { "分类已归档，请先启用" }
                if (entities.category(category.id) == null) entities = entities.copy(categories = entities.categories + category)
                val subName = get("subcategory")
                val sub = if (subName.isBlank()) null else entities.subcategories.find { it.categoryId == category.id && it.name == subName } ?: Subcategory(categoryId = category.id, name = subName, sortOrder = entities.subcategories.size)
                require(sub?.archived != true) { "子分类已归档，请先启用" }
                if (sub != null && entities.subcategory(sub.id) == null) entities = entities.copy(subcategories = entities.subcategories + sub)
                val tagIds = get("tags").split('|').filter { it.isNotBlank() }.map { name ->
                    val matchesTags = entities.tags.filter { it.name == name }; require(matchesTags.size <= 1) { "存在同名标签" }
                    val tag = matchesTags.singleOrNull() ?: Tag(name = name, sortOrder = entities.tags.size)
                    require(!tag.archived) { "标签「$name」已归档，请先启用" }
                    if (entities.tags.none { it.id == tag.id }) entities = entities.copy(tags = entities.tags + tag)
                    tag.id
                }.distinct()
                val id = get("id"); require(id.length <= 200) { "源流水 ID 超过 200 字" }
                val entry = Entry(type = type, amountMinor = amount, date = date, accountId = account.id, categoryId = category.id,
                    subcategoryId = sub?.id, tagIds = tagIds, note = get("note"), countInStats = flag(get("countInStats"), true),
                    reimbursable = flag(get("reimbursable"), false), externalSource = if (id.isBlank()) null else "csv", externalId = id.ifBlank { null })
                Ledger.validate(entities.copy(transactions = listOf(entry)))
                val exact = id.isNotBlank() && (id in sourceIds || id in internalIds)
                val suspected = !exact && signature(entry) in signatures
                signatures.add(signature(entry)); if (id.isNotBlank()) sourceIds.add(id)
                ImportRow(index, entry, exactDuplicate = exact, suspectedDuplicate = suspected, description = "$date · ${account.name} · $categoryName · ¥${Money.format(amount)}")
            }.getOrElse { ImportRow(index, error = it.message ?: "该行无效") }
        }
        return ImportPreview(s.meta.revision, rows, entities)
    }
    fun apply(s: LedgerState, preview: ImportPreview, selected: Set<Int>): LedgerState {
        require(s.meta.revision == preview.revision) { "预览已过期，请重新生成" }
        val rows = preview.rows.filter { it.index in selected }
        require(rows.isNotEmpty() && rows.size == selected.size && rows.all { it.error == null && !it.exactDuplicate && it.entry != null }) { "请选择有效且未重复的行" }
        val entries = rows.map { it.entry!! }
        val cats = entries.mapNotNull { it.categoryId }.toSet(); val subs = entries.mapNotNull { it.subcategoryId }.toSet(); val tags = entries.flatMap { it.tagIds }.toSet()
        return s.copy(categories = s.categories + preview.entities.categories.filter { it.id in cats && s.category(it.id) == null },
            subcategories = s.subcategories + preview.entities.subcategories.filter { it.id in subs && s.subcategory(it.id) == null },
            tags = s.tags + preview.entities.tags.filter { it.id in tags && s.tags.none { t -> t.id == it.id } }, transactions = s.transactions + entries).also { Ledger.validate(it) }
    }
}
