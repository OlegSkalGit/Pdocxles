package com.pdocxles.app.engine.openxml

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import kotlin.math.roundToInt

object OpenXmlHtmlEngine {

    suspend fun convertDocxToHtml(file: File): Result<String> = withContext(Dispatchers.IO) {
        try {
            if (!file.exists() || file.length() == 0L) {
                return@withContext Result.failure(IllegalArgumentException("DOCX file is empty or not found: ${file.absolutePath}"))
            }

            ZipFile(file).use { zip ->
                val imagesMap = extractAllImages(zip)
                val relsMap = extractRelationships(zip, "word/_rels/document.xml.rels")
                val docEntry = zip.getEntry("word/document.xml")
                    ?: return@withContext Result.failure(IllegalArgumentException("Invalid DOCX: missing word/document.xml"))

                val docXml = zip.getInputStream(docEntry).use { it.readBytes() }
                val bodyHtml = parseDocxXml(ByteArrayInputStream(docXml), imagesMap, relsMap)

                val fullHtml = buildHtmlDocument(
                    title = file.name,
                    bodyContent = "<div class=\"docx-canvas\"><div class=\"docx-page\">$bodyHtml</div></div>",
                    customCss = DOCX_PAGE_CSS
                )
                Result.success(fullHtml)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Result.failure(e)
        }
    }

    suspend fun convertPptxToHtml(file: File): Result<String> = withContext(Dispatchers.IO) {
        try {
            if (!file.exists() || file.length() == 0L) {
                return@withContext Result.failure(IllegalArgumentException("PPTX file is empty or not found: ${file.absolutePath}"))
            }

            ZipFile(file).use { zip ->
                val imagesMap = extractAllImages(zip)
                val slideEntries = zip.entries().asSequence()
                    .filter { it.name.startsWith("ppt/slides/slide") && it.name.endsWith(".xml") }
                    .sortedBy { extractSlideNumber(it.name) }
                    .toList()

                if (slideEntries.isEmpty()) {
                    return@withContext Result.failure(IllegalArgumentException("No slides found in PPTX presentation"))
                }

                val slidesHtml = StringBuilder()
                slideEntries.forEachIndexed { index, entry ->
                    val slideNum = index + 1
                    val relPath = "ppt/slides/_rels/${entry.name.substringAfterLast('/')}.rels"
                    val relsMap = extractRelationships(zip, relPath)
                    val slideXml = zip.getInputStream(entry).use { it.readBytes() }
                    val contentHtml = parseSlideXml(ByteArrayInputStream(slideXml), imagesMap, relsMap)

                    slidesHtml.append("""
                        <div class="slide-card" id="slide-$slideNum">
                            <div class="slide-header">
                                <span class="slide-badge">Slide $slideNum of ${slideEntries.size}</span>
                            </div>
                            <div class="slide-content">
                                $contentHtml
                            </div>
                        </div>
                    """.trimIndent())
                }

                val fullHtml = buildHtmlDocument(
                    title = file.name,
                    bodyContent = slidesHtml.toString(),
                    customCss = PPTX_CSS
                )
                Result.success(fullHtml)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Result.failure(e)
        }
    }

    private fun extractSlideNumber(name: String): Int {
        val numStr = name.substringAfter("slide").substringBefore(".xml")
        return numStr.toIntOrNull() ?: 0
    }

    /**
     * Extracts all media files (PNG, JPG, GIF, WEBP, SVG, BMP) from archive and maps by filename and relative path.
     */
    private fun extractAllImages(zip: ZipFile): Map<String, String> {
        val map = mutableMapOf<String, String>()
        val entries = zip.entries()
        while (entries.hasMoreElements()) {
            val entry = entries.nextElement()
            if (!entry.isDirectory && (entry.name.contains("/media/") || entry.name.startsWith("media/"))) {
                val filename = entry.name.substringAfterLast('/')
                val ext = filename.substringAfterLast('.', "png").lowercase()
                val mime = when (ext) {
                    "jpg", "jpeg" -> "image/jpeg"
                    "gif" -> "image/gif"
                    "svg" -> "image/svg+xml"
                    "webp" -> "image/webp"
                    "bmp" -> "image/bmp"
                    else -> "image/png"
                }
                try {
                    val bytes = zip.getInputStream(entry).use { it.readBytes() }
                    val base64 = encodeBase64(bytes)
                    val dataUri = "data:$mime;base64,$base64"
                    map[filename] = dataUri
                    map[entry.name] = dataUri
                    map["media/$filename"] = dataUri
                    map["../media/$filename"] = dataUri
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
        return map
    }

    private fun encodeBase64(bytes: ByteArray): String {
        return try {
            java.util.Base64.getEncoder().encodeToString(bytes)
        } catch (e: Throwable) {
            android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        }
    }

    /**
     * Extracts OpenXML relationship mappings (Id -> Target filename / path).
     */
    private fun extractRelationships(zip: ZipFile, relsPath: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        val entry = zip.getEntry(relsPath) ?: return map

        try {
            zip.getInputStream(entry).use { stream ->
                val parser = createXmlParser(stream)
                var event = parser.eventType
                while (event != XmlPullParser.END_DOCUMENT) {
                    if (event == XmlPullParser.START_TAG) {
                        val tagName = parser.name?.substringAfterLast(':') ?: ""
                        if (tagName == "Relationship") {
                            val id = getAttributeAny(parser, "Id", "id")
                            val target = getAttributeAny(parser, "Target", "target")
                            if (id != null && target != null) {
                                val filename = target.substringAfterLast('/')
                                map[id] = filename
                                map[id.lowercase()] = filename
                            }
                        }
                    }
                    event = parser.next()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return map
    }

    /**
     * Parses DOCX XML with realistic A4 page layout, natural integrated tables, and rich text typography:
     * bold, italic, underline, strike, text color, highlight, font size, sub/sup, bullet lists, page breaks.
     */
    private fun parseDocxXml(
        stream: InputStream,
        imagesMap: Map<String, String>,
        relsMap: Map<String, String>
    ): String {
        val sb = StringBuilder()
        val parser = createXmlParser(stream)
        var event = parser.eventType

        var inTableCell = false
        var inParagraph = false
        var isHeaderRow = false
        var cellColSpan = 1
        var cellShadingHex: String? = null
        var cellTextAlign: String? = null
        var cellWidthStyle: String? = null

        val currentGridColWidths = mutableListOf<Int>()

        var isBold = false
        var isItalic = false
        var isUnderline = false
        var isStrike = false
        var vertAlign: String? = null
        var textColorHex: String? = null
        var highlightColor: String? = null
        var fontSizePt: Int? = null

        var isHeading = false
        var headingLevel = 1
        var isBulletList = false
        var paragraphAlign: String? = null
        var paragraphIndentPx: Int? = null
        var paragraphSpacingBeforePt: Int? = null
        var paragraphSpacingAfterPt: Int? = null

        val currentParagraphText = StringBuilder()
        val currentCellContent = StringBuilder()

        while (event != XmlPullParser.END_DOCUMENT) {
            val tagName = parser.name?.substringAfterLast(':') ?: ""

            when (event) {
                XmlPullParser.START_TAG -> {
                    when (tagName) {
                        "tbl" -> {
                            currentGridColWidths.clear()
                            sb.append("<div class=\"table-container\"><table class=\"doc-table\">")
                        }
                        "tblGrid" -> {
                            currentGridColWidths.clear()
                        }
                        "gridCol" -> {
                            val w = getAttributeAny(parser, "w", "w:w")?.toIntOrNull() ?: 0
                            if (w > 0) {
                                currentGridColWidths.add(w)
                            }
                        }
                        "tblHeader" -> {
                            isHeaderRow = true
                        }
                        "tr" -> {
                            isHeaderRow = false
                            sb.append("<tr>")
                        }
                        "tc" -> {
                            inTableCell = true
                            cellColSpan = 1
                            cellShadingHex = null
                            cellTextAlign = null
                            cellWidthStyle = null
                            currentCellContent.clear()
                        }
                        "tcW" -> {
                            val w = getAttributeAny(parser, "w", "w:w")?.toIntOrNull()
                            val type = getAttributeAny(parser, "type", "w:type")?.lowercase()
                            if (w != null && w > 0) {
                                cellWidthStyle = if (type == "pct") {
                                    val pct = (w / 50.0).roundToInt()
                                    "width: $pct%;"
                                } else {
                                    val px = (w / 15).coerceAtLeast(25)
                                    "width: ${px}px; min-width: ${px}px;"
                                }
                            }
                        }
                        "gridSpan" -> {
                            val spanVal = getAttributeAny(parser, "val", "w:val")?.toIntOrNull()
                            if (spanVal != null && spanVal > 1) {
                                cellColSpan = spanVal
                            }
                        }
                        "shd" -> {
                            val fillVal = getAttributeAny(parser, "fill", "w:fill")
                            if (!fillVal.isNullOrBlank() && fillVal != "auto" && fillVal != "none") {
                                cellShadingHex = if (fillVal.startsWith("#")) fillVal else "#$fillVal"
                            }
                        }
                        "jc" -> {
                            val jcVal = getAttributeAny(parser, "val", "w:val")?.lowercase()
                            val align = when (jcVal) {
                                "center" -> "center"
                                "right" -> "right"
                                "both" -> "justify"
                                else -> "left"
                            }
                            if (inTableCell) {
                                cellTextAlign = align
                            } else {
                                paragraphAlign = align
                            }
                        }
                        "p" -> {
                            inParagraph = true
                            currentParagraphText.clear()
                            isHeading = false
                            isBulletList = false
                            paragraphAlign = null
                            paragraphIndentPx = null
                            paragraphSpacingBeforePt = null
                            paragraphSpacingAfterPt = null
                        }
                        "pStyle" -> {
                            val styleVal = getAttributeAny(parser, "val", "w:val") ?: ""
                            if (styleVal.startsWith("Heading", ignoreCase = true) || styleVal.startsWith("Title", ignoreCase = true)) {
                                isHeading = true
                                val levelChar = styleVal.lastOrNull { it.isDigit() }
                                headingLevel = levelChar?.digitToIntOrNull()?.coerceIn(1, 4) ?: 1
                            } else if (styleVal.contains("List", ignoreCase = true) || styleVal.contains("Bullet", ignoreCase = true)) {
                                isBulletList = true
                            }
                        }
                        "ind" -> {
                            val firstLine = getAttributeAny(parser, "firstLine", "w:firstLine")?.toIntOrNull()
                            if (firstLine != null && firstLine > 0) {
                                paragraphIndentPx = (firstLine / 15).coerceIn(12, 60)
                            }
                        }
                        "spacing" -> {
                            val before = getAttributeAny(parser, "before", "w:before")?.toIntOrNull()
                            val after = getAttributeAny(parser, "after", "w:after")?.toIntOrNull()
                            if (before != null && before > 0) {
                                paragraphSpacingBeforePt = (before / 20).coerceIn(2, 36)
                            }
                            if (after != null && after > 0) {
                                paragraphSpacingAfterPt = (after / 20).coerceIn(2, 36)
                            }
                        }
                        "numPr" -> {
                            isBulletList = true
                        }
                        "r" -> {
                            isBold = false
                            isItalic = false
                            isUnderline = false
                            isStrike = false
                            vertAlign = null
                            textColorHex = null
                            highlightColor = null
                            fontSizePt = null
                        }
                        "b" -> {
                            val v = getAttributeAny(parser, "val", "w:val")?.lowercase()
                            isBold = v == null || v == "1" || v == "true" || v == "on"
                        }
                        "i" -> {
                            val v = getAttributeAny(parser, "val", "w:val")?.lowercase()
                            isItalic = v == null || v == "1" || v == "true" || v == "on"
                        }
                        "u" -> {
                            val v = getAttributeAny(parser, "val", "w:val")?.lowercase()
                            isUnderline = v != "none"
                        }
                        "strike", "dstrike" -> {
                            val v = getAttributeAny(parser, "val", "w:val")?.lowercase()
                            isStrike = v == null || v == "1" || v == "true" || v == "on"
                        }
                        "color" -> {
                            val c = getAttributeAny(parser, "val", "w:val")
                            if (!c.isNullOrBlank() && c != "auto") {
                                textColorHex = if (c.startsWith("#")) c else "#$c"
                            }
                        }
                        "highlight" -> {
                            val h = getAttributeAny(parser, "val", "w:val")
                            if (!h.isNullOrBlank() && h != "none") {
                                highlightColor = h
                            }
                        }
                        "sz" -> {
                            val szHalfPt = getAttributeAny(parser, "val", "w:val")?.toIntOrNull()
                            if (szHalfPt != null && szHalfPt > 0) {
                                fontSizePt = szHalfPt / 2
                            }
                        }
                        "vertAlign" -> {
                            vertAlign = getAttributeAny(parser, "val", "w:val")?.lowercase()
                        }
                        "t" -> {
                            val text = parser.nextText()
                            if (text.isNotEmpty()) {
                                val escaped = escapeHtml(text)
                                val styled = formatRichRun(
                                    text = escaped,
                                    bold = isBold,
                                    italic = isItalic,
                                    underline = isUnderline,
                                    strike = isStrike,
                                    colorHex = textColorHex,
                                    highlight = highlightColor,
                                    fontSizePt = fontSizePt,
                                    vertAlign = vertAlign
                                )
                                currentParagraphText.append(styled)
                            }
                        }
                        "br", "cr" -> {
                            val brType = getAttributeAny(parser, "type", "w:type")?.lowercase()
                            if (brType == "page") {
                                // A4 Page Break!
                                if (inParagraph && currentParagraphText.isNotEmpty()) {
                                    sb.append("<p>$currentParagraphText</p>")
                                    currentParagraphText.clear()
                                }
                                sb.append("</div><div class=\"docx-page\">")
                            } else {
                                currentParagraphText.append("<br/>")
                            }
                        }
                        "lastRenderedPageBreak" -> {
                            if (inParagraph && currentParagraphText.isNotEmpty()) {
                                sb.append("<p>$currentParagraphText</p>")
                                currentParagraphText.clear()
                            }
                            sb.append("</div><div class=\"docx-page\">")
                        }
                        "blip", "imagedata" -> {
                            val embedId = getAttributeAny(parser, "embed", "r:embed", "id", "r:id", "href", "r:link")
                            if (embedId != null) {
                                val imageName = relsMap[embedId] ?: relsMap[embedId.lowercase()]
                                val dataUri = if (imageName != null) {
                                    imagesMap[imageName] ?: imagesMap["media/$imageName"]
                                } else {
                                    imagesMap[embedId]
                                }

                                if (dataUri != null) {
                                    val imgHtml = "<div class=\"img-wrapper\"><img src=\"$dataUri\" alt=\"Image\" /></div>"
                                    if (inTableCell) {
                                        currentCellContent.append(imgHtml)
                                    } else if (inParagraph) {
                                        currentParagraphText.append(imgHtml)
                                    } else {
                                        sb.append(imgHtml)
                                    }
                                }
                            }
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    when (tagName) {
                        "tblGrid" -> {
                            if (currentGridColWidths.isNotEmpty()) {
                                val total = currentGridColWidths.sum().toDouble()
                                if (total > 0) {
                                    val cg = StringBuilder("<colgroup>")
                                    for (w in currentGridColWidths) {
                                        val pct = ((w / total) * 100.0).roundToInt()
                                        val minPx = (w / 15).coerceAtLeast(25)
                                        cg.append("<col style=\"width: $pct%; min-width: ${minPx}px;\" />")
                                    }
                                    cg.append("</colgroup>")
                                    sb.append(cg.toString())
                                }
                            }
                        }
                        "tbl" -> {
                            sb.append("</table></div>")
                        }
                        "tr" -> {
                            sb.append("</tr>")
                        }
                        "tc" -> {
                            inTableCell = false
                            val tag = if (isHeaderRow) "th" else "td"
                            val styleAttr = StringBuilder()
                            if (cellWidthStyle != null) {
                                styleAttr.append(cellWidthStyle).append(" ")
                            }
                            if (cellShadingHex != null) {
                                styleAttr.append("background-color: $cellShadingHex; ")
                            }
                            if (cellTextAlign != null) {
                                styleAttr.append("text-align: $cellTextAlign; ")
                            }

                            val styleStr = if (styleAttr.isNotEmpty()) " style=\"$styleAttr\"" else ""
                            val spanStr = if (cellColSpan > 1) " colspan=\"$cellColSpan\"" else ""

                            val content = currentCellContent.toString().trim()
                            val finalContent = if (content.isNotEmpty()) content else "&nbsp;"

                            sb.append("<$tag$spanStr$styleStr>$finalContent</$tag>")
                            currentCellContent.clear()
                        }
                        "p" -> {
                            inParagraph = false
                            val text = currentParagraphText.toString()
                            val styleRules = StringBuilder()
                            if (paragraphAlign != null) styleRules.append("text-align: $paragraphAlign; ")
                            if (paragraphIndentPx != null) styleRules.append("text-indent: ${paragraphIndentPx}px; ")
                            if (paragraphSpacingBeforePt != null) styleRules.append("margin-top: ${paragraphSpacingBeforePt}pt; ")
                            if (paragraphSpacingAfterPt != null) styleRules.append("margin-bottom: ${paragraphSpacingAfterPt}pt; ")

                            val styleStr = if (styleRules.isNotEmpty()) " style=\"$styleRules\"" else ""

                            if (text.isNotBlank() || text.contains("<img") || text.contains("<br")) {
                                val pHtml = if (isHeading) {
                                    "<h$headingLevel$styleStr>$text</h$headingLevel>"
                                } else if (isBulletList) {
                                    "<div class=\"doc-list-item\"$styleStr><span class=\"doc-bullet\">•</span> $text</div>"
                                } else {
                                    "<p$styleStr>$text</p>"
                                }

                                if (inTableCell) {
                                    currentCellContent.append(pHtml)
                                } else {
                                    sb.append(pHtml)
                                }
                            } else if (!inTableCell) {
                                sb.append("<div class=\"empty-p\"></div>")
                            }
                            currentParagraphText.clear()
                        }
                    }
                }
            }
            event = parser.next()
        }
        return sb.toString()
    }

    /**
     * Parses PPTX slide XML with shape-level image extraction, tables, and rich text formatting:
     * bold, italic, underline, strike, colors, font sizes, alignment and bullet levels.
     */
    private fun parseSlideXml(
        stream: InputStream,
        imagesMap: Map<String, String>,
        relsMap: Map<String, String>
    ): String {
        val sb = StringBuilder()
        val parser = createXmlParser(stream)
        var event = parser.eventType

        var isBold = false
        var isItalic = false
        var isUnderline = false
        var isStrike = false
        var fontSizePt: Int? = null
        var textColorHex: String? = null

        var paragraphAlign: String? = null
        var listLevel = 0

        var inTableCell = false
        var inParagraph = false
        var cellShadingHex: String? = null

        val currentParagraphText = StringBuilder()
        val currentCellContent = StringBuilder()

        while (event != XmlPullParser.END_DOCUMENT) {
            val tagName = parser.name?.substringAfterLast(':') ?: ""

            when (event) {
                XmlPullParser.START_TAG -> {
                    when (tagName) {
                        "tbl" -> {
                            sb.append("<div class=\"table-container\"><table class=\"doc-table\">")
                        }
                        "tr" -> {
                            sb.append("<tr>")
                        }
                        "tc" -> {
                            inTableCell = true
                            cellShadingHex = null
                            currentCellContent.clear()
                        }
                        "p" -> {
                            inParagraph = true
                            currentParagraphText.clear()
                            paragraphAlign = null
                            listLevel = 0
                        }
                        "pPr" -> {
                            val algn = getAttributeAny(parser, "algn")?.lowercase()
                            paragraphAlign = when (algn) {
                                "ctr" -> "center"
                                "r" -> "right"
                                "just" -> "justify"
                                else -> if (algn != null) "left" else null
                            }
                            val lvlStr = getAttributeAny(parser, "lvl")
                            listLevel = lvlStr?.toIntOrNull() ?: 0
                        }
                        "r" -> {
                            isBold = false
                            isItalic = false
                            isUnderline = false
                            isStrike = false
                            fontSizePt = null
                            textColorHex = null
                        }
                        "rPr" -> {
                            val b = getAttributeAny(parser, "b")
                            if (b == "1" || b == "true") isBold = true
                            val i = getAttributeAny(parser, "i")
                            if (i == "1" || i == "true") isItalic = true
                            val u = getAttributeAny(parser, "u")
                            if (!u.isNullOrBlank() && u != "none") isUnderline = true
                            val strike = getAttributeAny(parser, "strike")
                            if (!strike.isNullOrBlank() && strike != "noStrike") isStrike = true

                            val sz = getAttributeAny(parser, "sz")?.toIntOrNull()
                            if (sz != null && sz > 0) {
                                fontSizePt = (sz / 100.0).roundToInt()
                            }
                        }
                        "srgbClr" -> {
                            val hex = getAttributeAny(parser, "val")
                            if (!hex.isNullOrBlank()) {
                                if (inTableCell && currentCellContent.isEmpty() && currentParagraphText.isEmpty()) {
                                    cellShadingHex = "#$hex"
                                } else {
                                    textColorHex = "#$hex"
                                }
                            }
                        }
                        "t" -> {
                            val text = parser.nextText()
                            if (text.isNotEmpty()) {
                                val escaped = escapeHtml(text)
                                val styled = formatRichRun(
                                    text = escaped,
                                    bold = isBold,
                                    italic = isItalic,
                                    underline = isUnderline,
                                    strike = isStrike,
                                    colorHex = textColorHex,
                                    highlight = null,
                                    fontSizePt = fontSizePt,
                                    vertAlign = null
                                )
                                currentParagraphText.append(styled)
                            }
                        }
                        "br" -> {
                            currentParagraphText.append("<br/>")
                        }
                        "blip", "imagedata" -> {
                            // Slide-level picture shape (DrawingML <p:pic> or <v:imagedata>)
                            val embedId = getAttributeAny(parser, "embed", "r:embed", "id", "r:id", "href", "r:link")
                            if (embedId != null) {
                                val imgName = relsMap[embedId] ?: relsMap[embedId.lowercase()]
                                val dataUri = if (imgName != null) {
                                    imagesMap[imgName] ?: imagesMap["media/$imgName"]
                                } else {
                                    imagesMap[embedId]
                                }

                                if (dataUri != null) {
                                    val imgHtml = "<div class=\"img-wrapper\"><img src=\"$dataUri\" alt=\"Slide Image\" /></div>"
                                    if (inTableCell) {
                                        currentCellContent.append(imgHtml)
                                    } else if (inParagraph) {
                                        currentParagraphText.append(imgHtml)
                                    } else {
                                        sb.append(imgHtml)
                                    }
                                }
                            }
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    when (tagName) {
                        "tbl" -> {
                            sb.append("</table></div>")
                        }
                        "tr" -> {
                            sb.append("</tr>")
                        }
                        "tc" -> {
                            inTableCell = false
                            val styleAttr = if (cellShadingHex != null) " style=\"background-color: $cellShadingHex;\"" else ""
                            val content = currentCellContent.toString().trim()
                            val finalContent = if (content.isNotEmpty()) content else "&nbsp;"
                            sb.append("<td$styleAttr>$finalContent</td>")
                            currentCellContent.clear()
                        }
                        "p" -> {
                            inParagraph = false
                            val text = currentParagraphText.toString().trim()
                            if (text.isNotBlank() || text.contains("<img") || text.contains("<br")) {
                                val styles = StringBuilder()
                                if (paragraphAlign != null) styles.append("text-align: $paragraphAlign; ")
                                if (listLevel > 0) styles.append("padding-left: ${listLevel * 20}px; ")

                                val styleAttr = if (styles.isNotEmpty()) " style=\"$styles\"" else ""
                                val bullet = if (listLevel > 0) "<span class=\"slide-bullet\">•</span> " else ""
                                val pHtml = "<p class=\"slide-text\"$styleAttr>$bullet$text</p>"

                                if (inTableCell) {
                                    currentCellContent.append(pHtml)
                                } else {
                                    sb.append(pHtml)
                                }
                            }
                            currentParagraphText.clear()
                        }
                    }
                }
            }
            event = parser.next()
        }
        return sb.toString()
    }

    private fun getAttributeAny(parser: XmlPullParser, vararg candidateNames: String): String? {
        val count = parser.attributeCount
        for (i in 0 until count) {
            val attrName = parser.getAttributeName(i)
            val cleanName = attrName.substringAfterLast(':')
            for (cand in candidateNames) {
                val cleanCand = cand.substringAfterLast(':')
                if (attrName.equals(cand, ignoreCase = true) || cleanName.equals(cleanCand, ignoreCase = true)) {
                    val value = parser.getAttributeValue(i)
                    if (!value.isNullOrBlank()) return value
                }
            }
        }
        return null
    }

    private fun createXmlParser(stream: InputStream): XmlPullParser {
        val parser: XmlPullParser = try {
            XmlPullParserFactory.newInstance().newPullParser()
        } catch (e: Throwable) {
            try {
                val kxmlClass = Class.forName("org.kxml2.io.KXmlParser")
                kxmlClass.getDeclaredConstructor().newInstance() as XmlPullParser
            } catch (e2: Throwable) {
                XmlPullParserFactory.newInstance("org.kxml2.io.KXmlParser,org.kxml2.io.KXmlSerializer", null).newPullParser()
            }
        }
        parser.setInput(stream, "UTF-8")
        return parser
    }

    private fun formatRichRun(
        text: String,
        bold: Boolean,
        italic: Boolean,
        underline: Boolean,
        strike: Boolean,
        colorHex: String?,
        highlight: String?,
        fontSizePt: Int?,
        vertAlign: String?
    ): String {
        var res = text
        if (bold) res = "<strong>$res</strong>"
        if (italic) res = "<em>$res</em>"
        if (underline) res = "<u>$res</u>"
        if (strike) res = "<s>$res</s>"
        if (vertAlign == "superscript") res = "<sup>$res</sup>"
        if (vertAlign == "subscript") res = "<sub>$res</sub>"

        val styleRules = StringBuilder()
        if (colorHex != null) styleRules.append("color: $colorHex; ")
        if (highlight != null) styleRules.append("background-color: $highlight; ")
        if (fontSizePt != null && fontSizePt > 0) styleRules.append("font-size: ${fontSizePt}pt; ")

        return if (styleRules.isNotEmpty()) {
            "<span style=\"$styleRules\">$res</span>"
        } else {
            res
        }
    }

    private fun escapeHtml(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")
    }

    private fun buildHtmlDocument(title: String, bodyContent: String, customCss: String): String {
        return """
            <!DOCTYPE html>
            <html lang="en">
            <head>
                <meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=5.0, user-scalable=yes">
                <title>${escapeHtml(title)}</title>
                <style>
                    $BASE_CSS
                    $customCss
                </style>
            </head>
            <body>
                $bodyContent
            </body>
            </html>
        """.trimIndent()
    }

    private const val BASE_CSS = """
        * {
            box-sizing: border-box;
            -webkit-tap-highlight-color: transparent;
        }
        body {
            margin: 0;
            padding: 12px;
            font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", "Calibri", "Carlito", "Liberation Sans", Roboto, Helvetica, Arial, sans-serif;
            font-size: 14.5px;
            line-height: 1.35;
            color: #1a1a1a;
            background-color: #e9ecef;
            -webkit-font-smoothing: antialiased;
        }
        h1, h2, h3, h4, h5, h6 {
            color: #111827;
            font-weight: 700;
            margin-top: 1.2em;
            margin-bottom: 0.4em;
            line-height: 1.25;
        }
        h1 { font-size: 1.6em; border-bottom: 1px solid #e2e8f0; padding-bottom: 4px; }
        h2 { font-size: 1.35em; }
        h3 { font-size: 1.15em; }
        h4 { font-size: 1.05em; }
        p {
            margin: 0 0 6px 0;
            line-height: 1.25;
        }
        .empty-p {
            height: 10px;
        }
        .doc-list-item {
            margin: 3px 0 4px 18px;
            line-height: 1.35;
        }
        .doc-bullet {
            color: #2563eb;
            font-weight: bold;
            margin-right: 6px;
        }
        .img-wrapper {
            text-align: center;
            margin: 10px 0;
            width: 100%;
        }
        img {
            max-width: 100%;
            height: auto;
            display: inline-block;
            border-radius: 2px;
        }
        .table-container {
            width: 100%;
            overflow-x: auto;
            -webkit-overflow-scrolling: touch;
            margin: 12px 0;
            border: none;
            background: transparent;
            box-shadow: none;
        }
        table.doc-table {
            border-collapse: collapse;
            width: 100%;
            min-width: 100%;
            table-layout: auto;
            margin: 0;
            background: #ffffff;
            font-size: 13px;
            line-height: 1.35;
        }
        table.doc-table th, table.doc-table td {
            border: 1px solid #b8bfc6;
            padding: 6px 10px;
            text-align: left;
            vertical-align: top;
            word-break: break-word;
            overflow-wrap: break-word;
            min-width: 25px;
        }
        table.doc-table th {
            background-color: #f1f4f8;
            font-weight: 700;
            color: #1a1a24;
        }
        table.doc-table tr:nth-child(even) td {
            background-color: #fafbfc;
        }
    """

    private const val DOCX_PAGE_CSS = """
        body {
            background-color: #e9ecf0;
            padding: 16px 8px;
        }
        .docx-canvas {
            width: 100%;
            max-width: 820px;
            margin: 0 auto;
        }
        .docx-page {
            background-color: #ffffff;
            width: 100%;
            min-height: 1120px;
            margin: 0 auto 20px auto;
            padding: 56px 56px;
            box-shadow: 0 4px 16px rgba(0, 0, 0, 0.08), 0 1px 3px rgba(0, 0, 0, 0.04);
            border: 1px solid #d4d8de;
            border-radius: 2px;
            box-sizing: border-box;
            word-wrap: break-word;
            overflow-wrap: break-word;
        }
        @media screen and (max-width: 600px) {
            body {
                padding: 8px 4px;
            }
            .docx-page {
                padding: 28px 18px;
                min-height: auto;
                margin-bottom: 12px;
                box-shadow: 0 2px 8px rgba(0, 0, 0, 0.06);
            }
        }
    """

    private const val PPTX_CSS = """
        body {
            background-color: #e5e9f0;
            padding: 12px;
        }
        .slide-card {
            max-width: 880px;
            margin: 0 auto 20px auto;
            background: #ffffff;
            border-radius: 12px;
            padding: 24px;
            box-shadow: 0 4px 14px rgba(0,0,0,0.08);
            border: 1px solid #d8dee9;
            position: relative;
            box-sizing: border-box;
        }
        .slide-header {
            display: flex;
            justify-content: flex-end;
            margin-bottom: 14px;
            border-bottom: 1px solid #f0f0f0;
            padding-bottom: 6px;
        }
        .slide-badge {
            background: #fff3e0;
            color: #e65100;
            font-size: 12px;
            font-weight: 600;
            padding: 3px 10px;
            border-radius: 12px;
            border: 1px solid #ffe082;
        }
        .slide-content {
            font-size: 16px;
            line-height: 1.6;
            min-height: 120px;
        }
        .slide-text {
            margin: 8px 0;
        }
        .slide-bullet {
            color: #ff9800;
            font-weight: bold;
            margin-right: 6px;
        }
    """
}
