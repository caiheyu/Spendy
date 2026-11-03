package com.github.caiheyu.spendy

import com.github.caiheyu.spendy.domain.*
import org.junit.Assert.*
import org.junit.Test

class IconLibraryTest {
    private val image = LibraryIcon("喜欢的图标", "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aXpAAAAAASUVORK5CYII=")

    @Test fun removedBuiltInDisappearsFromChoicesAndCanBeRestored() {
        val ledger = Ledger.seed()
        val icon = BuiltInIcons.first { it.value == "utensils" }
        val library = IconLibrary().remove(icon)
        assertFalse(library.available(ledger).any { it.value == icon.value })
        assertEquals("utensils", ledger.category("c-food")?.icon)
        assertEquals(BuiltInIcons, library.restoreBuiltIns().available(ledger))
    }

    @Test fun legacyImagesAreReusableAndStayRemovedWhileStillInUse() {
        val ledger = Ledger.seed().let { it.copy(accounts = it.accounts.map { account -> account.copy(icon = image.value) },
            tags = it.tags.map { tag -> tag.copy(icon = image.value) }) }
        val icon = IconLibrary().available(ledger).single { it.value == image.value }
        val removed = IconLibrary().remove(icon)
        assertFalse(removed.available(ledger).any { it.value == image.value })
        assertEquals(image.value, ledger.accounts.single().icon)
        assertEquals(image.value, removed.add(image).available(ledger).single { it.value == image.value }.value)
    }

    @Test fun repeatedImportsDeduplicateAndCanRenameOrRestoreAnImage() {
        var library = IconLibrary().add(image).add(image.copy(name = "新名称"))
        assertEquals(1, library.customIcons.size)
        assertEquals("新名称", library.customIcons.single().name)
        library = library.remove(image)
        assertTrue(library.customIcons.isEmpty())
        assertFalse(library.restoreBuiltIns().available(Ledger.seed()).any { it.value == image.value })
        assertEquals(listOf(image), library.add(image).customIcons)
    }

    @Test fun scopesDefaultToAllAndCanLimitBuiltInAndUploadedIconsIndependently() {
        val ledger = Ledger.seed()
        val wallet = BuiltInIcons.first()
        val original = IconLibrary().add(image)
        IconScopes.forEach { (scope, _) -> assertEquals(original.available(ledger), original.available(ledger, scope)) }
        val library = original.withScopes(wallet, listOf("account"))
            .withScopes(image, listOf("tag", "category"))
        assertEquals(listOf("category", "tag"), library.scopesFor(image))
        assertTrue(library.available(ledger, "account").any { it.key == wallet.key })
        assertFalse(library.available(ledger, "account").any { it.key == image.key })
        listOf("category", "tag").forEach { scope ->
            assertFalse(library.available(ledger, scope).any { it.key == wallet.key })
            assertTrue(library.available(ledger, scope).any { it.key == image.key })
        }
        assertEquals(original.available(ledger), library.available(ledger))
    }

    @Test fun noScopesHidesChoicesWhileKeepingAssignedImagesAndManagementAccess() {
        val ledger = Ledger.seed().let { it.copy(accounts = it.accounts.map { account -> account.copy(icon = image.value) }) }
        val library = IconLibrary().withScopes(image, emptyList())
        assertTrue(library.available(ledger).any { it.key == image.key })
        IconScopes.forEach { (scope, _) -> assertFalse(library.available(ledger, scope).any { it.key == image.key }) }
        assertEquals(image.value, ledger.accounts.single().icon)
    }

    @Test fun renameRemovalAndReimportKeepVisibilityUntilExplicitlyReset() {
        val library = IconLibrary().add(image).withScopes(image, listOf("tag"))
        val restored = library.add(image.copy(name = "改名")).remove(image).add(image)
        assertEquals(listOf("tag"), restored.scopesFor(image))
        assertEquals(IconLibrary().add(image), restored.withScopes(image, listOf("tag", "account", "category")))
    }

    @Test fun invalidScopesCannotEnterTheLibrary() {
        assertThrows(IllegalArgumentException::class.java) { IconLibrary().withScopes(image, listOf("unknown")) }
        assertThrows(IllegalArgumentException::class.java) { IconLibrary().withScopes(image, listOf("tag", "tag")) }
        assertThrows(IllegalArgumentException::class.java) { IconLibrary(iconScopes = mapOf("unknown" to listOf("tag"))).validate() }
        assertThrows(IllegalArgumentException::class.java) { IconLibrary(iconScopes = mapOf(image.key to listOf("unknown"))).validate() }
    }

    @Test fun replacingIconArtPreservesIdentityNameScopeAndRemainsRemovable() {
        val wallet = BuiltInIcons.first()
        val library = IconLibrary().add(image).withScopes(wallet, listOf("account"))
        val replaced = library.replaceImage(wallet, image.value)
        assertEquals(image.value, replaced.imageFor(wallet.value))
        assertEquals("钱包", BuiltInIcons.first { it.key == wallet.key }.name)
        assertEquals(listOf("account"), replaced.scopesFor(wallet))
        assertEquals(image.value, replaced.available(Ledger.seed(), "account").first { it.key == wallet.key }.let { replaced.imageFor(it.value) })
        assertEquals(library, replaced.replaceImage(wallet, wallet.value))
        val removed = replaced.remove(wallet)
        assertFalse(removed.available(Ledger.seed()).any { it.key == wallet.key })
        assertEquals(image.value, removed.imageFor(wallet.value))
        assertEquals(replaced, removed.restoreBuiltIns())
    }

    @Test fun replacingCustomIconDoesNotDuplicateOrChangeItsRemovalIdentity() {
        val library = IconLibrary().add(image).withScopes(image, listOf("tag"))
        val replaced = library.replaceImage(image, BuiltInIcons.first().value)
        assertEquals(BuiltInIcons.first().value, replaced.imageFor(image.value))
        assertEquals("tag", replaced.scopesFor(image).single())
        assertEquals(1, replaced.customIcons.size)
        assertEquals(image.key, replaced.available(Ledger.seed()).single { it.name == image.name }.key)
        assertTrue(replaced.remove(image).hiddenIconIds.contains(image.key))
    }

    @Test fun invalidReplacementImagesAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { IconLibrary().replaceImage(BuiltInIcons.first(), "data:image/svg+xml;base64,AAAA") }
        assertThrows(IllegalArgumentException::class.java) { IconLibrary().replaceImage(BuiltInIcons.first(), "data:image/png;base64,AAAA") }
        assertThrows(IllegalArgumentException::class.java) {
            IconLibrary(imageOverrides = mapOf("unknown" to BuiltInIcons.first().value)).validate()
        }
    }

    @Test fun copyingAnotherIconsArtKeepsTheirFutureReplacementsIndependent() {
        val wallet = BuiltInIcons.first()
        val card = BuiltInIcons[1]
        val original = IconLibrary().withScopes(wallet, listOf("account")).withScopes(card, listOf("tag"))
            .replaceImage(wallet, "coffee")
        val copied = original.replaceImage(card, original.imageFor(wallet.value))
        val changed = copied.replaceImage(wallet, image.value)
        assertEquals("coffee", changed.imageFor(card.value))
        assertEquals(image.value, changed.imageFor(wallet.value))
        assertEquals(listOf("account"), changed.scopesFor(wallet))
        assertEquals(listOf("tag"), changed.scopesFor(card))
    }

    @Test fun invalidLibraryContentIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { IconLibrary().add(image.copy(value = "data:image/svg+xml;base64,AAAA")) }
        assertThrows(IllegalArgumentException::class.java) { IconLibrary().add(image.copy(value = "data:image/png;base64,AAAA")) }
        assertThrows(IllegalArgumentException::class.java) { IconLibrary().add(image.copy(name = "")) }
        assertThrows(IllegalArgumentException::class.java) { IconLibrary(hiddenIconIds = listOf("unknown")).validate() }
    }

    @Test fun libraryChangesMarkPreferencesAsUnbackedUp() {
        val original = AppSettings()
        val clean = original.copy(backedUpRevision = 10, backedUpPreferencesHash = original.preferencesHash())
        assertTrue(clean.isBackupCurrent(10))
        assertFalse(clean.copy(iconLibrary = IconLibrary().add(image)).isBackupCurrent(10))
        assertFalse(clean.copy(iconLibrary = IconLibrary().remove(BuiltInIcons.first())).isBackupCurrent(10))
        assertFalse(clean.copy(iconLibrary = IconLibrary().withScopes(BuiltInIcons.first(), listOf("account"))).isBackupCurrent(10))
        assertFalse(clean.copy(iconLibrary = IconLibrary().replaceImage(BuiltInIcons.first(), image.value)).isBackupCurrent(10))
        val oldHash = java.security.MessageDigest.getInstance("SHA-256").digest("system|#5560E9|false|true".toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        assertEquals(oldHash, original.preferencesHash())
    }
}
