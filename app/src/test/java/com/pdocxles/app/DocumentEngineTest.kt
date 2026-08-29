package com.pdocxles.app

import com.pdocxles.app.engine.openxml.OpenXmlHtmlEngine
import com.pdocxles.app.engine.xlsx.FastExcelEngine
import com.pdocxles.app.model.DocumentItem
import com.pdocxles.app.model.DocumentType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

                // 3. xl/sharedStrings.xml with phonetic guide
                zos.putNextEntry(ZipEntry("xl/sharedStrings.xml"))
                zos.write("""<?xml version="1.0" encoding="UTF-8"?>
                    <sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" count="2" uniqueCount="2">
                        <si>
                            <t>Revenue</t>
                            <rPh sb="0" eb="7"><t>phonetic_garbage</t></rPh>
                            <phoneticPr fontId="1"/>
                        </si>
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
            if (sheetsRes.isFailure) throw sheetsRes.exceptionOrNull()!!
            val sheets = sheetsRes.getOrNull()!!
            assertEquals(1, sheets.size)
            assertEquals("Financials", sheets[0].name)

            val dataRes = engine.loadSheetData(0)
            if (dataRes.isFailure) throw dataRes.exceptionOrNull()!!
            val data = dataRes.getOrNull()!!
            assertEquals(2, data.rows.size)
            assertEquals("Revenue", data.rows[0][0])
            assertEquals("150000", data.rows[0][1])
            assertEquals("Profit", data.rows[1][0])
            assertEquals("45000.5", data.rows[1][1])

            val htmlRes = engine.convertSheetToHtml(0)
            if (htmlRes.isFailure) throw htmlRes.exceptionOrNull()!!
            val html = htmlRes.getOrNull()!!
            assertTrue("Should contain table", html.contains("<table class=\"excel-table\">"))
            assertTrue("Should contain column header A", html.contains("<th class=\"header-col\">A</th>"))
            assertTrue("Should contain Revenue", html.contains("Revenue"))
            assertTrue("Should contain numeric alignment for 150000", html.contains("text-align: right;"))
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun testDocxRichTextStyles() = runBlocking {
        val tempFile = File.createTempFile("test_sample", ".docx")
        try {
            ZipOutputStream(FileOutputStream(tempFile)).use { zos ->
                // word/document.xml
                zos.putNextEntry(ZipEntry("word/document.xml"))
                zos.write("""<?xml version="1.0" encoding="UTF-8"?>
                    <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                        <w:body>
                            <w:p>
                                <w:pPr><w:jc w:val="center"/></w:pPr>
                                <w:r>
                                    <w:rPr>
                                        <w:b/>
                                        <w:color w:val="FF0000"/>
                                        <w:sz w:val="32"/>
                                    </w:rPr>
                                    <w:t>Centered Red Title</w:t>
                                </w:r>
                            </w:p>
                        </w:body>
                    </w:document>
                """.trimIndent().toByteArray())
                zos.closeEntry()
            }

            val res = OpenXmlHtmlEngine.convertDocxToHtml(tempFile)
            assertTrue(res.isSuccess)
            val html = res.getOrNull()!!
            assertTrue("Should contain bold", html.contains("<strong>"))
            assertTrue("Should contain text-align center", html.contains("text-align: center"))
            assertTrue("Should contain red color", html.contains("color: #FF0000"))
            assertTrue("Should contain 16pt font size", html.contains("font-size: 16pt"))
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun testPptxImagesAndStyles() = runBlocking {
        val tempFile = File.createTempFile("test_sample", ".pptx")
        try {
            ZipOutputStream(FileOutputStream(tempFile)).use { zos ->
                // ppt/media/image1.png (mock 1x1 png bytes)
                zos.putNextEntry(ZipEntry("ppt/media/image1.png"))
                zos.write(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10))
                zos.closeEntry()

                // ppt/slides/_rels/slide1.xml.rels
                zos.putNextEntry(ZipEntry("ppt/slides/_rels/slide1.xml.rels"))
                zos.write("""<?xml version="1.0" encoding="UTF-8"?>
                    <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                        <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/image" Target="../media/image1.png"/>
                    </Relationships>
                """.trimIndent().toByteArray())
                zos.closeEntry()

                // ppt/slides/slide1.xml
                zos.putNextEntry(ZipEntry("ppt/slides/slide1.xml"))
                zos.write("""<?xml version="1.0" encoding="UTF-8"?>
                    <p:sld xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
                        <p:cSld>
                            <p:spTree>
                                <p:sp>
                                    <p:txBody>
                                        <a:p>
                                            <a:pPr algn="ctr"/>
                                            <a:r>
                                                <a:rPr b="1" sz="2400"><a:solidFill><a:srgbClr val="008000"/></a:solidFill></a:rPr>
                                                <a:t>Slide Title</a:t>
                                            </a:r>
                                        </a:p>
                                    </p:txBody>
                                </p:sp>
                                <p:pic>
                                    <p:blipFill>
                                        <a:blip r:embed="rId2"/>
                                    </p:blipFill>
                                </p:pic>
                            </p:spTree>
                        </p:cSld>
                    </p:sld>
                """.trimIndent().toByteArray())
                zos.closeEntry()
            }

            val res = OpenXmlHtmlEngine.convertPptxToHtml(tempFile)
            if (res.isFailure) throw res.exceptionOrNull()!!
            val html = res.getOrNull()!!
            assertTrue("Should contain bold", html.contains("<strong>"))
            assertTrue("Should contain green color", html.contains("color: #008000"))
            assertTrue("Should contain 24pt font size", html.contains("font-size: 24pt"))
            assertTrue("Should contain embedded image data URI", html.contains("data:image/png;base64"))
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun testBakGreenVoltDocx() = runBlocking {
        val file = File("d:/Skalatskiy/Develop/Projects/Pdocxles/.BAK/Інструкція_на_портативну_зарядку_станцію_GreenVolt_new.docx")
        if (file.exists()) {
            val tempCacheDir = File(System.getProperty("java.io.tmpdir"), "test_docx_cache").apply { mkdirs() }
            try {
                val res = OpenXmlHtmlEngine.convertDocxToHtml(file, tempCacheDir)
                if (res.isFailure) {
                    val err = res.exceptionOrNull()
                    err?.printStackTrace()
                    throw err ?: Exception("Unknown error")
                }
                val html = res.getOrNull()!!
                val dumpFile = File("build/greenvolt_dump.html")
                dumpFile.writeText(html)
                println("GreenVolt HTML dumped to ${dumpFile.absolutePath} length: ${html.length}")
                assertTrue("HTML should be compact and not contain 15MB base64 strings", html.length < 500_000)
                assertTrue("Should contain file:// image references", html.contains("file://"))
            } finally {
                tempCacheDir.deleteRecursively()
            }
        }
    }

    @Test
    fun testAppUpdateVersionComparison() {
        val currentVer = com.pdocxles.app.update.AppUpdateManager.extractVersionNumbers("26.08.28_1530")
        val newerVer = com.pdocxles.app.update.AppUpdateManager.extractVersionNumbers("Pdocxles_26.08.28_1632.apk")
        val olderVer = com.pdocxles.app.update.AppUpdateManager.extractVersionNumbers("Pdocxles_26.08.27_1000.apk")

        assertTrue(com.pdocxles.app.update.AppUpdateManager.isVersionNewer(newerVer, currentVer))
        assertFalse(com.pdocxles.app.update.AppUpdateManager.isVersionNewer(olderVer, currentVer))
        assertFalse(com.pdocxles.app.update.AppUpdateManager.isVersionNewer(currentVer, currentVer))
    }
}
