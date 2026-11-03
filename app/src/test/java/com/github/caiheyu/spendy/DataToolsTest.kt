package com.github.caiheyu.spendy

import com.github.caiheyu.spendy.data.*
import com.github.caiheyu.spendy.domain.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class DataToolsTest {
    private fun base() = Ledger.seed().let { it.copy(accounts = it.accounts.map { a -> a.copy(openedOn = "2020-01-01") }) }
    private fun csv(rows: List<List<String>>) = Csv.parse(Csv.encode(listOf(listOf("date", "type", "amount", "account", "category", "tags", "note", "id")) + rows))
    private fun row(amount: String = "18.50", id: String = "", date: String = "2024-01-01", category: String = "餐饮", tags: String = "") = listOf(date, "支出", amount, "现金", category, tags, "中文,引号\"和\n换行", id)
    @Test fun csvEscapingRoundTrip() {
        val rows = listOf(listOf("中文", "逗号,引号\"", "换\r\n行"), listOf("a", "b", "c"))
        val parsed = Csv.parse(Csv.encode(rows)); assertEquals(rows.first(), parsed.headers); assertEquals(rows.drop(1), parsed.rows)
    }
    @Test fun invalidQuoteRejected() { assertThrows(IllegalArgumentException::class.java) { Csv.parse("a,b\n\"x,y") }; assertThrows(IllegalArgumentException::class.java) { Csv.parse("a,b\n\"x\"z,y") } }
    @Test fun previewFlagsInvalidDateAndPrecision() {
        val t = csv(listOf(row(date = "2024-02-30"), row(amount = "0.001"), row(amount = "-1")))
        val p = Csv.preview(base(), t, Csv.mapping(t), "a-cash"); assertTrue(p.rows.all { it.error != null && !it.selected })
    }
    @Test fun sameSourceIdCannotBeSelectedTwice() {
        val t = csv(listOf(row(id = "one"), row(id = "one")))
        val p = Csv.preview(base(), t, Csv.mapping(t), "a-cash"); assertTrue(p.rows[0].selected); assertTrue(p.rows[1].exactDuplicate)
        assertThrows(IllegalArgumentException::class.java) { Csv.apply(base(), p, setOf(1)) }
    }
    @Test fun suspectedDuplicatesAreNotPreselected() {
        val t = csv(listOf(row(), row())); val p = Csv.preview(base(), t, Csv.mapping(t), "a-cash")
        assertTrue(p.rows[1].suspectedDuplicate); assertFalse(p.rows[1].selected)
        assertEquals(2, Csv.apply(base(), p, setOf(0, 1)).transactions.size)
    }
    @Test fun selectedRowsOnlyCreateTheirEntities() {
        val t = csv(listOf(row(category = "我的分类", tags = "新标签"), row(category = "不选分类", tags = "不选标签")))
        val s = base(); val p = Csv.preview(s, t, Csv.mapping(t), "a-cash"); val imported = Csv.apply(s, p, setOf(0))
        assertTrue(imported.categories.any { it.name == "我的分类" }); assertFalse(imported.categories.any { it.name == "不选分类" }); assertFalse(imported.tags.any { it.name == "不选标签" })
    }
    @Test fun unknownAccountAndArchivedReferencesAreErrors() {
        val t = csv(listOf(row())); val s = base().copy(accounts = emptyList()); assertNotNull(Csv.preview(s, t, Csv.mapping(t), null).rows.first().error)
        val b = base(); val archived = b.copy(tags = b.tags.map { if (it.name == "工作") it.copy(archived = true) else it })
        val withTag = csv(listOf(row(tags = "工作"))); assertNotNull(Csv.preview(archived, withTag, Csv.mapping(withTag), "a-cash").rows.first().error)
    }
    @Test fun stalePreviewRejected() {
        val s = base(); val t = csv(listOf(row())); val p = Csv.preview(s, t, Csv.mapping(t), "a-cash")
        assertThrows(IllegalArgumentException::class.java) { Csv.apply(s.copy(meta = s.meta.copy(revision = 1)), p, setOf(0)) }
    }
    @Test fun specialEventsNotImportedAsIncome() {
        val t = Csv.parse("date,type,amount,account,kind\n2024-01-01,支出,10,现金,refund")
        assertNotNull(Csv.preview(base(), t, Csv.mapping(t), "a-cash").rows.first().error)
    }
    @Test fun plaintextAndEncryptedBackupsRoundTrip() {
        val b = Backup(data = base(), settings = AppSettings(theme = "dark"))
        assertEquals(b, BackupCodec.decode(BackupCodec.encode(b, null)))
        val password = "password-123".toCharArray(); val encrypted = BackupCodec.encode(b, password)
        assertTrue(BackupCodec.isEncrypted(encrypted)); assertEquals(b, BackupCodec.decode(encrypted, password))
    }
    @Test fun iconLibrarySurvivesPlaintextAndEncryptedBackups() {
        val icon = LibraryIcon("图片", "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aXpAAAAAASUVORK5CYII=")
        val library = IconLibrary().add(icon).remove(BuiltInIcons.first())
            .withScopes(icon, listOf("category", "tag")).withScopes(BuiltInIcons[1], listOf("account"))
            .replaceImage(BuiltInIcons[1], icon.value)
        val backup = Backup(data = base(), settings = AppSettings(iconLibrary = library))
        assertEquals(backup, BackupCodec.decode(BackupCodec.encode(backup, null)))
        val password = "password-123".toCharArray()
        assertEquals(backup, BackupCodec.decode(BackupCodec.encode(backup, password), password))
    }
    @Test fun backupsCreatedBeforeIconLibraryAreStillAccepted() {
        val backup = Backup(data = base(), settings = AppSettings())
        val tree = LedgerJson.parseToJsonElement(BackupCodec.encode(backup, null).toString(Charsets.UTF_8)).jsonObject
        val oldSettings = JsonObject(tree.getValue("settings").jsonObject - "iconLibrary")
        val oldBackup = JsonObject(tree + ("settings" to oldSettings))
        assertEquals(backup, BackupCodec.decode(oldBackup.toString().toByteArray()))
        val incomplete = JsonObject(tree + ("settings" to JsonObject(oldSettings - "theme")))
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.decode(incomplete.toString().toByteArray()) }
    }
    @Test fun backupsBeforeVisibilitySettingsDefaultToAllScopes() {
        val library = IconLibrary().remove(BuiltInIcons.first())
        val backup = Backup(data = base(), settings = AppSettings(iconLibrary = library))
        val tree = LedgerJson.parseToJsonElement(BackupCodec.encode(backup, null).toString(Charsets.UTF_8)).jsonObject
        val settings = tree.getValue("settings").jsonObject
        val oldLibrary = JsonObject(settings.getValue("iconLibrary").jsonObject - setOf("iconScopes", "imageOverrides"))
        val oldBackup = JsonObject(tree + ("settings" to JsonObject(settings + ("iconLibrary" to oldLibrary))))
        val decoded = BackupCodec.decode(oldBackup.toString().toByteArray())
        assertEquals(backup, decoded)
        IconScopes.forEach { (scope, _) -> assertEquals(library.available(base()), decoded.settings.iconLibrary.available(base(), scope)) }
    }
    @Test fun wrongPasswordDoesNotDecrypt() {
        val b = Backup(data = base(), settings = AppSettings()); val encrypted = BackupCodec.encode(b, "password-123".toCharArray())
        assertThrows(IllegalStateException::class.java) { BackupCodec.decode(encrypted, "wrong-password".toCharArray()) }
    }
    @Test fun encryptionUsesFreshSaltAndIv() {
        val b = Backup(data = base(), settings = AppSettings()); val p = "password-123".toCharArray()
        assertFalse(BackupCodec.encode(b, p).contentEquals(BackupCodec.encode(b, p)))
    }
    @Test fun incompleteAndUnknownVersionBackupsRejected() {
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.decode("{\"schemaVersion\":5,\"exportedAt\":\"2024-01-01T00:00:00Z\",\"data\":{},\"settings\":{}}".toByteArray()) }
        val b = Backup(data = base(), settings = AppSettings()); val raw = LedgerJson.encodeToString(b.copy(schemaVersion = 6)).toByteArray()
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.decode(raw) }
    }
    @Test fun encryptedEnvelopeRejectsMissingRequiredMetadata() {
        val backup = Backup(data = base(), settings = AppSettings())
        val encoded = BackupCodec.encode(backup, "password-123".toCharArray())
        val root = LedgerJson.parseToJsonElement(encoded.toString(Charsets.UTF_8)).jsonObject - "app"
        assertThrows(IllegalArgumentException::class.java) {
            BackupCodec.decode(LedgerJson.encodeToString(root).toByteArray(), "password-123".toCharArray())
        }
    }
    @Test fun backupStatusIncludesPreferencesAndCanMatchAfterRestore() {
        val settings = AppSettings(theme = "dark", hideAmounts = false)
        val clean = settings.copy(backedUpRevision = 12, backedUpPreferencesHash = settings.preferencesHash())
        assertTrue(clean.isBackupCurrent(12))
        assertFalse(clean.copy(theme = "light").isBackupCurrent(12))
        assertFalse(clean.isBackupCurrent(13))
        assertTrue(clean.copy(backedUpRevision = 13, backedUpPreferencesHash = settings.preferencesHash()).isBackupCurrent(13))
    }
    @Test fun restoreValidatesEveryReference() {
        val s = base(); val invalid = s.copy(transactions = listOf(Entry(amountMinor = 100, accountId = "missing", categoryId = "c-food")))
        val raw = LedgerJson.encodeToString(Backup(data = invalid, settings = AppSettings())).toByteArray()
        assertThrows(IllegalStateException::class.java) { BackupCodec.decode(raw) }
    }
    @Test fun updateManifestRequiresStrictDigestsAndUtc() {
        val good = UpdateManifest(1, "0.2.0", 2, 26, "a".repeat(64), "b".repeat(64), "2026-01-01T00:00:00Z", "更新")
        UpdateService.validate(good)
        assertThrows(IllegalArgumentException::class.java) { UpdateService.validate(good.copy(apkSha256 = "bad")) }
        assertThrows(IllegalArgumentException::class.java) { UpdateService.validate(good.copy(publishedAt = "2026-01-01")) }
    }
}
