package com.github.caiheyu.spendy.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import com.github.caiheyu.spendy.domain.LibraryIcon
import java.io.ByteArrayOutputStream

object IconImageImporter {
    fun read(context: Context, uri: Uri): LibraryIcon {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readLimited(5 * 1024 * 1024) } ?: error("无法读取图片")
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        require(options.outWidth > 0 && options.outHeight > 0) { "图片格式不支持，请选择 PNG、JPG 或 WebP 图片" }
        options.inJustDecodeBounds = false
        var sample = 1
        while (maxOf(options.outWidth, options.outHeight) / (sample * 2) >= 192) sample *= 2
        options.inSampleSize = sample
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: error("图片格式不支持")
        val square = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
        try {
            val scale = 96f / maxOf(bitmap.width, bitmap.height)
            val left = (96 - bitmap.width * scale) / 2
            val top = (96 - bitmap.height * scale) / 2
            Canvas(square).drawBitmap(bitmap, null, RectF(left, top, 96 - left, 96 - top), Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
            val output = ByteArrayOutputStream()
            square.compress(Bitmap.CompressFormat.PNG, 100, output)
            val displayName = runCatching {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
            }.getOrNull() ?: uri.lastPathSegment
            return LibraryIcon(displayName?.substringBeforeLast('.')?.take(60)?.ifBlank { null } ?: "自定义图标",
                "data:image/png;base64," + Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP))
        } finally {
            square.recycle()
            bitmap.recycle()
        }
    }
}
