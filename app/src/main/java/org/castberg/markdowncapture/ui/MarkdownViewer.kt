package org.castberg.markdowncapture.ui

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.documentfile.provider.DocumentFile
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// ── Block model ───────────────────────────────────────────────────────────────

sealed class MdBlock {
    data class Heading(val text: String, val level: Int) : MdBlock()
    data class Paragraph(val spans: AnnotatedString) : MdBlock()
    data class BulletItem(val spans: AnnotatedString, val indent: Int) : MdBlock()
    data class Blockquote(val spans: AnnotatedString) : MdBlock()
    data class CodeBlock(val code: String) : MdBlock()
    data class InlineImage(val alt: String, val path: String) : MdBlock()
    object Rule : MdBlock()
    object Blank : MdBlock()
}

// ── Parser ────────────────────────────────────────────────────────────────────

private val wikiImageRe  = Regex("""!\[\[([^\]]+)]]""")
private val mdImageRe    = Regex("""!\[([^\]]*?)]\(([^)]+)\)""")
private val fencedStart  = Regex("""^```""")

fun parseMarkdown(text: String): List<MdBlock> {
    val lines = text.lines()
    val blocks = mutableListOf<MdBlock>()
    var i = 0

    // Strip YAML frontmatter
    if (lines.getOrNull(0)?.trim() == "---") {
        i = 1
        while (i < lines.size && lines[i].trim() != "---") i++
        i++ // skip closing ---
    }

    while (i < lines.size) {
        val line = lines[i]

        // Fenced code block
        if (fencedStart.containsMatchIn(line)) {
            val code = StringBuilder()
            i++
            while (i < lines.size && !fencedStart.containsMatchIn(lines[i])) {
                code.appendLine(lines[i])
                i++
            }
            blocks += MdBlock.CodeBlock(code.toString().trimEnd())
            i++
            continue
        }

        // Heading
        val headingMatch = Regex("""^(#{1,6})\s+(.+)""").matchEntire(line.trim())
        if (headingMatch != null) {
            blocks += MdBlock.Heading(headingMatch.groupValues[2], headingMatch.groupValues[1].length)
            i++; continue
        }

        // Horizontal rule
        if (Regex("""^[-*_]{3,}\s*$""").matches(line.trim())) {
            blocks += MdBlock.Rule; i++; continue
        }

        // Blockquote
        if (line.trimStart().startsWith("> ")) {
            blocks += MdBlock.Blockquote(inlineFormat(line.trimStart().removePrefix("> ")))
            i++; continue
        }

        // Bullet item
        val bulletMatch = Regex("""^(\s*)[-*+]\s+(.+)""").matchEntire(line)
        if (bulletMatch != null) {
            val indent = bulletMatch.groupValues[1].length / 2
            blocks += MdBlock.BulletItem(inlineFormat(bulletMatch.groupValues[2]), indent)
            i++; continue
        }

        // Blank line
        if (line.isBlank()) { blocks += MdBlock.Blank; i++; continue }

        // Image-only line (wiki or standard)
        val wikiImg = wikiImageRe.matchEntire(line.trim())
        if (wikiImg != null) {
            blocks += MdBlock.InlineImage("", wikiImg.groupValues[1])
            i++; continue
        }
        val mdImg = mdImageRe.matchEntire(line.trim())
        if (mdImg != null) {
            blocks += MdBlock.InlineImage(mdImg.groupValues[1], mdImg.groupValues[2])
            i++; continue
        }

        // Paragraph — accumulate until blank or structural line
        val para = StringBuilder(line)
        i++
        while (i < lines.size) {
            val next = lines[i]
            if (next.isBlank()) break
            if (Regex("""^#{1,6}\s""").containsMatchIn(next)) break
            if (Regex("""^[-*_]{3,}\s*$""").matches(next.trim())) break
            if (next.trimStart().startsWith("> ")) break
            if (Regex("""^(\s*)[-*+]\s""").containsMatchIn(next)) break
            if (fencedStart.containsMatchIn(next)) break
            if (wikiImageRe.containsMatchIn(next) || mdImageRe.containsMatchIn(next)) break
            para.append(" ").append(next.trim())
            i++
        }
        blocks += MdBlock.Paragraph(inlineFormat(para.toString()))
    }
    return blocks
}

// Inline bold / italic / code formatting
fun inlineFormat(text: String): AnnotatedString = buildAnnotatedString {
    val patterns = listOf(
        Regex("""\*\*(.+?)\*\*""") to "bold",
        Regex("""__(.+?)__""")    to "bold",
        Regex("""\*(.+?)\*""")   to "italic",
        Regex("""_(.+?)_""")     to "italic",
        Regex("""`(.+?)`""")     to "code",
    )

    var pos = 0
    while (pos < text.length) {
        var earliest: MatchResult? = null
        var earliestTag = ""
        for ((re, tag) in patterns) {
            val m = re.find(text, pos) ?: continue
            if (earliest == null || m.range.first < earliest.range.first) {
                earliest = m; earliestTag = tag
            }
        }
        if (earliest == null) { append(text.substring(pos)); break }
        if (earliest.range.first > pos) append(text.substring(pos, earliest.range.first))
        when (earliestTag) {
            "bold"   -> withStyle(SpanStyle(fontWeight = FontWeight.Bold))   { append(earliest!!.groupValues[1]) }
            "italic" -> withStyle(SpanStyle(fontStyle  = FontStyle.Italic))  { append(earliest!!.groupValues[1]) }
            "code"   -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp)) { append(earliest!!.groupValues[1]) }
        }
        pos = earliest.range.last + 1
    }
}

// ── SAF image loader ──────────────────────────────────────────────────────────

suspend fun loadSafImage(
    context: Context,
    folderUri: String,
    imagePath: String   // e.g. "_resources/baseName/image.jpg" or "![[_resources/...]]"
): ImageBitmap? = withContext(Dispatchers.IO) {
    runCatching {
        val folder = DocumentFile.fromTreeUri(context, Uri.parse(folderUri)) ?: return@withContext null
        val segments = imagePath.trimStart('/').split("/")
        var current: DocumentFile = folder
        for (seg in segments) {
            current = current.findFile(seg) ?: return@withContext null
        }
        val stream = context.contentResolver.openInputStream(current.uri) ?: return@withContext null
        BitmapFactory.decodeStream(stream)?.asImageBitmap()
    }.getOrNull()
}

// ── Composable renderer ───────────────────────────────────────────────────────

@Composable
fun RenderedMarkdown(
    blocks: List<MdBlock>,
    folderUri: String,
    modifier: Modifier = Modifier
) {
    val context = androidx.compose.ui.platform.LocalContext.current

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (block in blocks) {
            when (block) {
                is MdBlock.Blank -> Spacer(Modifier.height(4.dp))

                is MdBlock.Rule -> HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                is MdBlock.Heading -> {
                    val style = when (block.level) {
                        1 -> MaterialTheme.typography.headlineMedium
                        2 -> MaterialTheme.typography.headlineSmall
                        3 -> MaterialTheme.typography.titleLarge
                        else -> MaterialTheme.typography.titleMedium
                    }
                    Text(
                        block.text,
                        style = style,
                        modifier = Modifier.padding(top = if (block.level <= 2) 8.dp else 4.dp)
                    )
                }

                is MdBlock.Paragraph -> Text(block.spans, style = MaterialTheme.typography.bodyMedium)

                is MdBlock.BulletItem -> Row(modifier = Modifier.padding(start = (block.indent * 16).dp)) {
                    Text("• ", style = MaterialTheme.typography.bodyMedium)
                    Text(block.spans, style = MaterialTheme.typography.bodyMedium)
                }

                is MdBlock.Blockquote -> Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant,
                            RoundedCornerShape(4.dp)
                        )
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(block.spans, style = MaterialTheme.typography.bodyMedium)
                }

                is MdBlock.CodeBlock -> Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant,
                            RoundedCornerShape(4.dp)
                        )
                        .padding(10.dp)
                ) {
                    Text(
                        block.code,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                    )
                }

                is MdBlock.InlineImage -> {
                    var bitmap by remember(block.path) { mutableStateOf<ImageBitmap?>(null) }
                    LaunchedEffect(block.path) {
                        bitmap = loadSafImage(context, folderUri, block.path)
                    }
                    bitmap?.let {
                        Image(
                            bitmap = it,
                            contentDescription = block.alt.ifBlank { null },
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp)),
                            contentScale = ContentScale.FillWidth
                        )
                    }
                }

                else -> Unit
            }
        }
    }
}
