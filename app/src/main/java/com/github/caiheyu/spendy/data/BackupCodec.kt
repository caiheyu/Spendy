package com.github.caiheyu.spendy.data

import com.github.caiheyu.spendy.domain.Ledger
import kotlinx.serialization.Serializable
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.*
import java.io.InputStream
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

object BackupCodec {
    const val MAX_BYTES = 30 * 1024 * 1024
    @Serializable private data class Kdf(val algo: String = "PBKDF2", val hash: String = "SHA-256", val iterations: Int = 210_000, val salt: String)
    @Serializable private data class Encryption(val algo: String = "AES-256-GCM", val iv: String)
    @Serializable private data class Envelope(val app: String = "spendy-backup", val version: Int = 2,
        val encrypted: Boolean = true, val kdf: Kdf, val cipher: Encryption, val data: String)
    fun isEncrypted(bytes: ByteArray): Boolean = LedgerJson.parseToJsonElement(bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF")).jsonObject["encrypted"]?.jsonPrimitive?.booleanOrNull == true
    private fun required(value: JsonObject, serializer: KSerializer<*>, optionalFields: Set<String> = emptySet()) {
        val d = serializer.descriptor
        require((0 until d.elementsCount).all { d.getElementName(it) in optionalFields || value.containsKey(d.getElementName(it)) }) { "备份缺少必要字段：${d.serialName}" }
    }
    fun encode(backup: Backup, password: CharArray?): ByteArray {
        validate(backup)
        val plain = LedgerJson.encodeToString(backup).toByteArray(Charsets.UTF_8)
        require(plain.size <= 20 * 1024 * 1024) { "账本过大，无法生成备份" }
        if (password == null) return plain
        require(password.size >= 8) { "备份密码至少 8 个字符" }
        val salt = ByteArray(16).also(SecureRandom()::nextBytes)
        val iv = ByteArray(12).also(SecureRandom()::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val key = derive(password, salt, 210_000)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        return try {
            val encrypted = cipher.doFinal(plain)
            LedgerJson.encodeToString(Envelope(kdf = Kdf(salt = b64(salt)), cipher = Encryption(iv = b64(iv)), data = b64(encrypted))).toByteArray(Charsets.UTF_8)
        } finally { plain.fill(0) }
    }
    fun decode(bytes: ByteArray, password: CharArray? = null): Backup {
        require(bytes.size <= MAX_BYTES) { "备份文件不能超过 30 MB" }
        val raw = bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF")
        val obj = runCatching { LedgerJson.parseToJsonElement(raw).jsonObject }.getOrElse { error("备份文件不是有效 JSON") }
        val plain = if (obj["encrypted"]?.jsonPrimitive?.booleanOrNull == true) {
            required(obj, Envelope.serializer())
            required(obj.getValue("kdf").jsonObject, Kdf.serializer())
            required(obj.getValue("cipher").jsonObject, Encryption.serializer())
            val env = LedgerJson.decodeFromString<Envelope>(raw)
            require(env.app == "spendy-backup" && env.version == 2 && env.kdf.algo == "PBKDF2" && env.kdf.hash == "SHA-256" && env.cipher.algo == "AES-256-GCM" && env.kdf.iterations in 10_000..2_000_000) { "不支持或损坏的加密备份格式" }
            require(password != null && password.isNotEmpty()) { "请输入备份密码" }
            val salt = Base64.getDecoder().decode(env.kdf.salt); val iv = Base64.getDecoder().decode(env.cipher.iv)
            val data = Base64.getDecoder().decode(env.data)
            require(salt.size == 16 && iv.size == 12 && data.size >= 16 && data.size <= 21 * 1024 * 1024) { "加密备份参数无效" }
            try {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, derive(password, salt, env.kdf.iterations), GCMParameterSpec(128, iv))
                cipher.doFinal(data).toString(Charsets.UTF_8)
            } catch (_: javax.crypto.AEADBadTagException) { error("密码错误或文件已损坏") }
        } else raw
        require(plain.length <= 20 * 1024 * 1024) { "备份内容过大" }
        val tree = LedgerJson.parseToJsonElement(plain).jsonObject
        required(tree, Backup.serializer())
        val data = tree.getValue("data").jsonObject
        required(data, com.github.caiheyu.spendy.domain.LedgerState.serializer())
        required(data.getValue("meta").jsonObject, com.github.caiheyu.spendy.domain.LedgerMeta.serializer())
        required(tree.getValue("settings").jsonObject, com.github.caiheyu.spendy.domain.AppSettings.serializer(), setOf("iconLibrary"))
        mapOf("accounts" to com.github.caiheyu.spendy.domain.Account.serializer(),
            "categories" to com.github.caiheyu.spendy.domain.Category.serializer(),
            "subcategories" to com.github.caiheyu.spendy.domain.Subcategory.serializer(),
            "tags" to com.github.caiheyu.spendy.domain.Tag.serializer(),
            "transactions" to com.github.caiheyu.spendy.domain.Entry.serializer()).forEach { (key, serializer) ->
            val list = data.getValue(key).jsonArray
            require(list.size <= 100_000) { "备份实体数量超过限制" }
            list.forEach { required(it.jsonObject, serializer) }
        }
        val backup = LedgerJson.decodeFromString<Backup>(plain)
        validate(backup)
        return backup
    }
    fun validate(backup: Backup) {
        require(backup.schemaVersion == 5) { "此版本仅支持 Android schema v5 备份" }
        require(backup.exportedAt.endsWith("Z")) { "备份导出时间必须为 UTC" }
        require(Instant.parse(backup.exportedAt).toEpochMilli() >= 0) { "备份导出时间无效" }
        Ledger.validate(backup.data)
        LedgerRepository.validateSettings(backup.settings)
    }
    private fun b64(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes)
    private fun derive(password: CharArray, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(password, salt, iterations, 256)
        return try { val key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            try { SecretKeySpec(key, "AES") } finally { key.fill(0) }
        } finally { spec.clearPassword() }
    }
}

fun InputStream.readLimited(limit: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        require(output.size().toLong() + count <= limit) { "文件大小超过限制" }
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}
