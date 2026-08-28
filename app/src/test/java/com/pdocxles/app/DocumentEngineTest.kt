package com.pdocxles.app

import com.pdocxles.app.engine.xlsx.FastExcelEngine
import com.pdocxles.app.model.DocumentItem
import com.pdocxles.app.model.DocumentType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class DocumentEngineTest {

    @Test
    fun testDocumentTypeResolution() {
        assertEquals(DocumentType.PDF, DocumentType.fromFileName("invoice.pdf"))
        assertEquals(DocumentType.PDF, DocumentType.fromFileName("REPORT.PDF"))
        assertEquals(DocumentType.DOCX, DocumentType.fromFileName("document.docx"))
        assertEquals(DocumentType.XLSX, DocumentType.fromFileName("sheet.xlsx"))
        assertEquals(DocumentType.PPTX, DocumentType.fromFileName("presentation.pptx"))
        assertEquals(DocumentType.UNKNOWN, DocumentType.fromFileName("image.png"))

        assertEquals(DocumentType.PDF, DocumentType.fromMimeType("application/pdf"))
        assertEquals(DocumentType.DOCX, DocumentType.fromMimeType("application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
        assertEquals(DocumentType.XLSX, DocumentType.fromMimeType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
        assertEquals(DocumentType.PPTX, DocumentType.fromMimeType("application/vnd.openxmlformats-officedocument.presentationml.presentation"))
    }

    @Test
    fun testDocumentItemFormatting() {
        val itemBytes = DocumentItem(
            name = "test.pdf",
            path = "/tmp/test.pdf",
            type = DocumentType.PDF,
            sizeBytes = 500,
            lastModified = System.currentTimeMillis()
        )
        assertEquals("500 B", itemBytes.formattedSize)

        val itemKb = DocumentItem(
            name = "test.docx",
            path = "/tmp/test.docx",
            type = DocumentType.DOCX,
            sizeBytes = 1024 * 50,
            lastModified = System.currentTimeMillis()
        )
        assertEquals("50.0 KB", itemKb.formattedSize)

        val itemMb = DocumentItem(
            name = "test.xlsx",
            path = "/tmp/test.xlsx",
            type = DocumentType.XLSX,
            sizeBytes = 1024 * 1024 * 5,
            lastModified = System.currentTimeMillis()
        )
        assertEquals("5.00 MB", itemMb.formattedSize)
    }

    @Test
    fun testNativeXlsxEngine() = runBlocking {
        val tempFile = File.createTempFile("test_sample", ".xlsx")
        try {
            ZipOutputStream(FileOutputStream(tempFile)).use { zos ->
                // 1. xl/_rels/workbook.xml.rels
                zos.putNextEntry(ZipEntry("xl/_rels/workbook.xml.rels"))
                zos.write("""<?xml version="1.0" encoding="UTF-8"?>
                    <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                        <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
                    </Relationships>
                """.trimIndent().toByteArray())
                zos.closeEntry()

                // 2. xl/workbook.xml
                zos.putNextEntry(ZipEntry("xl/workbook.xml"))
                zos.write("""<?xml version="1.0" encoding="UTF-8"?>
                    <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
                        <sheets>
                            <sheet name="Financials" sheetId="1" r:id="rId1"/>
                        </sheets>
                    </workbook>
                """.trimIndent().toByteArray())
                zos.closeEntry()

                // 3. xl/sharedStrings.xml
                zos.putNextEntry(ZipEntry("xl/sharedStrings.xml"))
                zos.write("""<?xml version="1.0" encoding="UTF-8"?>
                    <sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" count="2" uniqueCount="2">
                        <si><t>Revenue</t></si>
                        <si><t>Profit</t></si>
                    </sst>
                """.trimIndent().toByteArray())
                zos.closeEntry()

                // 4. xl/worksheets/sheet1.xml
                zos.putNextEntry(ZipEntry("xl/worksheets/sheet1.xml"))
                zos.write("""<?xml version="1.0" encoding="UTF-8"?>
                    <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                        <sheetData>
                            <row r="1">
                                <c r="A1" t="s"><v>0</v></c>
                                <c r="B1"><v>150000</v></c>
                            </row>
                            <row r="2">
                                <c r="A2" t="s"><v>1</v></c>
                                <c r="B2"><v>45000.50</v></c>
                            </row>
                        </sheetData>
                    </worksheet>
                """.trimIndent().toByteArray())
                zos.closeEntry()
            }

            val engine = FastExcelEngine(tempFile)
            val sheetsRes = engine.getSheetList()
            if (sheetsRes.isFailure) {
                sheetsRes.exceptionOrNull()?.printStackTrace()
                throw sheetsRes.exceptionOrNull()!!
            }
            val sheets = sheetsRes.getOrNull()!!
            assertEquals(1, sheets.size)
            assertEquals("Financials", sheets[0].name)

            val dataRes = engine.loadSheetData(0)
            if (dataRes.isFailure) {
                dataRes.exceptionOrNull()?.printStackTrace()
                throw dataRes.exceptionOrNull()!!
            }
            val data = dataRes.getOrNull()!!
            assertEquals(2, data.rows.size)
            assertEquals("Revenue", data.rows[0][0])
            assertEquals("150000", data.rows[0][1])
            assertEquals("Profit", data.rows[1][0])
            assertEquals("45000.5", data.rows[1][1])
        } finally {
            tempFile.delete()
        }
    }
}
