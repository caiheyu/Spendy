package com.github.caiheyu.spendy.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.FileProvider
import com.github.caiheyu.spendy.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class UpdateManifest(val schemaVersion: Int, val versionName: String, val versionCode: Long,
    val minSdk: Int, val apkSha256: String, val signerCertificateSha256: String,
    val publishedAt: String, val releaseNotes: String)

@Singleton
class UpdateService @Inject constructor(@ApplicationContext private val context: Context) {
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).callTimeout(3, TimeUnit.MINUTES).build()
    private val allowedHosts = setOf("github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com")
    private val base = "https://github.com/caiheyu/Spendy/releases/latest/download/"
    private fun response(url: String): okhttp3.Response {
        var current = url
        repeat(4) { hop ->
            val request = Request.Builder().url(current).header("User-Agent", "Spendy/${BuildConfig.VERSION_NAME}").build()
            require(request.url.scheme == "https" && request.url.host in allowedHosts && request.url.username.isEmpty() && request.url.password.isEmpty()) { "更新链接不在 GitHub 白名单内" }
            val r = client.newCall(request).execute()
            if (r.code in listOf(301, 302, 303, 307, 308)) {
                val location = r.header("Location"); val next = location?.let { r.request.url.resolve(it) }; r.close()
                require(hop < 3 && next != null) { "更新重定向次数过多或链接无效" }
                current = next.toString()
            } else return r
        }
        error("更新重定向次数过多")
    }
    fun check(): UpdateManifest? = response(base + "update.json").use { r ->
        if (r.code == 404) error("GitHub 暂无可用的 update.json 发布清单")
        require(r.isSuccessful) { "检查更新失败（HTTP ${r.code}）" }
        val body = r.body ?: error("更新清单为空")
        require(body.contentLength() <= 65_536) { "更新清单过大" }
        val manifest = LedgerJson.decodeFromString<UpdateManifest>(body.byteStream().readLimited(65_536).toString(Charsets.UTF_8))
        validate(manifest)
        require(manifest.minSdk <= Build.VERSION.SDK_INT) { "新版本需要 Android API ${manifest.minSdk}，当前设备不兼容" }
        manifest.takeIf { it.versionCode > BuildConfig.VERSION_CODE }
    }
    fun download(manifest: UpdateManifest): File {
        validate(manifest)
        require(manifest.versionCode > BuildConfig.VERSION_CODE && manifest.minSdk <= Build.VERSION.SDK_INT) { "更新版本或设备兼容性无效" }
        val directory = File(context.cacheDir, "updates").apply { mkdirs() }
        val part = File(directory, "spendy.apk.part"); val final = File(directory, "spendy.apk")
        try {
            response(base + "spendy.apk").use { r ->
                require(r.isSuccessful) { "下载失败（HTTP ${r.code}）" }
                val body = r.body ?: error("下载文件为空")
                require(body.contentLength() <= 150 * 1024 * 1024) { "安装包超过 150 MB" }
                body.byteStream().use { input -> part.outputStream().use { output ->
                    val buffer = ByteArray(16_384); var total = 0L
                    while (true) { val count = input.read(buffer); if (count < 0) break; total += count; require(total <= 150L * 1024 * 1024) { "安装包超过 150 MB" }; output.write(buffer, 0, count) }
                    require(total > 0) { "安装包为空" }; output.fd.sync()
                } }
            }
            val digest = MessageDigest.getInstance("SHA-256")
            part.inputStream().use { input -> val buffer = ByteArray(8192); while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) } }
            require(hex(digest.digest()) == manifest.apkSha256) { "安装包 SHA-256 校验失败" }
            val pm = context.packageManager
            @Suppress("DEPRECATION") val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
            @Suppress("DEPRECATION") val archive = pm.getPackageArchiveInfo(part.absolutePath, flags) ?: error("安装包无效")
            @Suppress("DEPRECATION") val current = pm.getPackageInfo(context.packageName, flags)
            require(archive.packageName == context.packageName) { "安装包包名不匹配" }
            @Suppress("DEPRECATION") val version = if (Build.VERSION.SDK_INT >= 28) archive.longVersionCode else archive.versionCode.toLong()
            require(version == manifest.versionCode && archive.versionName == manifest.versionName) { "安装包版本与清单不一致" }
            require((archive.applicationInfo?.minSdkVersion ?: Int.MAX_VALUE) <= Build.VERSION.SDK_INT) { "安装包与设备不兼容" }
            @Suppress("DEPRECATION") fun signers(info: android.content.pm.PackageInfo): Set<String> =
                (if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures).orEmpty().map { hex(MessageDigest.getInstance("SHA-256").digest(it.toByteArray())) }.toSet()
            val installed = signers(current); val downloaded = signers(archive)
            require(downloaded.size == 1 && downloaded == installed && manifest.signerCertificateSha256 in downloaded) { "签名证书不匹配，已拒绝安装" }
            if (final.exists()) require(final.delete()) { "无法替换旧安装包" }
            require(part.renameTo(final)) { "无法保存已校验安装包" }
            return final
        } catch (e: Exception) { part.delete(); throw e }
    }
    fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()
    fun installIntent(file: File): Intent = Intent(Intent.ACTION_VIEW).setDataAndType(
        FileProvider.getUriForFile(context, "${context.packageName}.files", file), "application/vnd.android.package-archive")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    companion object {
        fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
        fun validate(m: UpdateManifest) {
            require(m.schemaVersion == 1 && m.versionCode in 1..Int.MAX_VALUE.toLong() && m.versionName.isNotBlank() && m.versionName.length <= 80 && m.minSdk in 26..100) { "更新清单版本字段无效" }
            require(Regex("[0-9a-f]{64}").matches(m.apkSha256) && Regex("[0-9a-f]{64}").matches(m.signerCertificateSha256)) { "更新清单摘要字段无效" }
            require(Regex("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?Z").matches(m.publishedAt) && Instant.parse(m.publishedAt).toEpochMilli() >= 0 && m.releaseNotes.length <= 4000) { "更新清单时间或说明无效" }
        }
    }
}
