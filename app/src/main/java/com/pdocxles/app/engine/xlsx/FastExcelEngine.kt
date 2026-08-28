package com.pdocxles.app.engine.xlsx

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.InputStream
import java.math.BigDecimal
import java.util.zip.ZipFile
import kotlin.math.roundToInt

data class SheetInfo(
    val index: Int,
    val name: String,
    val targetPath: String = ""
)

data class SheetData(
    val sheetInfo: SheetInfo,
    val rows: List<List<String>>,
    val maxColumns: Int
)

data class CellStyle(
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val fontColorHex: String? = null,
    val fontSizePt: Int? = null,
    val bgColorHex: String? = null,
    val alignH: String? = null,
    val alignV: String? = null
)

data class MergedCell(
    val startCol: Int,
    val startRow: Int,
    val colSpan: Int,
    val rowSpan: Int
)

/**
 * 100% Native OpenXML XLSX streaming reader and spreadsheet engine for Android.
 * Extracts styles (fonts, fills, alignments), merged cells, column widths,
 * and renders high-performance HTML/CSS spreadsheets with native pinch-zoom.
 */
class FastExcelEngine(private val file: File) {

    suspend fun getSheetList(): Result<List<SheetInfo>> = withContext(Dispatchers.IO) {
        try {
            if (!file.exists() || file.length() == 0L) {
                return@withContext Result.failure(IllegalArgumentException("XLSX file is empty or not found: ${file.absolutePath}"))
            }

            ZipFile(file).use { zip ->
                val relsMap = extractWorkbookRels(zip)
                val workbookEntry = zip.getEntry("xl/workbook.xml")
                    ?: return@withContext Result.failure(IllegalArgumentException("Invalid XLSX: missing xl/workbook.xml"))

                val sheets = mutableListOf<SheetInfo>()
                zip.getInputStream(workbookEntry).use { stream ->
                    val parser = createXmlParser(stream)
                    var event = parser.eventType
                    var index = 0

                    while (event != XmlPullParser.END_DOCUMENT) {
                        if (event == XmlPullParser.START_TAG) {
                            val tagName = parser.name?.substringAfterLast(':') ?: ""
                            if (tagName == "sheet") {
                                val name = getAttributeAny(parser, "name") ?: "Sheet ${index + 1}"
                                val rId = getAttributeAny(parser, "r:id", "id")
                                val target = if (rId != null) relsMap[rId] ?: relsMap[rId.lowercase()] else null
                                val normalizedTarget = if (target != null) {
                                    if (target.startsWith("/")) target.removePrefix("/")
                                    else if (!target.startsWith("xl/")) "xl/$target"
                                    else target
                                } else {
                                    "xl/worksheets/sheet${index + 1}.xml"
                                }

                                sheets.add(SheetInfo(index = index++, name = name, targetPath = normalizedTarget))
                            }
                        }
                        event = parser.next()
                    }
                }

                if (sheets.isEmpty()) {
                    val wsEntries = zip.entries().asSequence()
                        .filter { it.name.startsWith("xl/worksheets/sheet") && it.name.endsWith(".xml") }
                        .sortedBy { it.name }
                        .toList()
                    wsEntries.forEachIndexed { i, entry ->
                        sheets.add(SheetInfo(index = i, name = "Sheet ${i + 1}", targetPath = entry.name))
                    }
                }

                Result.success(sheets)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Result.failure(e)
        }
    }

    suspend fun convertSheetToHtml(sheetIndex: Int, maxRowsLimit: Int = 1500): Result<String> = withContext(Dispatchers.IO) {
        try {
            if (!file.exists() || file.length() == 0L) {
                return@withContext Result.failure(IllegalArgumentException("XLSX file is empty or not found"))
            }

            ZipFile(file).use { zip ->
                val sheetList = getSheetList().getOrNull() ?: emptyList()
                val targetSheet = sheetList.getOrNull(sheetIndex)
                val targetPath = targetSheet?.targetPath?.ifBlank { null }
                    ?: "xl/worksheets/sheet${sheetIndex + 1}.xml"

                val sheetEntry = zip.getEntry(targetPath)
                    ?: zip.getEntry("xl/worksheets/sheet${sheetIndex + 1}.xml")
                    ?: return@withContext Result.failure(IllegalArgumentException("Worksheet not found in archive: $targetPath"))

                val sharedStrings = extractSharedStrings(zip)
                val styles = extractStyles(zip)
                val (mergedCellsMap, coveredCells) = extractMergedCells(zip, targetPath)
                val colWidthsMap = extractColumnWidths(zip, targetPath)

                val html = parseSheetXmlToHtml(
                    sheetStream = zip.getInputStream(sheetEntry),
                    sheetName = targetSheet?.name ?: "Sheet ${sheetIndex + 1}",
                    sharedStrings = sharedStrings,
                    styles = styles,
                    mergedCellsMap = mergedCellsMap,
                    coveredCells = coveredCells,
                    colWidthsMap = colWidthsMap,
                    maxRowsLimit = maxRowsLimit
                )

                Result.success(html)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Result.failure(e)
        }
    }

    suspend fun loadSheetData(sheetIndex: Int, maxRowsLimit: Int = 1000): Result<SheetData> = withContext(Dispatchers.IO) {
        try {
            if (!file.exists() || file.length() == 0L) {
                return@withContext Result.failure(IllegalArgumentException("XLSX file is empty or not found"))
            }

            ZipFile(file).use { zip ->
                val sheetList = getSheetList().getOrNull() ?: emptyList()
                val targetSheet = sheetList.getOrNull(sheetIndex)
                val targetPath = targetSheet?.targetPath?.ifBlank { null }
                    ?: "xl/worksheets/sheet${sheetIndex + 1}.xml"

                val sheetEntry = zip.getEntry(targetPath)
                    ?: zip.getEntry("xl/worksheets/sheet${sheetIndex + 1}.xml")
                    ?: return@withContext Result.failure(IllegalArgumentException("Worksheet entry not found in XLSX archive"))

                val sheetInfo = targetSheet ?: SheetInfo(index = sheetIndex, name = "Sheet ${sheetIndex + 1}")
                val sharedStrings = extractSharedStrings(zip)

                val rowsList = mutableListOf<List<String>>()
                var maxCols = 0

                zip.getInputStream(sheetEntry).use { stream ->
                    val parser = createXmlParser(stream)
                    var event = parser.eventType

                    val currentRowCells = mutableMapOf<Int, String>()
                    var currentCellCol = -1
                    var currentCellType: String? = null
                    var isInsideValueTag = false
                    var isInsideInlineText = false
                    val cellTextBuilder = StringBuilder()

                    while (event != XmlPullParser.END_DOCUMENT) {
                        val tagName = parser.name?.substringAfterLast(':') ?: ""

                        when (event) {
                            XmlPullParser.START_TAG -> {
                                when (tagName) {
                                    "row" -> {
                                        currentRowCells.clear()
                                        currentCellCol = -1
                                    }
                                    "c" -> {
                                        cellTextBuilder.clear()
                                        val cellRef = getAttributeAny(parser, "r")
                                        currentCellType = getAttributeAny(parser, "t")
                                        currentCellCol = if (cellRef != null) {
                                            parseColumnFromRef(cellRef)
                                        } else {
                                            currentCellCol + 1
                                        }
                                        isInsideValueTag = false
                                        isInsideInlineText = false
                                    }
                                    "v" -> isInsideValueTag = true
                                    "t" -> isInsideInlineText = true
                                    "f" -> {
                                        isInsideValueTag = false
                                        isInsideInlineText = false
                                    }
                                }
                            }
                            XmlPullParser.TEXT -> {
                                if (isInsideValueTag || isInsideInlineText) {
                                    cellTextBuilder.append(parser.text)
                                }
                            }
                            XmlPullParser.END_TAG -> {
                                when (tagName) {
                                    "v" -> isInsideValueTag = false
                                    "t" -> isInsideInlineText = false
                                    "c" -> {
                                        if (currentCellCol >= 0) {
                                            val rawValue = cellTextBuilder.toString().trim()
                                            val formattedValue = formatCellValue(rawValue, currentCellType, sharedStrings)
                                            currentRowCells[currentCellCol] = formattedValue
                                            maxCols = maxOf(maxCols, currentCellCol + 1)
                                        }
                                        currentCellType = null
                                        isInsideValueTag = false
                                        isInsideInlineText = false
                                    }
                                    "row" -> {
                                        if (currentRowCells.isNotEmpty()) {
                                            val maxIdx = (currentRowCells.keys.maxOrNull() ?: -1) + 1
                                            val row = ArrayList<String>(maxIdx)
                                            for (c in 0 until maxIdx) {
                                                row.add(currentRowCells[c] ?: "")
                                            }
                                            rowsList.add(row)
                                            maxCols = maxOf(maxCols, maxIdx)
                                        } else {
                                            rowsList.add(emptyList())
                                        }

                                        if (rowsList.size >= maxRowsLimit) {
                                            break
                                        }
                                    }
                                }
                            }
                        }
                        event = parser.next()
                    }
                }

                val normalizedRows = rowsList.map { row ->
                    if (row.size < maxCols) {
                        row + List(maxCols - row.size) { "" }
                    } else {
                        row
                    }
                }

                Result.success(SheetData(sheetInfo, normalizedRows, maxCols))
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Result.failure(e)
        }
    }

    private data class ParsedCellData(
        val value: String,
        val styleId: Int,
        val colIndex: Int
    )

    private fun parseSheetXmlToHtml(
        sheetStream: InputStream,
        sheetName: String,
        sharedStrings: List<String>,
        styles: List<CellStyle>,
        mergedCellsMap: Map<String, MergedCell>, // "col,row" -> MergedCell
        coveredCells: Set<String>, // "col,row" covered by a merge
        colWidthsMap: Map<Int, Int>, // colIndex -> px width
        maxRowsLimit: Int
    ): String {
        val parser = createXmlParser(sheetStream)
        var event = parser.eventType

        val rows = mutableListOf<Pair<Int, Map<Int, ParsedCellData>>>() // rowIndex -> (colIndex -> ParsedCellData)
        var maxColIndex = 0

        var currentRowIdx = 0
        val currentRowCells = mutableMapOf<Int, ParsedCellData>()
        var currentCellCol = -1
        var currentCellType: String? = null
        var currentCellStyleId = 0
        var isInsideValueTag = false
        var isInsideInlineText = false
        val cellTextBuilder = StringBuilder()

        while (event != XmlPullParser.END_DOCUMENT) {
            val tagName = parser.name?.substringAfterLast(':') ?: ""

            when (event) {
                XmlPullParser.START_TAG -> {
                    when (tagName) {
                        "row" -> {
                            currentRowCells.clear()
                            val rVal = getAttributeAny(parser, "r")?.toIntOrNull()
                            currentRowIdx = if (rVal != null) rVal - 1 else currentRowIdx + 1
                            currentCellCol = -1
                        }
                        "c" -> {
                            cellTextBuilder.clear()
                            val cellRef = getAttributeAny(parser, "r")
                            currentCellType = getAttributeAny(parser, "t")
                            currentCellStyleId = getAttributeAny(parser, "s")?.toIntOrNull() ?: 0

                            currentCellCol = if (cellRef != null) {
                                val col = parseColumnFromRef(cellRef)
                                val row = parseRowFromRef(cellRef)
                                if (row >= 0) currentRowIdx = row
                                col
                            } else {
                                currentCellCol + 1
                            }
                            isInsideValueTag = false
                            isInsideInlineText = false
                        }
                        "v" -> isInsideValueTag = true
                        "t" -> isInsideInlineText = true
                        "f" -> {
                            isInsideValueTag = false
                            isInsideInlineText = false
                        }
                    }
                }
                XmlPullParser.TEXT -> {
                    if (isInsideValueTag || isInsideInlineText) {
                        cellTextBuilder.append(parser.text)
                    }
                }
                XmlPullParser.END_TAG -> {
                    when (tagName) {
                        "v" -> isInsideValueTag = false
                        "t" -> isInsideInlineText = false
                        "c" -> {
                            if (currentCellCol >= 0) {
                                val rawValue = cellTextBuilder.toString().trim()
                                val formattedValue = formatCellValue(rawValue, currentCellType, sharedStrings)
                                currentRowCells[currentCellCol] = ParsedCellData(
                                    value = formattedValue,
                                    styleId = currentCellStyleId,
                                    colIndex = currentCellCol
                                )
                                maxColIndex = maxOf(maxColIndex, currentCellCol + 1)
                            }
                            currentCellType = null
                            currentCellStyleId = 0
                            isInsideValueTag = false
                            isInsideInlineText = false
                        }
                        "row" -> {
                            if (currentRowCells.isNotEmpty()) {
                                rows.add(currentRowIdx to currentRowCells.toMap())
                            }
                            if (rows.size >= maxRowsLimit) {
                                break
                            }
                        }
                    }
                }
            }
            event = parser.next()
        }

        // Build responsive, pixel-perfect HTML spreadsheet
        val sb = StringBuilder()
        sb.append("""
            <!DOCTYPE html>
            <html lang="en">
            <head>
                <meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=5.0, user-scalable=yes">
                <title>${escapeHtml(sheetName)}</title>
                <style>
                    * {
                        box-sizing: border-box;
                        -webkit-tap-highlight-color: transparent;
                        touch-action: pan-x pan-y pinch-zoom;
                    }
                    body {
                        margin: 0;
                        padding: 0;
                        font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", "Calibri", "Carlito", "Liberation Sans", Roboto, Helvetica, Arial, sans-serif;
                        font-size: 13px;
                        background-color: #f3f4f6;
                        color: #1e293b;
                        overflow: auto;
                    }
                    .table-wrapper {
                        display: inline-block;
                        min-width: 100%;
                        background: #ffffff;
                        padding-bottom: 24px;
                    }
                    table.excel-table {
                        border-collapse: collapse;
                        table-layout: fixed;
                        background-color: #ffffff;
                    }
                    table.excel-table th, table.excel-table td {
                        border: 1px solid #d0d7de;
                        padding: 6px 8px;
                        font-size: 13px;
                        line-height: 1.35;
                        white-space: pre-wrap;
                        word-break: break-word;
                        overflow-wrap: break-word;
                        vertical-align: middle;
                    }
                    /* Sticky top headers (#, A, B, C...) */
                    th.header-col {
                        background-color: #f1f5f9;
                        color: #475569;
                        font-weight: 600;
                        text-align: center;
                        border: 1px solid #cbd5e1;
                        position: sticky;
                        top: 0;
                        z-index: 10;
                        user-select: none;
                        font-size: 11.5px;
                        height: 28px;
                    }
                    /* Sticky left row numbers (1, 2, 3...) */
                    th.header-row {
                        background-color: #f1f5f9;
                        color: #64748b;
                        font-weight: 500;
                        text-align: center;
                        border: 1px solid #cbd5e1;
                        position: sticky;
                        left: 0;
                        z-index: 9;
                        width: 44px;
                        min-width: 44px;
                        max-width: 44px;
                        user-select: none;
                        font-size: 11px;
                    }
                    th.corner-cell {
                        position: sticky;
                        top: 0;
                        left: 0;
                        z-index: 20;
                        background-color: #e2e8f0;
                        width: 44px;
                        min-width: 44px;
                    }
                    td.cell-empty {
                        color: transparent;
                    }
                </style>
            </head>
            <body>
                <div class="table-wrapper">
                    <table class="excel-table">
        """.trimIndent())

        // Colgroup with widths
        sb.append("<colgroup>")
        sb.append("<col style=\"width: 44px;\" />") // Row numbers col
        for (c in 0 until maxColIndex) {
            val wPx = colWidthsMap[c] ?: 95
            sb.append("<col style=\"width: ${wPx}px; min-width: ${wPx}px;\" />")
        }
        sb.append("</colgroup>")

        // Header Row (Corner, A, B, C...)
        sb.append("<thead><tr>")
        sb.append("<th class=\"header-col corner-cell\">#</th>")
        for (c in 0 until maxColIndex) {
            sb.append("<th class=\"header-col\">${getColumnLetter(c)}</th>")
        }
        sb.append("</tr></thead>")

        // Rows
        sb.append("<tbody>")
        for ((rIdx, cellMap) in rows) {
            sb.append("<tr>")
            sb.append("<th class=\"header-row\">${rIdx + 1}</th>")

            for (c in 0 until maxColIndex) {
                val coordKey = "$c,$rIdx"
                if (coveredCells.contains(coordKey)) {
                    continue // Skip covered cell by merge
                }

                val merge = mergedCellsMap[coordKey]
                val spanAttr = StringBuilder()
                if (merge != null) {
                    if (merge.colSpan > 1) spanAttr.append(" colspan=\"${merge.colSpan}\"")
                    if (merge.rowSpan > 1) spanAttr.append(" rowspan=\"${merge.rowSpan}\"")
                }

                val cellData = cellMap[c]
                if (cellData != null && cellData.value.isNotEmpty()) {
                    val style = styles.getOrNull(cellData.styleId)
                    val styleRules = StringBuilder()

                    if (style != null) {
                        if (style.bold) styleRules.append("font-weight: bold; ")
                        if (style.italic) styleRules.append("font-style: italic; ")
                        if (style.underline) styleRules.append("text-decoration: underline; ")
                        if (style.fontColorHex != null) styleRules.append("color: ${style.fontColorHex}; ")
                        if (style.bgColorHex != null) styleRules.append("background-color: ${style.bgColorHex}; ")
                        if (style.fontSizePt != null) styleRules.append("font-size: ${style.fontSizePt}pt; ")
                        if (style.alignH != null) styleRules.append("text-align: ${style.alignH}; ")
                        if (style.alignV != null) styleRules.append("vertical-align: ${style.alignV}; ")
                    }

                    // Default alignment if not explicitly specified: numbers right-aligned, text left-aligned
                    if (style?.alignH == null) {
                        if (cellData.value.toDoubleOrNull() != null) {
                            styleRules.append("text-align: right; ")
                        }
                    }

                    val styleAttr = if (styleRules.isNotEmpty()) " style=\"$styleRules\"" else ""
                    val escapedText = escapeHtml(cellData.value)
                    sb.append("<td$spanAttr$styleAttr>$escapedText</td>")
                } else {
                    val style = if (cellData != null) styles.getOrNull(cellData.styleId) else null
                    val bgStyle = if (style?.bgColorHex != null) " style=\"background-color: ${style.bgColorHex};\"" else ""
                    sb.append("<td$spanAttr$bgStyle class=\"cell-empty\">&nbsp;</td>")
                }
            }
            sb.append("</tr>")
        }
        sb.append("</tbody></table></div></body></html>")

        return sb.toString()
    }

    /**
     * Extracts styles from xl/styles.xml:
     * Fonts (bold, italic, underline, color, size), Fills (cell background color),
     * and CellXfs (maps styleId -> font, fill, horizontal & vertical alignment).
     */
    private fun extractStyles(zip: ZipFile): List<CellStyle> {
        val entry = zip.getEntry("xl/styles.xml") ?: return emptyList()
        val stylesList = mutableListOf<CellStyle>()

        try {
            zip.getInputStream(entry).use { stream ->
                val parser = createXmlParser(stream)
                var event = parser.eventType

                val fonts = mutableListOf<CellStyle>()
                val fills = mutableListOf<String?>() // background hex colors

                var inFonts = false
                var inFills = false
                var inCellXfs = false

                var currentFontBold = false
                var currentFontItalic = false
                var currentFontUnderline = false
                var currentFontColor: String? = null
                var currentFontSize: Int? = null

                var currentFillColor: String? = null

                while (event != XmlPullParser.END_DOCUMENT) {
                    val tagName = parser.name?.substringAfterLast(':') ?: ""

                    when (event) {
                        XmlPullParser.START_TAG -> {
                            when (tagName) {
                                "fonts" -> inFonts = true
                                "font" -> {
                                    currentFontBold = false
                                    currentFontItalic = false
                                    currentFontUnderline = false
                                    currentFontColor = null
                                    currentFontSize = null
                                }
                                "b" -> if (inFonts) currentFontBold = true
                                "i" -> if (inFonts) currentFontItalic = true
                                "u" -> if (inFonts) currentFontUnderline = true
                                "sz" -> if (inFonts) currentFontSize = getAttributeAny(parser, "val")?.toDoubleOrNull()?.roundToInt()
                                "color" -> {
                                    if (inFonts) {
                                        val rgb = getAttributeAny(parser, "rgb")
                                        if (!rgb.isNullOrBlank()) {
                                            val cleanRgb = if (rgb.length == 8) rgb.substring(2) else rgb
                                            currentFontColor = "#$cleanRgb"
                                        }
                                    }
                                }
                                "fills" -> inFills = true
                                "fill" -> currentFillColor = null
                                "fgColor", "bgColor" -> {
                                    if (inFills) {
                                        val rgb = getAttributeAny(parser, "rgb")
                                        if (!rgb.isNullOrBlank()) {
                                            val cleanRgb = if (rgb.length == 8) rgb.substring(2) else rgb
                                            currentFillColor = "#$cleanRgb"
                                        }
                                    }
                                }
                                "cellXfs" -> inCellXfs = true
                                "xf" -> {
                                    if (inCellXfs) {
                                        val fontId = getAttributeAny(parser, "fontId")?.toIntOrNull() ?: 0
                                        val fillId = getAttributeAny(parser, "fillId")?.toIntOrNull() ?: 0
                                        val fontStyle = fonts.getOrNull(fontId)
                                        val bgHex = fills.getOrNull(fillId)

                                        stylesList.add(
                                            CellStyle(
                                                bold = fontStyle?.bold ?: false,
                                                italic = fontStyle?.italic ?: false,
                                                underline = fontStyle?.underline ?: false,
                                                fontColorHex = fontStyle?.fontColorHex,
                                                fontSizePt = fontStyle?.fontSizePt,
                                                bgColorHex = bgHex
                                            )
                                        )
                                    }
                                }
                                "alignment" -> {
                                    if (inCellXfs && stylesList.isNotEmpty()) {
                                        val lastIdx = stylesList.size - 1
                                        val prev = stylesList[lastIdx]
                                        val hAlign = getAttributeAny(parser, "horizontal")?.lowercase()
                                        val vAlign = getAttributeAny(parser, "vertical")?.lowercase()

                                        val mappedH = when (hAlign) {
                                            "center" -> "center"
                                            "right" -> "right"
                                            "justify" -> "justify"
                                            "left" -> "left"
                                            else -> null
                                        }
                                        val mappedV = when (vAlign) {
                                            "center" -> "middle"
                                            "top" -> "top"
                                            "bottom" -> "bottom"
                                            else -> null
                                        }

                                        stylesList[lastIdx] = prev.copy(alignH = mappedH, alignV = mappedV)
                                    }
                                }
                            }
                        }
                        XmlPullParser.END_TAG -> {
                            when (tagName) {
                                "fonts" -> inFonts = false
                                "font" -> {
                                    if (inFonts) {
                                        fonts.add(
                                            CellStyle(
                                                bold = currentFontBold,
                                                italic = currentFontItalic,
                                                underline = currentFontUnderline,
                                                fontColorHex = currentFontColor,
                                                fontSizePt = currentFontSize
                                            )
                                        )
                                    }
                                }
                                "fills" -> inFills = false
                                "fill" -> {
                                    if (inFills) {
                                        fills.add(currentFillColor)
                                    }
                                }
                                "cellXfs" -> inCellXfs = false
                            }
                        }
                    }
                    event = parser.next()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return stylesList
    }

    /**
     * Extracts merged cells from sheet XML (e.g. <mergeCell ref="A1:C2"/>).
     */
    private fun extractMergedCells(zip: ZipFile, sheetPath: String): Pair<Map<String, MergedCell>, Set<String>> {
        val mergedMap = mutableMapOf<String, MergedCell>()
        val coveredSet = mutableSetOf<String>()
        val entry = zip.getEntry(sheetPath) ?: return mergedMap to coveredSet

        try {
            zip.getInputStream(entry).use { stream ->
                val parser = createXmlParser(stream)
                var event = parser.eventType

                while (event != XmlPullParser.END_DOCUMENT) {
                    if (event == XmlPullParser.START_TAG && (parser.name?.endsWith("mergeCell") == true)) {
                        val ref = getAttributeAny(parser, "ref")
                        if (ref != null && ref.contains(':')) {
                            val parts = ref.split(':')
                            val fromCol = parseColumnFromRef(parts[0])
                            val fromRow = parseRowFromRef(parts[0])
                            val toCol = parseColumnFromRef(parts[1])
                            val toRow = parseRowFromRef(parts[1])

                            val colSpan = (toCol - fromCol + 1).coerceAtLeast(1)
                            val rowSpan = (toRow - fromRow + 1).coerceAtLeast(1)

                            val startKey = "$fromCol,$fromRow"
                            mergedMap[startKey] = MergedCell(fromCol, fromRow, colSpan, rowSpan)

                            // Mark all covered cells
                            for (r in fromRow..toRow) {
                                for (c in fromCol..toCol) {
                                    if (r != fromRow || c != fromCol) {
                                        coveredSet.add("$c,$r")
                                    }
                                }
                            }
                        }
                    }
                    event = parser.next()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return mergedMap to coveredSet
    }

    /**
     * Extracts custom column widths from sheet XML (<cols><col min="1" max="1" width="20"/></cols>).
     */
    private fun extractColumnWidths(zip: ZipFile, sheetPath: String): Map<Int, Int> {
        val map = mutableMapOf<Int, Int>()
        val entry = zip.getEntry(sheetPath) ?: return map

        try {
            zip.getInputStream(entry).use { stream ->
                val parser = createXmlParser(stream)
                var event = parser.eventType

                while (event != XmlPullParser.END_DOCUMENT) {
                    if (event == XmlPullParser.START_TAG && (parser.name?.endsWith("col") == true)) {
                        val min = getAttributeAny(parser, "min")?.toIntOrNull()
                        val max = getAttributeAny(parser, "max")?.toIntOrNull()
                        val widthVal = getAttributeAny(parser, "width")?.toDoubleOrNull()

                        if (min != null && max != null && widthVal != null) {
                            val widthPx = (widthVal * 8.5).roundToInt().coerceIn(50, 450)
                            for (c in min..max) {
                                map[c - 1] = widthPx
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
     * Extracts Shared Strings with strict filtering:
     * Appends text ONLY from `<t>` tags, ignoring `<rPh>`, `<phoneticPr>`, `<rPr>` formatting tags.
     */
    private fun extractSharedStrings(zip: ZipFile): List<String> {
        val entry = zip.getEntry("xl/sharedStrings.xml") ?: return emptyList()
        val list = mutableListOf<String>()

        try {
            zip.getInputStream(entry).use { stream ->
                val parser = createXmlParser(stream)
                var event = parser.eventType
                var inStringItem = false
                var inTextTag = false
                var inPhonetic = false
                val currentString = StringBuilder()

                while (event != XmlPullParser.END_DOCUMENT) {
                    val tagName = parser.name?.substringAfterLast(':') ?: ""
                    when (event) {
                        XmlPullParser.START_TAG -> {
                            when (tagName) {
                                "si" -> {
                                    inStringItem = true
                                    currentString.clear()
                                }
                                "rPh", "phoneticPr" -> inPhonetic = true
                                "t" -> {
                                    if (inStringItem && !inPhonetic) {
                                        inTextTag = true
                                    }
                                }
                            }
                        }
                        XmlPullParser.TEXT -> {
                            if (inTextTag && !inPhonetic) {
                                currentString.append(parser.text)
                            }
                        }
                        XmlPullParser.END_TAG -> {
                            when (tagName) {
                                "t" -> inTextTag = false
                                "rPh", "phoneticPr" -> inPhonetic = false
                                "si" -> {
                                    inStringItem = false
                                    inTextTag = false
                                    inPhonetic = false
                                    list.add(currentString.toString())
                                }
                            }
                        }
                    }
                    event = parser.next()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }

    private fun extractWorkbookRels(zip: ZipFile): Map<String, String> {
        val map = mutableMapOf<String, String>()
        val entry = zip.getEntry("xl/_rels/workbook.xml.rels") ?: return map

        try {
            zip.getInputStream(entry).use { stream ->
                val parser = createXmlParser(stream)
                var event = parser.eventType
                while (event != XmlPullParser.END_DOCUMENT) {
                    if (event == XmlPullParser.START_TAG && (parser.name == "Relationship" || parser.name?.endsWith(":Relationship") == true)) {
                        val id = getAttributeAny(parser, "Id", "id")
                        val target = getAttributeAny(parser, "Target", "target")
                        if (id != null && target != null) {
                            map[id] = target
                            map[id.lowercase()] = target
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

    private fun formatCellValue(rawValue: String, cellType: String?, sharedStrings: List<String>): String {
        if (rawValue.isBlank()) return ""
        return try {
            when (cellType) {
                "s" -> {
                    val idx = rawValue.toIntOrNull()
                    if (idx != null && idx in sharedStrings.indices) {
                        sharedStrings[idx]
                    } else {
                        rawValue
                    }
                }
                "b" -> if (rawValue == "1" || rawValue.equals("true", ignoreCase = true)) "TRUE" else "FALSE"
                "str", "inlineStr" -> rawValue
                "e" -> rawValue // Formula error (#N/A, #VALUE!)
                "d" -> rawValue // Date
                else -> {
                    val doubleVal = rawValue.toDoubleOrNull()
                    if (doubleVal != null) {
                        if (doubleVal == doubleVal.toLong().toDouble() && !doubleVal.isInfinite()) {
                            doubleVal.toLong().toString()
                        } else {
                            BigDecimal(rawValue).stripTrailingZeros().toPlainString()
                        }
                    } else {
                        rawValue
                    }
                }
            }
        } catch (e: Exception) {
            rawValue
        }
    }

    private fun parseColumnFromRef(ref: String): Int {
        val colLetters = ref.takeWhile { it.isLetter() }.uppercase()
        if (colLetters.isEmpty()) return 0
        var result = 0
        for (char in colLetters) {
            if (char in 'A'..'Z') {
                result = result * 26 + (char - 'A' + 1)
            }
        }
        return (result - 1).coerceAtLeast(0)
    }

    private fun parseRowFromRef(ref: String): Int {
        val rowDigits = ref.dropWhile { it.isLetter() }
        val rowNum = rowDigits.toIntOrNull() ?: return 0
        return (rowNum - 1).coerceAtLeast(0)
    }

    private fun getColumnLetter(colIndex: Int): String {
        var num = colIndex
        val sb = java.lang.StringBuilder()
        while (num >= 0) {
            val rem = num % 26
            sb.append(('A'.code + rem).toChar())
            num = (num / 26) - 1
        }
        return sb.reverse().toString()
    }

    private fun escapeHtml(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")
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
}
