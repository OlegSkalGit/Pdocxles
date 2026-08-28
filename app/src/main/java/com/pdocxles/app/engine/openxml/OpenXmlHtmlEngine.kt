package com.pdocxles.app.engine.openxml

import android.util.Base64
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
                    bodyContent = bodyHtml,
                    customCss = DOCX_CSS
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
                    val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
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

    private fun extractRelationships(zip: ZipFile, relsPath: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        val entry = zip.getEntry(relsPath) ?: return map

        try {
            zip.getInputStream(entry).use { stream ->
                val parser = createXmlParser(stream)
                var event = parser.eventType
                while (event != XmlPullParser.END_DOCUMENT) {
                    if (event == XmlPullParser.START_TAG && (parser.name == "Relationship" || parser.name?.endsWith(":Relationship") == true)) {
                        val id = getAttributeAny(parser, "Id", "id")
                        val target = getAttributeAny(parser, "Target", "target")
                        if (id != null && target != null) {
                            val filename = target.substringAfterLast('/')
                            map[id] = filename
                            map[id.lowercase()] = filename
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

    private fun parseDocxXml(
        stream: InputStream,
        imagesMap: Map<String, String>,
        relsMap: Map<String, String>
    ): String {
        val sb = StringBuilder()
        val parser = createXmlParser(stream)
        var event = parser.eventType

        var inTableCell = false
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
        var isHeading = false
        var headingLevel = 1
        var paragraphAlign: String? = null

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
                                    val px = (w / 15).coerceAtLeast(30)
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
                            currentParagraphText.clear()
                            isHeading = false
                            paragraphAlign = null
                        }
                        "pStyle" -> {
                            val styleVal = getAttributeAny(parser, "val", "w:val") ?: ""
                            if (styleVal.startsWith("Heading", ignoreCase = true) || styleVal.startsWith("Title", ignoreCase = true)) {
                                isHeading = true
                                val levelChar = styleVal.lastOrNull { it.isDigit() }
                                headingLevel = levelChar?.digitToIntOrNull()?.coerceIn(1, 4) ?: 1
                            }
                        }
                        "r" -> {
                            isBold = false
                            isItalic = false
                            isUnderline = false
                            isStrike = false
                        }
                        "b" -> isBold = true
                        "i" -> isItalic = true
                        "u" -> isUnderline = true
                        "strike" -> isStrike = true
                        "t" -> {
                            val text = parser.nextText()
                            if (text.isNotEmpty()) {
                                val escaped = escapeHtml(text)
                                val styled = formatRun(escaped, isBold, isItalic, isUnderline, isStrike)
                                currentParagraphText.append(styled)
                            }
                        }
                        "br", "cr" -> {
                            currentParagraphText.append("<br/>")
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
                                    } else {
                                        currentParagraphText.append(imgHtml)
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
                                        val minPx = (w / 15).coerceAtLeast(30)
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
                        }
                        "p" -> {
                            val text = currentParagraphText.toString()
                            val alignStyle = if (paragraphAlign != null) " style=\"text-align: $paragraphAlign;\"" else ""

                            if (text.isNotBlank() || text.contains("<img") || text.contains("<br")) {
                                val pHtml = if (isHeading) {
                                    "<h$headingLevel$alignStyle>$text</h$headingLevel>"
                                } else {
                                    "<p$alignStyle>$text</p>"
                                }

                                if (inTableCell) {
                                    currentCellContent.append(pHtml)
                                } else {
                                    sb.append(pHtml)
                                }
                            } else if (!inTableCell) {
                                sb.append("<div class=\"empty-p\"></div>")
                            }
                        }
                    }
                }
            }
            event = parser.next()
        }
        return sb.toString()
    }

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
        var currentText = StringBuilder()

        while (event != XmlPullParser.END_DOCUMENT) {
            val tagName = parser.name?.substringAfterLast(':') ?: ""

            when (event) {
                XmlPullParser.START_TAG -> {
                    when (tagName) {
                        "p" -> {
                            currentText.clear()
                        }
                        "rPr" -> {
                            isBold = getAttributeAny(parser, "b") == "1"
                            isItalic = getAttributeAny(parser, "i") == "1"
                        }
                        "t" -> {
                            val text = parser.nextText()
                            if (text.isNotEmpty()) {
                                val escaped = escapeHtml(text)
                                val styled = formatRun(escaped, isBold, isItalic, false, false)
                                currentText.append(styled)
                            }
                        }
                        "blip", "imagedata" -> {
                            val embedId = getAttributeAny(parser, "embed", "r:embed", "id", "r:id", "href", "r:link")
                            if (embedId != null) {
                                val imgName = relsMap[embedId] ?: relsMap[embedId.lowercase()]
                                val dataUri = if (imgName != null) {
                                    imagesMap[imgName] ?: imagesMap["media/$imgName"]
                                } else {
                                    imagesMap[embedId]
                                }

                                if (dataUri != null) {
                                    currentText.append("<div class=\"img-wrapper\"><img src=\"$dataUri\" alt=\"Slide Image\" /></div>")
                                }
                            }
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    when (tagName) {
                        "p" -> {
                            val t = currentText.toString()
                            if (t.isNotBlank() || t.contains("<img")) {
                                sb.append("<p class=\"slide-text\">$t</p>")
                            }
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

    private fun formatRun(text: String, bold: Boolean, italic: Boolean, underline: Boolean, strike: Boolean): String {
        var res = text
        if (bold) res = "<strong>$res</strong>"
        if (italic) res = "<em>$res</em>"
        if (underline) res = "<u>$res</u>"
        if (strike) res = "<s>$res</s>"
        return res
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
                <div class="content-wrapper">
                    $bodyContent
                </div>
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
            font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif;
            font-size: 15px;
            line-height: 1.6;
            color: #1c1b1f;
            background-color: #f8f9fa;
        }
        .content-wrapper {
            max-width: 900px;
            margin: 0 auto;
            background: #ffffff;
            padding: 24px 20px;
            border-radius: 12px;
            box-shadow: 0 2px 8px rgba(0,0,0,0.06);
            word-wrap: break-word;
            overflow-wrap: break-word;
        }
        h1, h2, h3, h4, h5, h6 {
            color: #1a1a24;
            font-weight: 700;
            margin-top: 1.4em;
            margin-bottom: 0.6em;
            line-height: 1.3;
        }
        h1 { font-size: 1.6em; border-bottom: 1px solid #e0e0e0; padding-bottom: 6px; }
        h2 { font-size: 1.35em; }
        h3 { font-size: 1.15em; }
        p {
            margin: 0 0 10px 0;
        }
        .empty-p {
            height: 10px;
        }
        .img-wrapper {
            text-align: center;
            margin: 16px 0;
            width: 100%;
            overflow-x: auto;
        }
        img {
            max-width: 100%;
            height: auto;
            display: inline-block;
            border-radius: 6px;
            box-shadow: 0 2px 6px rgba(0,0,0,0.12);
        }
        .table-container {
            width: 100%;
            overflow-x: auto;
            -webkit-overflow-scrolling: touch;
            margin: 16px 0;
            border: 1px solid #d0d7de;
            border-radius: 8px;
            box-shadow: 0 1px 4px rgba(0,0,0,0.04);
            background: #ffffff;
        }
        table.doc-table {
            border-collapse: collapse;
            width: 100%;
            min-width: 100%;
            table-layout: auto;
            margin: 0;
        }
        table.doc-table th, table.doc-table td {
            border: 1px solid #d0d7de;
            padding: 9px 12px;
            text-align: left;
            vertical-align: top;
            font-size: 13.5px;
            line-height: 1.45;
            word-break: break-word;
            overflow-wrap: break-word;
            min-width: 35px;
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

    private const val DOCX_CSS = """
        .content-wrapper {
            border: 1px solid #eaeaea;
        }
    """

    private const val PPTX_CSS = """
        body {
            background-color: #eceff1;
            padding: 12px;
        }
        .content-wrapper {
            background: transparent;
            box-shadow: none;
            padding: 0;
        }
        .slide-card {
            background: #ffffff;
            border-radius: 12px;
            padding: 20px;
            margin-bottom: 20px;
            box-shadow: 0 3px 12px rgba(0,0,0,0.08);
            border: 1px solid #dfe3e8;
            position: relative;
        }
        .slide-header {
            display: flex;
            justify-content: flex-end;
            margin-bottom: 12px;
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
            font-size: 15px;
            min-height: 120px;
        }
        .slide-text {
            margin: 6px 0;
        }
    """
}
