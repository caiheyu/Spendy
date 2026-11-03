package com.github.caiheyu.spendy.domain

import kotlinx.serialization.Serializable
import java.security.MessageDigest

@Serializable
data class LibraryIcon(val name: String, val value: String) {
    val key: String get() = if (value.startsWith("data:")) "image:" + MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 0xff) } else value
}

val BuiltInIcons = listOf(
    "wallet" to "钱包", "card" to "银行卡", "money" to "现金", "utensils" to "餐饮",
    "coffee" to "咖啡", "subway" to "地铁", "bag" to "购物", "home" to "居住",
    "film" to "电影", "phone" to "通讯", "med" to "医疗", "book" to "书籍",
    "gift" to "礼物", "paw" to "宠物", "dumbbell" to "运动", "scissors" to "理发",
    "receipt" to "票据", "briefcase" to "工作", "trend" to "趋势", "award" to "奖励",
    "heart" to "爱心", "plane" to "旅行", "tag" to "标签", "star" to "星星",
    "cart" to "购物车", "package" to "包裹", "piggy" to "储蓄", "dots" to "其他",
).map { (value, name) -> LibraryIcon(name, value) }

val IconScopes = listOf("category" to "分类", "tag" to "标签", "account" to "账户")

@Serializable
data class IconLibrary(
    val customIcons: List<LibraryIcon> = emptyList(),
    val hiddenIconIds: List<String> = emptyList(),
    val iconScopes: Map<String, List<String>> = emptyMap(),
    val imageOverrides: Map<String, String> = emptyMap(),
) {
    // The original value identifies an icon. Replacing its image keeps all existing
    // assignments, names and visibility scopes attached to that same identity.
    fun imageFor(value: String): String = imageOverrides[LibraryIcon("", value).key] ?: value

    fun replaceImage(icon: LibraryIcon, image: String): IconLibrary {
        val updated = if (image == icon.value) imageOverrides - icon.key else imageOverrides + (icon.key to image)
        return copy(imageOverrides = updated.toSortedMap()).also { it.validate() }
    }

    fun scopesFor(icon: LibraryIcon): List<String> = iconScopes[icon.key] ?: IconScopes.map { it.first }

    fun withScopes(icon: LibraryIcon, scopes: List<String>): IconLibrary {
        require(scopes.distinct().size == scopes.size && scopes.all { scope -> IconScopes.any { it.first == scope } }) { "图标可见范围无效" }
        val ordered = IconScopes.map { it.first }.filter { it in scopes }
        val updated = if (ordered.size == IconScopes.size) iconScopes - icon.key else iconScopes + (icon.key to ordered)
        return copy(iconScopes = updated.toSortedMap()).also { it.validate() }
    }

    fun available(ledger: LedgerState, scope: String? = null): List<LibraryIcon> {
        require(scope == null || IconScopes.any { it.first == scope }) { "图标可见范围无效" }
        val usedImages = (ledger.accounts.map { it.name to it.icon } + ledger.categories.map { it.name to it.icon } +
            ledger.tags.map { it.name to it.icon }).filter { it.second.startsWith("data:image/png;base64,") }
            .map { (name, value) -> LibraryIcon(name.take(56) + "图标", value) }
        val hidden = hiddenIconIds.toSet()
        return (BuiltInIcons + customIcons + usedImages).distinctBy { it.key }
            .filter { it.key !in hidden && (scope == null || scope in scopesFor(it)) }
    }

    fun add(icon: LibraryIcon): IconLibrary = copy(
        customIcons = customIcons.filterNot { it.key == icon.key } + icon,
        hiddenIconIds = hiddenIconIds - icon.key,
    ).also { it.validate() }

    fun remove(icon: LibraryIcon): IconLibrary = copy(
        customIcons = customIcons.filterNot { it.key == icon.key },
        hiddenIconIds = (hiddenIconIds + icon.key).distinct().sorted(),
    ).also { it.validate() }

    fun restoreBuiltIns(): IconLibrary = copy(hiddenIconIds = hiddenIconIds - BuiltInIcons.map { it.key }.toSet())

    fun validate() {
        require(customIcons.size <= 200 && customIcons.map { it.key }.distinct().size == customIcons.size) { "自定义图标最多 200 个，且不能重复" }
        customIcons.forEach {
            require(it.name.isNotBlank() && it.name.length <= 60 && it.value.length < 100_000 &&
                it.value.startsWith("data:image/png;base64,")) { "自定义图标名称或图片格式无效" }
            validateImage(it.value)
        }
        val builtIns = BuiltInIcons.map { it.key }.toSet()
        fun validKey(key: String) = key in builtIns || Regex("image:[0-9a-f]{64}").matches(key)
        require(hiddenIconIds.size <= 5000 && hiddenIconIds.distinct().size == hiddenIconIds.size &&
            hiddenIconIds.all(::validKey)) { "图标库移除记录无效" }
        require(iconScopes.size <= 5000 && iconScopes.all { (key, scopes) -> validKey(key) &&
            scopes.distinct().size == scopes.size && scopes.all { scope -> IconScopes.any { it.first == scope } }
        }) { "图标可见范围无效" }
        require(imageOverrides.size <= 5000 && imageOverrides.keys.all(::validKey)) { "图标替换记录无效" }
        imageOverrides.values.forEach(::validateImage)
    }

    private fun validateImage(value: String) {
        if (BuiltInIcons.any { it.value == value }) return
        require(value.length < 100_000 && value.startsWith("data:image/png;base64,")) { "图标图片格式无效" }
        val bytes = runCatching { java.util.Base64.getDecoder().decode(value.substringAfter(',')) }.getOrNull()
        require(bytes != null && bytes.size >= 8 && bytes.take(8) == listOf(137, 80, 78, 71, 13, 10, 26, 10).map { b -> b.toByte() }) { "图标不是有效的 PNG 图片" }
    }
}
