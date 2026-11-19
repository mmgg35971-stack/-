package com.mangatranslator.features.translate

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import com.mangatranslator.core.database.DraftPageEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Copies picked images / PDF pages into private app storage so the draft
 * survives app restarts and revoked URI permissions. Pages are processed one
 * at a time to keep memory low on mid-range phones. Originals are kept
 * byte-for-byte when the format is already supported.
 */
@Singleton
class PageImporter @Inject constructor(@ApplicationContext private val context: Context) {

    data class ImportResult(val pages: List<DraftPageEntity>, val skipped: Int)

    fun draftDir(draftId: String): File = File(context.filesDir, "drafts/$draftId").apply { mkdirs() }

    suspend fun importImages(draftId: String, uris: List<Uri>, startPosition: Int): ImportResult = withContext(Dispatchers.IO) {
        val out = mutableListOf<DraftPageEntity>()
        var skipped = 0
        uris.forEachIndexed { i, uri ->
            val page = runCatching { importImage(draftId, uri, startPosition + i) }.getOrNull()
            if (page != null) out += page else skipped++
        }
        ImportResult(out.mapIndexed { i, p -> p.copy(position = startPosition + i) }, skipped)
    }

    suspend fun importPdf(draftId: String, uri: Uri, startPosition: Int): ImportResult = withContext(Dispatchers.IO) {
        val out = mutableListOf<DraftPageEntity>()
        val name = displayName(uri) ?: "document.pdf"
        val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: return@withContext ImportResult(emptyList(), 1)
        pfd.use { fd ->
            PdfRenderer(fd).use { renderer ->
                for (i in 0 until renderer.pageCount) {
                    renderer.openPage(i).use { page ->
                        // Render at ~1600px width: sharp enough for OCR, small enough to upload.
                        val scale = (MAX_RENDER_WIDTH.toFloat() / page.width).coerceIn(1f, 4f)
                        val w = (page.width * scale).toInt()
                        val h = (page.height * scale).toInt().coerceAtMost(MAX_RENDER_HEIGHT)
                        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(Color.WHITE)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        val file = File(draftDir(draftId), "${UUID.randomUUID()}.jpg")
                        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                        bmp.recycle()
                        out += DraftPageEntity(draftId, startPosition + i, file.absolutePath, "$name #${i + 1}", "image/jpeg", file.length())
                    }
                }
            }
        }
        ImportResult(out, 0)
    }

    private fun importImage(draftId: String, uri: Uri, position: Int): DraftPageEntity? {
        val resolver = context.contentResolver
        val mime = resolver.getType(uri) ?: guessMime(uri)
        val name = displayName(uri) ?: uri.lastPathSegment ?: "page"
        val dir = draftDir(draftId)

        if (mime in SUPPORTED) {
            val ext = when (mime) { "image/png" -> "png"; "image/webp" -> "webp"; else -> "jpg" }
            val file = File(dir, "${UUID.randomUUID()}.$ext")
            resolver.openInputStream(uri)?.use { input -> file.outputStream().use { input.copyTo(it) } } ?: return null
            if (file.length() in 1..MAX_BYTES && isDecodable(file)) {
                return DraftPageEntity(draftId, position, file.absolutePath, name, mime, file.length())
            }
            file.delete()
        }
        // Unsupported format (HEIC, GIF, ...) or too large: re-encode as JPEG.
        val bmp = decodeScaled(uri) ?: return null
        val file = File(dir, "${UUID.randomUUID()}.jpg")
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        bmp.recycle()
        return DraftPageEntity(draftId, position, file.absolutePath, name, "image/jpeg", file.length())
    }

    private fun isDecodable(file: File): Boolean {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, o)
        return o.outWidth > 0 && o.outHeight > 0
    }

    private fun decodeScaled(uri: Uri): Bitmap? = runCatching {
        if (Build.VERSION.SDK_INT >= 28) {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
                val w = info.size.width
                if (w > MAX_REENCODE_WIDTH) {
                    val ratio = MAX_REENCODE_WIDTH.toFloat() / w
                    decoder.setTargetSize(MAX_REENCODE_WIDTH, (info.size.height * ratio).toInt())
                }
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } else {
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
        }
    }.getOrNull()

    private fun displayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    private fun guessMime(uri: Uri): String {
        val p = uri.toString().lowercase()
        return when {
            p.endsWith(".png") -> "image/png"
            p.endsWith(".webp") -> "image/webp"
            else -> "image/jpeg"
        }
    }

    fun deleteDraftFiles(draftId: String) {
        File(context.filesDir, "drafts/$draftId").deleteRecursively()
    }

    fun tempSizeBytes(): Long = File(context.filesDir, "drafts").walkBottomUp().filter { it.isFile }.sumOf { it.length() } +
        context.cacheDir.walkBottomUp().filter { it.isFile }.sumOf { it.length() }

    private companion object {
        val SUPPORTED = setOf("image/jpeg", "image/png", "image/webp")
        const val MAX_BYTES = 15L * 1024 * 1024
        const val MAX_RENDER_WIDTH = 1600
        const val MAX_RENDER_HEIGHT = 12000
        const val MAX_REENCODE_WIDTH = 2000
    }
}
