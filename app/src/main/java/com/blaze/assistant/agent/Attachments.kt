package com.blaze.assistant.agent

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/** Turns a picked photo, PDF or text file into content the model can read. */
object Attachments {
    private const val MAX_PDF_PAGES = 6

    suspend fun load(ctx: Context, uri: Uri): Attachment? = withContext(Dispatchers.IO) {
        try {
            val cr = ctx.contentResolver
            val mime = cr.getType(uri) ?: ""
            val name = cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            } ?: uri.lastPathSegment ?: "file"
            when {
                mime.startsWith("image/") -> {
                    val bmp = decodeScaled(ctx, uri, 1536) ?: return@withContext null
                    Attachment(name, listOf(image(bmp)))
                }
                mime == "application/pdf" -> {
                    val parts = mutableListOf<JSONObject>()
                    cr.openFileDescriptor(uri, "r")?.use { fd ->
                        PdfRenderer(fd).use { pdf ->
                            val n = minOf(pdf.pageCount, MAX_PDF_PAGES)
                            if (pdf.pageCount > n) parts += text("(PDF has ${pdf.pageCount} pages; showing the first $n.)")
                            for (i in 0 until n) pdf.openPage(i).use { page ->
                                val w = 1100; val h = (w.toFloat() * page.height / page.width).toInt()
                                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                                bmp.eraseColor(Color.WHITE)
                                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                parts += image(bmp, 75)
                            }
                        }
                    }
                    Attachment(name, parts)
                }
                mime.startsWith("text/") || name.endsWith(".txt") || name.endsWith(".md") || name.endsWith(".csv") || name.endsWith(".json") -> {
                    val body = cr.openInputStream(uri)?.bufferedReader()?.use { it.readText() }.orEmpty().take(60_000)
                    Attachment(name, listOf(text("File $name:\n$body")))
                }
                else -> null
            }
        } catch (_: Exception) { null }
    }

    private fun decodeScaled(ctx: Context, uri: Uri, max: Int): Bitmap? {
        val cr = ctx.contentResolver
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
        var sample = 1
        while (maxOf(o.outWidth, o.outHeight) / (sample * 2) >= max) sample *= 2
        val bmp = cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) } ?: return null
        val s = minOf(1f, max.toFloat() / maxOf(bmp.width, bmp.height))
        return if (s < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * s).toInt(), (bmp.height * s).toInt(), true) else bmp
    }

    private fun image(bmp: Bitmap, q: Int = 82): JSONObject {
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, q, out)
        val url = "data:image/jpeg;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        return JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", url))
    }

    private fun text(t: String) = JSONObject().put("type", "text").put("text", t)
}
