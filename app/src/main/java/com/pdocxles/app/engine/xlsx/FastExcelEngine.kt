package com.pdocxles.app.engine.xlsx

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.InputStream
import java.math.BigDecimal
import java.util.zip.ZipFile

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

/**
 * 100% Native OpenXML XLSX streaming reader for Android.
 * Uses Android's built-in XmlPullParser with strict sharedStrings phonetic filtering
 * and clean cell value extraction.
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
                    // Fallback: search worksheet entries in zip
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

    suspend fun loadSheetData(sheetIndex: Int, maxRowsLimit: Int = 1000): Result<SheetData> = withContext(Dispatchers.IO) {
        try {
            if (!file.exists() || file.length() == 0L) {
                return@withContext Result.failure(IllegalArgumentException("XLSX file is empty or not found"))
            }

            ZipFile(file).use { zip ->
                val sheetListRes = getSheetList()
                val sheetList = sheetListRes.getOrNull() ?: emptyList()
                val targetSheet = sheetList.getOrNull(sheetIndex)

                val targetPath = targetSheet?.targetPath?.ifBlank { null }
                    ?: "xl/worksheets/sheet${sheetIndex + 1}.xml"

                val sheetEntry = zip.getEntry(targetPath)
                    ?: zip.getEntry("xl/worksheets/sheet${sheetIndex + 1}.xml")
                    ?: return@withContext Result.failure(IllegalArgumentException("Worksheet entry not found in XLSX archive"))

                val sheetInfo = targetSheet ?: SheetInfo(index = sheetIndex, name = "Sheet ${sheetIndex + 1}")

                // 1. Extract Shared Strings table with strict phonetic and styling filtering
                val sharedStrings = extractSharedStrings(zip)

                // 2. Parse worksheet XML
                val rowsList = mutableListOf<List<String>>()
                var maxCols = 0

                zip.getInputStream(sheetEntry).use { stream ->
                    val parser = createXmlParser(stream)
                    var event = parser.eventType

                    val currentRowCells = mutableMapOf<Int, String>() // colIndex -> value
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
                                    "v" -> {
                                        isInsideValueTag = true
                                    }
                                    "t" -> {
                                        isInsideInlineText = true
                                    }
                                    "f" -> {
                                        // Ignore formula definition text so it doesn't pollute value
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
                                    "v" -> {
                                        isInsideValueTag = false
                                    }
                                    "t" -> {
                                        isInsideInlineText = false
                                    }
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

                // Normalize all rows to maxCols
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
                                "rPh", "phoneticPr" -> {
                                    inPhonetic = true
                                }
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
                                "t" -> {
                                    inTextTag = false
                                }
                                "rPh", "phoneticPr" -> {
                                    inPhonetic = false
                                }
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
                "b" -> {
                    if (rawValue == "1" || rawValue.equals("true", ignoreCase = true)) "TRUE" else "FALSE"
                }
                "str", "inlineStr" -> rawValue
                "e" -> rawValue // Formula error text (#N/A, #VALUE!)
                "d" -> rawValue // Date string
                else -> {
                    // Numeric value
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
