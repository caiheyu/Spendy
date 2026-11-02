package com.github.caiheyu.spendy.domain

import java.time.LocalDate
import java.time.temporal.ChronoUnit

data class StatPoint(val id: String, val date: String, val type: String, val amount: Long,
    val categoryId: String?, val subcategoryId: String?, val accountId: String, val tagIds: List<String>)
data class Totals(val expense: Long = 0, val income: Long = 0) { val balance get() = income - expense }
data class EntryFilter(
    val query: String = "", val type: String? = null, val accountId: String? = null,
    val categoryId: String? = null, val subcategoryId: String? = null,
    val tagIds: Set<String> = emptySet(), val matchAllTags: Boolean = true,
    val min: Long? = null, val max: Long? = null,
    val start: String = "1900-01-01", val end: String = LocalDate.now().toString(),
    val excludedOnly: Boolean = false, val pendingOnly: Boolean = false,
    val statType: String? = null, val withoutSubcategory: Boolean = false,
)

object Ledger {
    private fun date(raw: String, today: LocalDate): LocalDate {
        val d = runCatching { LocalDate.parse(raw) }.getOrElse { error("日期格式无效") }
        require(d.toString() == raw && d >= LocalDate.of(1900, 1, 1) && d <= today) { "日期需介于 1900 年与今天之间" }
        return d
    }
    fun validate(s: LedgerState, today: LocalDate = LocalDate.now()) {
        require(s.meta.schemaVersion == 5) { "不支持此账本版本" }
        require(s.meta.revision >= 0 && s.meta.createdAt >= 0 && s.meta.updatedAt >= 0) { "账本元数据无效" }
        fun ids(values: List<String>) {
            require(values.all { it.isNotBlank() } && values.distinct().size == values.size) { "存在空 ID 或重复 ID" }
        }
        fun entity(name: String, order: Int, created: Long, updated: Long, color: String, icon: String) {
            require(name.isNotBlank() && name.length <= 60 && order >= 0 && created >= 0 && updated >= created) { "名称、排序或时间戳无效" }
            require(Regex("#[0-9a-fA-F]{6}").matches(color)) { "颜色格式无效" }
            require(icon.isNotBlank() && icon.length <= 100_000 && (!icon.startsWith("data:") || icon.startsWith("data:image/png;base64,"))) { "图标格式无效" }
        }
        ids(s.accounts.map { it.id }); ids(s.categories.map { it.id }); ids(s.subcategories.map { it.id })
        ids(s.tags.map { it.id }); ids(s.transactions.map { it.id })
        val accounts = s.accounts.associateBy { it.id }; val cats = s.categories.associateBy { it.id }
        val subs = s.subcategories.associateBy { it.id }; val tags = s.tags.associateBy { it.id }
        val entries = s.transactions.associateBy { it.id }
        s.accounts.forEach {
            date(it.openedOn, today); require(it.openingBalanceMinor in -Money.MAX..Money.MAX) { "期初余额超出范围" }
            entity(it.name, it.sortOrder, it.createdAt, it.updatedAt, it.color, it.icon)
        }
        s.categories.forEach {
            require(it.type in listOf("income", "expense")) { "分类类型无效" }
            entity(it.name, it.sortOrder, it.createdAt, it.updatedAt, it.color, it.icon)
        }
        s.tags.forEach { entity(it.name, it.sortOrder, it.createdAt, it.updatedAt, it.color, it.icon) }
        s.subcategories.forEach {
            require(cats.containsKey(it.categoryId) && it.name.isNotBlank() && it.name.length <= 60 && it.sortOrder >= 0 && it.createdAt >= 0 && it.updatedAt >= it.createdAt) { "子分类无效" }
        }
        val sourceIds = mutableSetOf<Pair<String, String>>()
        s.transactions.forEach { e ->
            date(e.date, today)
            require(e.amountMinor in -Money.MAX..Money.MAX && e.amountMinor != 0L && e.feeMinor in 0..Money.MAX) { "流水金额无效" }
            val account = accounts[e.accountId] ?: error("账户引用不存在")
            require(e.date >= account.openedOn) { "流水早于账户期初生效日" }
            require(e.createdAt >= 0 && e.updatedAt >= e.createdAt && e.note.length <= 1000) { "流水时间戳或备注无效" }
            require(e.tagIds.distinct().size == e.tagIds.size && e.tagIds.all { tags.containsKey(it) }) { "标签引用无效" }
            require((e.externalId == null) == (e.externalSource == null)) { "外部来源字段不完整" }
            e.externalId?.let { require(it.isNotBlank() && !e.externalSource.isNullOrBlank() && sourceIds.add(e.externalSource!! to it)) { "来源 ID 重复" } }
            require(e.type in listOf("expense", "income", "transfer", "adjustment") && e.kind in listOf("normal", "refund", "reimbursement")) { "流水类型无效" }
            require(!e.reimbursable || e.type == "expense" && e.kind == "normal") { "只有普通支出可标记报销" }
            if (e.type in listOf("income", "expense")) {
                require(cats[e.categoryId]?.type == e.type) { "分类与收支类型不一致" }
                require(e.subcategoryId == null || subs[e.subcategoryId]?.categoryId == e.categoryId) { "子分类引用无效" }
                require(e.toAccountId == null && e.feeMinor == 0L && e.feeCategoryId == null) { "普通收支含转账字段" }
                if (e.kind == "normal") {
                    require(e.amountMinor > 0 && e.originalId == null) { "普通收支金额必须为正数" }
                } else {
                    val original = entries[e.originalId] ?: error("关联原消费不存在")
                    require(e.type == "expense" && e.amountMinor < 0 && original.type == "expense" && original.kind == "normal") { "退款 / 报销关系无效" }
                    require(e.date >= original.date && (e.kind != "reimbursement" || original.reimbursable)) { "到账日期或报销资格无效" }
                    require(e.categoryId == original.categoryId && e.subcategoryId == original.subcategoryId && e.tagIds.toSet() == original.tagIds.toSet() && e.countInStats == original.countInStats) { "关联记录需继承原单分类、标签及统计设置" }
                }
            } else {
                require(e.kind == "normal" && e.originalId == null && e.categoryId == null && e.subcategoryId == null && e.tagIds.isEmpty() && !e.reimbursable) { "特殊流水字段无效" }
                if (e.type == "transfer") {
                    val target = accounts[e.toAccountId] ?: error("请选择转入账户")
                    require(e.amountMinor > 0 && e.toAccountId != e.accountId && e.date >= target.openedOn) { "转账账户、金额或日期无效" }
                    require(if (e.feeMinor > 0) cats[e.feeCategoryId]?.type == "expense" else e.feeCategoryId == null) { "手续费分类无效" }
                } else require(e.toAccountId == null && e.feeMinor == 0L && e.feeCategoryId == null) { "余额调整含转账字段" }
            }
        }
        s.transactions.filter { it.originalId != null }.groupBy { it.originalId }.forEach { (id, linked) ->
            require(linked.fold(0L) { a, e -> Math.addExact(a, -e.amountMinor) } <= entries.getValue(id!!).amountMinor) { "退款与报销合计超过原消费" }
        }
        // Detect arithmetic overflow before a state can be committed or restored.
        s.accounts.forEach { balance(s, it.id, today.toString()) }
        val points = project(s)
        points.groupBy { it.type }.values.forEach { values -> values.fold(0L) { a, p -> Math.addExact(a, p.amount) } }
    }

    fun project(s: LedgerState): List<StatPoint> = s.transactions.flatMap { e ->
        when (e.type) {
            "income", "expense" -> if (e.countInStats) listOf(StatPoint(e.id, e.date, e.type, e.amountMinor, e.categoryId, e.subcategoryId, e.accountId, e.tagIds)) else emptyList()
            "transfer" -> if (e.feeMinor > 0) listOf(StatPoint(e.id, e.date, "expense", e.feeMinor, e.feeCategoryId, null, e.accountId, emptyList())) else emptyList()
            else -> emptyList()
        }
    }
    fun totals(s: LedgerState, start: String, end: String): Totals {
        val p = project(s).filter { it.date in start..end }
        return Totals(p.filter { it.type == "expense" }.sumOf { it.amount }, p.filter { it.type == "income" }.sumOf { it.amount })
    }
    fun movement(e: Entry, accountId: String): Long = when (e.type) {
        "transfer" -> when (accountId) { e.accountId -> -e.amountMinor - e.feeMinor; e.toAccountId -> e.amountMinor; else -> 0 }
        "expense" -> if (e.accountId == accountId) -e.amountMinor else 0
        else -> if (e.accountId == accountId) e.amountMinor else 0
    }
    fun balance(s: LedgerState, accountId: String, date: String = LocalDate.now().toString()): Long {
        val a = s.account(accountId) ?: return 0
        if (date < a.openedOn) return 0
        return s.transactions.filter { it.date <= date }.fold(a.openingBalanceMinor) { sum, e -> Math.addExact(sum, movement(e, accountId)) }
    }
    fun net(s: LedgerState, date: String = LocalDate.now().toString()): Long = s.accounts.fold(0L) { sum, a -> Math.addExact(sum, balance(s, a.id, date)) }
    fun recovered(s: LedgerState, id: String): Long = -s.transactions.filter { it.originalId == id }.sumOf { it.amountMinor }
    fun pending(s: LedgerState, e: Entry): Long = if (e.type == "expense" && e.kind == "normal" && e.reimbursable) e.amountMinor - recovered(s, e.id) else 0

    fun filter(s: LedgerState, f: EntryFilter): List<Entry> {
        val statisticalIds = f.statType?.let { type -> project(s).filter { it.type == type }.map { it.id }.toSet() }
        return s.transactions.filter { e ->
        val text = listOf(e.note, s.category(e.categoryId)?.name, s.subcategory(e.subcategoryId)?.name,
            s.account(e.accountId)?.name, s.account(e.toAccountId)?.name,
            s.tags.filter { it.id in e.tagIds }.joinToString { it.name }).joinToString(" ")
        e.date in f.start..f.end && (f.query.isBlank() || text.contains(f.query.trim(), true)) &&
            (f.type == null || f.type == e.type || f.type == e.kind) &&
            (f.accountId == null || f.accountId == e.accountId || f.accountId == e.toAccountId) &&
            (f.categoryId == null || f.categoryId == e.categoryId || f.categoryId == e.feeCategoryId) &&
            (f.subcategoryId == null || f.subcategoryId == e.subcategoryId) &&
            (!f.withoutSubcategory || e.subcategoryId == null) &&
            (statisticalIds == null || e.id in statisticalIds) &&
            (f.tagIds.isEmpty() || if (f.matchAllTags) e.tagIds.containsAll(f.tagIds) else e.tagIds.any { it in f.tagIds }) &&
            (f.min == null || kotlin.math.abs(e.amountMinor) >= f.min) &&
            (f.max == null || kotlin.math.abs(e.amountMinor) <= f.max) &&
            (!f.excludedOnly || !e.countInStats && e.type in listOf("expense", "income")) && (!f.pendingOnly || pending(s, e) > 0)
    }.sortedWith(compareByDescending<Entry> { it.date }.thenByDescending { it.createdAt }.thenBy { it.id })
    }

    fun save(s: LedgerState, input: Entry): LedgerState {
        val old = s.entry(input.id)
        var e = input.copy(createdAt = old?.createdAt ?: input.createdAt, updatedAt = now(),
            externalId = old?.externalId ?: input.externalId, externalSource = old?.externalSource ?: input.externalSource)
        if (e.originalId != null) {
            val original = s.entry(e.originalId) ?: error("原消费已不存在")
            e = e.copy(categoryId = original.categoryId, subcategoryId = original.subcategoryId, tagIds = original.tagIds, countInStats = original.countInStats)
        }
        fun available(archived: Boolean, preserved: Boolean) { require(!archived || preserved) { "不能为新账单选择已归档项目" } }
        s.account(e.accountId)?.let { available(it.archived, old?.accountId == it.id) }
        s.account(e.toAccountId)?.let { available(it.archived, old?.toAccountId == it.id) }
        if (e.kind == "normal") {
            s.category(e.categoryId)?.let { available(it.archived, old?.categoryId == it.id) }
            s.subcategory(e.subcategoryId)?.let { available(it.archived, old?.subcategoryId == it.id) }
            s.tags.filter { it.id in e.tagIds }.forEach { available(it.archived, old?.tagIds?.contains(it.id) == true) }
        }
        val replaced = s.transactions.filterNot { it.id == e.id }.map {
            if (it.originalId == e.id) it.copy(categoryId = e.categoryId, subcategoryId = e.subcategoryId,
                tagIds = e.tagIds, countInStats = e.countInStats, updatedAt = now()) else it
        } + e
        return s.copy(transactions = replaced).also { validate(it) }
    }
    fun delete(s: LedgerState, ids: Set<String>): LedgerState = s.copy(transactions = s.transactions.filterNot { it.id in ids || it.originalId in ids })

    fun seed(): LedgerState {
        val specs = listOf(
            listOf("food", "餐饮", "utensils", "#FF7A59", "早餐,午餐,晚餐,聚餐,零食,饮料,水果,咖啡"),
            listOf("trans", "交通", "subway", "#4C9AFF", "公交,地铁,打车,火车,飞机,加油,电车充电,停车"),
            listOf("shop", "购物", "bag", "#B074E8", "数码,衣服,日用"),
            listOf("house", "居住", "home", "#F5B84C", "房租,房贷,水电,燃气,物业,酒店"),
            listOf("fun", "娱乐", "film", "#F06292", "电影,游戏,音乐,演出,门票"),
            listOf("comm", "通讯", "phone", "#26A69A", "话费,宽带"),
            listOf("med", "医疗", "med", "#EF5350", "门诊,药品,体检"),
            listOf("edu", "教育", "book", "#7E57C2", "书籍,课程,培训"),
            listOf("social", "人情", "gift", "#EC5A9E", "红包,礼金,礼物"),
            listOf("pet", "宠物", "paw", "#F5A623", "粮食,医疗,用品"),
            listOf("fitness", "运动", "dumbbell", "#0E9F8A", "健身,装备,场馆"),
            listOf("beauty", "个护", "scissors", "#B074E8", "理发,护肤,洗护"),
            listOf("fee", "手续费", "receipt", "#8A8D9F", ""),
            listOf("other-expense", "其他支出", "dots", "#8A8D9F", ""),
            listOf("salary", "工资", "money", "#1FA97A", ""),
            listOf("invest", "理财", "trend", "#F5A623", "利息,基金,股票"),
            listOf("part", "兼职", "briefcase", "#5560E9", ""),
            listOf("bonus", "奖金", "award", "#F5A623", ""),
            listOf("redpacket", "红包", "gift", "#F0564A", ""),
            listOf("other-income", "其他收入", "dots", "#8A8D9F", ""),
        )
        val tagNames = listOf("网购", "超市", "外卖", "订阅", "工作", "家庭", "情侣", "旅行")
        val tagIds = listOf("online", "supermarket", "takeout", "subscription", "work", "family", "couple", "travel")
        val icons = listOf("bag", "cart", "package", "refresh", "briefcase", "home", "heart", "plane")
        val colors = listOf("#B074E8", "#1FA97A", "#FF7A59", "#29B6F6", "#5560E9", "#0E9F8A", "#EC5A9E", "#F5A623")
        return LedgerState(
            accounts = listOf(Account(id = "a-cash", name = "现金", icon = "money", color = "#F5A623")),
            categories = specs.mapIndexed { i, v -> Category(id = "c-${v[0]}", name = v[1], type = if (i < 14) "expense" else "income", icon = v[2], color = v[3], sortOrder = i) },
            subcategories = specs.flatMap { v -> v[4].split(',').filter { it.isNotBlank() }.mapIndexed { i, n -> Subcategory(id = "s-${v[0]}-$i", categoryId = "c-${v[0]}", name = n, sortOrder = i) } },
            tags = tagNames.mapIndexed { i, n -> Tag(id = "t-${tagIds[i]}", name = n, icon = icons[i], color = colors[i], sortOrder = i) },
        )
    }
}
