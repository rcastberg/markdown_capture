package org.castberg.markdowncapture.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream

// A4 in PDF points (72 pt = 1 inch)
private const val PAGE_W    = 595
private const val PAGE_H    = 842
private const val MARGIN    = 48
private const val CONTENT_W = PAGE_W - MARGIN * 2   // 499
private const val IMG_MAX_PX = 1200                 // max image width before re-scaling

// ── Minimal PDF object writer ─────────────────────────────────────────────────
// Objects are buffered in memory, then written sequentially with an xref table.

private class PdfWriter {
    private val objects = LinkedHashMap<Int, ByteArray>()
    private var next = 1

    fun alloc() = next++

    fun obj(n: Int, dict: String) {
        objects[n] = "$n 0 obj\n$dict\nendobj\n".toByteArray(Charsets.ISO_8859_1)
    }

    // Stream object: dict entries are appended after /Length automatically.
    fun stream(n: Int, extraDict: String, data: ByteArray) {
        val hdr  = "$n 0 obj\n<< /Length ${data.size}$extraDict >>\nstream\n"
                       .toByteArray(Charsets.ISO_8859_1)
        val tail = "\nendstream\nendobj\n".toByteArray(Charsets.ISO_8859_1)
        objects[n] = hdr + data + tail
    }

    fun writeTo(out: OutputStream) {
        val hdr = "%PDF-1.4\n%âãÏÓ\n".toByteArray(Charsets.ISO_8859_1)
        out.write(hdr)
        var pos = hdr.size.toLong()
        val offsets = mutableMapOf<Int, Long>()
        for ((n, bytes) in objects) { offsets[n] = pos; out.write(bytes); pos += bytes.size }

        val xrefOffset = pos
        val xb = StringBuilder("xref\n0 $next\n0000000000 65535 f \n")
        for (n in 1 until next)
            xb.append("${(offsets[n] ?: 0L).toString().padStart(10, '0')} 00000 n \n")
        xb.append("trailer\n<< /Size $next /Root 1 0 R >>\nstartxref\n$xrefOffset\n%%EOF\n")
        out.write(xb.toString().toByteArray(Charsets.ISO_8859_1))
    }
}

// ── Per-page content accumulator ─────────────────────────────────────────────

private class Page {
    val ops  = StringBuilder()
    val imgs = mutableListOf<Pair<String, Int>>() // (resourceName, objNum)
    var y    = MARGIN.toFloat()                   // y from TOP of page

    fun hasContent() = ops.isNotEmpty() || imgs.isNotEmpty()
    fun spaceLeft()  = PAGE_H - MARGIN - y

    fun addText(
        text: String, font: String, size: Float,
        leading: Float = size * 1.4f, xPad: Float = 0f
    ) {
        if (text.isBlank()) { y += leading * 0.5f; return }
        ops.append("BT /$font $size Tf\n")
        var first = true
        for (line in wrapWords(text, CONTENT_W - xPad, charWidthAt1(font) * size)) {
            val baselineY = PAGE_H - y - size * 0.82f
            if (first) { ops.append("${MARGIN + xPad} $baselineY Td\n"); first = false }
            else        { ops.append("0 ${-leading} Td\n") }
            ops.append("(${line.pdfEscape()}) Tj\n")
            y += leading
        }
        ops.append("ET\n")
        y += 2f
    }

    fun addImage(name: String, objNum: Int, drawW: Float, drawH: Float) {
        val pdfY = PAGE_H - y - drawH
        ops.append("q $drawW 0 0 $drawH ${MARGIN.toFloat()} $pdfY cm /$name Do Q\n")
        imgs += name to objNum
        y += drawH + 8
    }

    fun addRule() {
        val pdfY = PAGE_H - y - 5
        ops.append("0.4 w 0.8 G ${MARGIN.toFloat()} $pdfY m ${(PAGE_W - MARGIN).toFloat()} $pdfY l S\n")
        y += 10f
    }

    fun build() = ops.toString().toByteArray(Charsets.ISO_8859_1)
}

// ── Public entry points ───────────────────────────────────────────────────────

suspend fun generateAndSharePdf(
    context: Context,
    baseName: String,
    folderUri: String,
    blocks: List<MdBlock>
) = withContext(Dispatchers.IO) {

    val pdf = PdfWriter()

    // Reserve fixed object numbers up-front
    val nCatalog = pdf.alloc()  // 1
    val nPages   = pdf.alloc()  // 2
    val nF1      = pdf.alloc()  // 3  Helvetica       (body)
    val nF2      = pdf.alloc()  // 4  Helvetica-Bold  (headings)
    val nF3      = pdf.alloc()  // 5  Courier         (code)

    pdf.obj(nF1, "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>")
    pdf.obj(nF2, "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold /Encoding /WinAnsiEncoding >>")
    pdf.obj(nF3, "<< /Type /Font /Subtype /Type1 /BaseFont /Courier /Encoding /WinAnsiEncoding >>")

    // Pre-load every image as a JPEG XObject (DCTDecode = raw JPEG bytes stored as-is)
    data class ImgMeta(val objNum: Int, val w: Int, val h: Int)
    val imgMeta = mutableMapOf<String, ImgMeta>()
    for (block in blocks) {
        if (block !is MdBlock.InlineImage || block.path in imgMeta) continue
        val jpeg = prepareJpeg(context, folderUri, block.path) ?: continue
        val n = pdf.alloc()
        // /Filter /DCTDecode embeds the JPEG bytes verbatim — no pixel expansion
        pdf.stream(
            n,
            " /Type /XObject /Subtype /Image" +
            " /Width ${jpeg.w} /Height ${jpeg.h}" +
            " /ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode",
            jpeg.bytes
        )
        imgMeta[block.path] = ImgMeta(n, jpeg.w, jpeg.h)
    }

    // Build pages, breaking content when a block would overflow
    val pages   = mutableListOf<Page>()
    var current = Page()
    fun nextPage() { pages += current; current = Page() }
    fun ensureSpace(h: Float) { if (current.spaceLeft() < h) nextPage() }

    for (block in blocks) {
        when (block) {
            is MdBlock.Blank -> current.y += 6f

            is MdBlock.Rule -> { ensureSpace(14f); current.addRule() }

            is MdBlock.Heading -> {
                val sz  = when (block.level) { 1 -> 20f; 2 -> 17f; 3 -> 15f; else -> 13f }
                val est = estimateTextHeight(block.text, "F2", sz) + 12f
                ensureSpace(est)
                current.y += if (block.level <= 2) 10f else 6f
                current.addText(block.text, "F2", sz)
            }

            is MdBlock.Paragraph -> {
                val est = estimateTextHeight(block.spans.text, "F1", 11f) + 6f
                ensureSpace(est)
                current.addText(block.spans.text, "F1", 11f)
            }

            is MdBlock.BulletItem -> {
                val xPad = block.indent * 16f + 14f
                val est  = estimateTextHeight(block.spans.text, "F1", 11f, CONTENT_W - xPad) + 4f
                ensureSpace(est)
                // Bullet character (\225 = • in WinAnsiEncoding / Windows-1252 code page)
                val bY = PAGE_H - current.y - 11f * 0.82f
                current.ops.append("BT /F1 11 Tf ${MARGIN + block.indent * 16f} $bY Td (\\225) Tj ET\n")
                current.addText(block.spans.text, "F1", 11f, xPad = xPad)
                current.y -= 2f // addText adds a 2pt trailing gap; keep bullets tighter
            }

            is MdBlock.Blockquote -> {
                val est = estimateTextHeight(block.spans.text, "F1", 11f, CONTENT_W - 14f) + 6f
                ensureSpace(est)
                current.addText(block.spans.text, "F1", 11f, xPad = 14f)
            }

            is MdBlock.CodeBlock -> {
                val lines = block.code.lines()
                    .flatMap { wrapWords(it, CONTENT_W.toFloat(), charWidthAt1("F3") * 10f) }
                val est = lines.size * 14f + 10f
                ensureSpace(est)
                current.y += 4f
                for (line in lines) {
                    ensureSpace(14f)
                    current.addText(line, "F3", 10f, leading = 14f)
                }
                current.y += 4f
            }

            is MdBlock.InlineImage -> {
                val meta  = imgMeta[block.path] ?: continue
                val drawW = CONTENT_W.toFloat()
                val drawH = meta.h.toFloat() * drawW / meta.w
                val name  = "Im${meta.objNum}"
                // If image fits on remaining page, keep it there; otherwise start a new page
                if (current.spaceLeft() < drawH && current.hasContent()) nextPage()
                val actualH = drawH.coerceAtMost((PAGE_H - MARGIN * 2).toFloat())
                current.addImage(name, meta.objNum, drawW, actualH)
            }

            else -> Unit
        }
    }
    pages += current

    // Write each page as content stream + page object
    val pageNums = mutableListOf<Int>()
    for (page in pages) {
        if (!page.hasContent() && page.y <= MARGIN + 1f) continue
        val nContent = pdf.alloc()
        pdf.stream(nContent, "", page.build())
        val fontDict = "/F1 $nF1 0 R /F2 $nF2 0 R /F3 $nF3 0 R"
        val imgDict  = page.imgs.distinctBy { it.first }
            .joinToString(" ") { (nm, n) -> "/$nm $n 0 R" }
        val nPage = pdf.alloc()
        pdf.obj(nPage, """
            << /Type /Page /Parent $nPages 0 R
               /MediaBox [0 0 $PAGE_W $PAGE_H]
               /Contents $nContent 0 R
               /Resources << /Font << $fontDict >> /XObject << $imgDict >> >>
            >>
        """.trimIndent())
        pageNums.add(nPage)
    }

    val kids = pageNums.joinToString(" ") { "$it 0 R" }
    pdf.obj(nPages,   "<< /Type /Pages /Kids [$kids] /Count ${pageNums.size} >>")
    pdf.obj(nCatalog, "<< /Type /Catalog /Pages $nPages 0 R >>")

    val outFile = File(context.cacheDir, "$baseName.pdf")
    outFile.outputStream().buffered().use { pdf.writeTo(it) }

    val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", outFile)
    context.startActivity(Intent.createChooser(
        Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, "Share PDF"
    ))
}

fun shareMarkdownFile(context: Context, baseName: String, folderUri: String) {
    val folder = DocumentFile.fromTreeUri(context, Uri.parse(folderUri)) ?: return
    val mdDoc  = folder.findFile("$baseName.md") ?: return
    val cache  = File(context.cacheDir, "$baseName.md")
    context.contentResolver.openInputStream(mdDoc.uri)?.use { it.copyTo(cache.outputStream()) }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", cache)
    context.startActivity(Intent.createChooser(
        Intent(Intent.ACTION_SEND).apply {
            type = "text/markdown"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, "Share Markdown"
    ))
}

// ── Helpers ───────────────────────────────────────────────────────────────────

private data class JpegResult(val bytes: ByteArray, val w: Int, val h: Int)

private fun prepareJpeg(context: Context, folderUri: String, path: String): JpegResult? = runCatching {
    val folder = DocumentFile.fromTreeUri(context, Uri.parse(folderUri)) ?: return null
    var node: DocumentFile = folder
    for (seg in path.trimStart('/').split("/")) { node = node.findFile(seg) ?: return null }

    val raw = context.contentResolver.openInputStream(node.uri)?.use { it.readBytes() } ?: return null

    // Read dimensions without full decode
    val probe = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(raw, 0, raw.size, probe)
    val origW = probe.outWidth; val origH = probe.outHeight
    if (origW <= 0 || origH <= 0) return null

    // Use inSampleSize to avoid loading a huge bitmap, then scale to exact target
    val sampleSize = Integer.highestOneBit(origW / IMG_MAX_PX).coerceAtLeast(1)
    val decOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
    var bmp = BitmapFactory.decodeByteArray(raw, 0, raw.size, decOpts) ?: return null

    if (bmp.width > IMG_MAX_PX) {
        val scale   = IMG_MAX_PX.toFloat() / bmp.width
        val scaled  = Bitmap.createScaledBitmap(bmp, IMG_MAX_PX, (bmp.height * scale).toInt(), true)
        bmp.recycle(); bmp = scaled
    }

    val finalW = bmp.width; val finalH = bmp.height
    val out = ByteArrayOutputStream()
    bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
    bmp.recycle()
    JpegResult(out.toByteArray(), finalW, finalH)
}.getOrNull()

// Estimate the height needed to render `text` in `font` at `size`
private fun estimateTextHeight(
    text: String, font: String, size: Float,
    width: Float = CONTENT_W.toFloat()
): Float {
    val lines = wrapWords(text, width, charWidthAt1(font) * size)
    return lines.size * size * 1.4f
}

private fun charWidthAt1(font: String) = if (font == "F3") 0.6f else 0.52f

private fun wrapWords(text: String, maxW: Float, charW: Float): List<String> {
    if (text.isBlank() || maxW <= 0f) return listOf(text)
    val words = text.split(' ')
    val lines = mutableListOf<String>()
    val cur = StringBuilder(); var curW = 0f
    for (word in words) {
        val wW = word.length * charW
        if (cur.isEmpty()) { cur.append(word); curW = wW }
        else if (curW + charW + wW <= maxW) { cur.append(' ').append(word); curW += charW + wW }
        else { lines += cur.toString(); cur.clear().append(word); curW = wW }
    }
    if (cur.isNotEmpty()) lines += cur.toString()
    return lines.ifEmpty { listOf("") }
}

// Escape text for PDF string literals (parentheses, backslash; drop non-Latin-1)
private fun String.pdfEscape(): String = buildString {
    for (c in this@pdfEscape) when {
        c == '\\'       -> append("\\\\")
        c == '('        -> append("\\(")
        c == ')'        -> append("\\)")
        c.code in 32..126 || c.code in 160..255 -> append(c)
        else            -> append('?')
    }
}
