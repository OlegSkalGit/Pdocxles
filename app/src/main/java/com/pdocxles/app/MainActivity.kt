package com.pdocxles.app

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.pdocxles.app.engine.openxml.OpenXmlHtmlEngine
import com.pdocxles.app.engine.pdf.PdfRenderEngine
import com.pdocxles.app.engine.xlsx.FastExcelEngine
import com.pdocxles.app.engine.xlsx.SheetData
import com.pdocxles.app.engine.xlsx.SheetInfo
import com.pdocxles.app.model.DocumentItem
import com.pdocxles.app.model.DocumentType
import com.pdocxles.app.storage.DocumentStorageManager
import com.pdocxles.app.ui.adapter.DocumentAdapter
import com.pdocxles.app.ui.adapter.PdfPageAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var container: FrameLayout
    private var currentPdfEngine: PdfRenderEngine? = null

    // Document lists
    private var allDocuments = listOf<DocumentItem>()
    private var currentFilter: DocumentType? = null
    private var currentSearchQuery = ""
    private lateinit var documentAdapter: DocumentAdapter

    private val supportedMimeTypes = arrayOf(
        "application/pdf",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "application/msword",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "application/vnd.ms-excel",
        "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "application/vnd.ms-powerpoint",
        "*/*"
    )

    private val openDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            lifecycleScope.launch {
                val file = DocumentStorageManager.resolveUriToLocalFile(this@MainActivity, uri)
                if (file != null) {
                    openDocument(file)
                }
            }
        }
    }

    private var activeScreenType = ScreenType.FILE_MANAGER

    enum class ScreenType {
        FILE_MANAGER, PDF_VIEWER, XLSX_VIEWER, OFFICE_VIEWER, HELP
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        container = findViewById(R.id.container)

        DocumentStorageManager.trimCache(this)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (activeScreenType != ScreenType.FILE_MANAGER) {
                    showFileManager()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        if (!handleIncomingIntent(intent)) {
            showFileManager()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?): Boolean {
        if (intent == null) return false
        val action = intent.action
        val data: Uri? = intent.data

        if (Intent.ACTION_VIEW == action && data != null) {
            lifecycleScope.launch {
                val file = DocumentStorageManager.resolveUriToLocalFile(this@MainActivity, data)
                if (file != null) {
                    openDocument(file)
                } else {
                    showFileManager()
                }
            }
            return true
        }
        return false
    }

    // ==========================================
    // 1. FILE MANAGER SCREEN
    // ==========================================
    private fun showFileManager() {
        activeScreenType = ScreenType.FILE_MANAGER
        closeCurrentPdfEngine()
        container.removeAllViews()

        val view = LayoutInflater.from(this).inflate(R.layout.view_file_manager, container, false)
        container.addView(view)

        val rvDocuments: RecyclerView = view.findViewById(R.id.rvDocuments)
        val emptyStateView: View = view.findViewById(R.id.emptyStateView)
        val tvEmptyTitle: TextView = view.findViewById(R.id.tvEmptyTitle)
        val tvEmptyDesc: TextView = view.findViewById(R.id.tvEmptyDesc)
        val btnEmptyOpen: Button = view.findViewById(R.id.btnEmptyOpen)
        val btnFabOpen: View = view.findViewById(R.id.btnFabOpen)
        val btnRefresh: ImageButton = view.findViewById(R.id.btnRefresh)
        val btnHelp: ImageButton = view.findViewById(R.id.btnHelp)
        val etSearch: EditText = view.findViewById(R.id.etSearch)
        val btnClearSearch: ImageButton = view.findViewById(R.id.btnClearSearch)
        val filterAll: TextView = view.findViewById(R.id.filterAll)
        val filterPdf: TextView = view.findViewById(R.id.filterPdf)
        val filterDocx: TextView = view.findViewById(R.id.filterDocx)
        val filterXlsx: TextView = view.findViewById(R.id.filterXlsx)
        val filterPptx: TextView = view.findViewById(R.id.filterPptx)
        val progressBar: ProgressBar = view.findViewById(R.id.progressBar)

        documentAdapter = DocumentAdapter { item ->
            openDocument(File(item.path))
        }
        rvDocuments.layoutManager = LinearLayoutManager(this)
        rvDocuments.adapter = documentAdapter

        fun updateFilteredList() {
            val filtered = allDocuments.filter { item ->
                val matchesFilter = (currentFilter == null || item.type == currentFilter)
                val matchesQuery = currentSearchQuery.isBlank() || item.name.contains(currentSearchQuery, ignoreCase = true)
                matchesFilter && matchesQuery
            }
            documentAdapter.submitList(filtered)

            if (filtered.isEmpty()) {
                rvDocuments.visibility = View.GONE
                emptyStateView.visibility = View.VISIBLE
                if (currentSearchQuery.isNotEmpty()) {
                    tvEmptyTitle.setText(R.string.no_search_matches)
                    tvEmptyDesc.setText(R.string.no_search_desc)
                } else {
                    tvEmptyTitle.setText(R.string.no_documents_found)
                    tvEmptyDesc.setText(R.string.no_documents_desc)
                }
            } else {
                rvDocuments.visibility = View.VISIBLE
                emptyStateView.visibility = View.GONE
            }
        }

        val filterButtons = listOf(
            filterAll to null,
            filterPdf to DocumentType.PDF,
            filterDocx to DocumentType.DOCX,
            filterXlsx to DocumentType.XLSX,
            filterPptx to DocumentType.PPTX
        )

        fun updateFilterButtonsUI() {
            filterButtons.forEach { (btn, type) ->
                if (type == currentFilter) {
                    btn.setBackgroundResource(R.drawable.bg_filter_selected)
                    btn.setTextColor(Color.WHITE)
                    btn.typeface = Typeface.DEFAULT_BOLD
                } else {
                    btn.setBackgroundResource(R.drawable.bg_filter_unselected)
                    btn.setTextColor(Color.parseColor("#1C1B1F"))
                    btn.typeface = Typeface.DEFAULT
                }
            }
        }

        filterButtons.forEach { (btn, type) ->
            btn.setOnClickListener {
                currentFilter = type
                updateFilterButtonsUI()
                updateFilteredList()
            }
        }

        fun scanFiles() {
            progressBar.visibility = View.VISIBLE
            lifecycleScope.launch {
                allDocuments = DocumentStorageManager.scanDocuments(this@MainActivity)
                progressBar.visibility = View.GONE
                updateFilteredList()
            }
        }

        btnRefresh.setOnClickListener { scanFiles() }
        btnHelp.setOnClickListener { showHelp() }
        btnFabOpen.setOnClickListener { openDocumentLauncher.launch(supportedMimeTypes) }
        btnEmptyOpen.setOnClickListener { openDocumentLauncher.launch(supportedMimeTypes) }

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                currentSearchQuery = s?.toString()?.trim() ?: ""
                btnClearSearch.visibility = if (currentSearchQuery.isNotEmpty()) View.VISIBLE else View.GONE
                updateFilteredList()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        btnClearSearch.setOnClickListener {
            etSearch.setText("")
        }

        updateFilterButtonsUI()
        scanFiles()
    }

    // ==========================================
    // ROUTER
    // ==========================================
    fun openDocument(file: File) {
        val type = DocumentType.fromFile(file)
        when (type) {
            DocumentType.PDF -> openPdfViewer(file)
            DocumentType.XLSX -> openXlsxViewer(file)
            DocumentType.PPTX -> openOfficeViewer(file, isPptx = true)
            DocumentType.DOCX, DocumentType.UNKNOWN -> openOfficeViewer(file, isPptx = false)
        }
    }

    // ==========================================
    // 2. PDF VIEWER SCREEN
    // ==========================================
    private fun openPdfViewer(file: File) {
        activeScreenType = ScreenType.PDF_VIEWER
        closeCurrentPdfEngine()
        container.removeAllViews()

        val view = LayoutInflater.from(this).inflate(R.layout.view_pdf, container, false)
        container.addView(view)

        val btnBack: ImageButton = view.findViewById(R.id.btnBack)
        val tvPdfTitle: TextView = view.findViewById(R.id.tvPdfTitle)
        val tvPdfPageIndicator: TextView = view.findViewById(R.id.tvPdfPageIndicator)
        val rvPdfPages: RecyclerView = view.findViewById(R.id.rvPdfPages)
        val pdfProgressBar: ProgressBar = view.findViewById(R.id.pdfProgressBar)
        val pdfErrorLayout: View = view.findViewById(R.id.pdfErrorLayout)
        val tvPdfErrorMsg: TextView = view.findViewById(R.id.tvPdfErrorMsg)

        btnBack.setOnClickListener { showFileManager() }
        tvPdfTitle.text = file.name.replace(Regex("^\\d{10,14}_"), "")

        pdfProgressBar.visibility = View.VISIBLE
        rvPdfPages.visibility = View.GONE
        pdfErrorLayout.visibility = View.GONE

        val layoutManager = LinearLayoutManager(this)
        rvPdfPages.layoutManager = layoutManager

        val engine = PdfRenderEngine(file)
        currentPdfEngine = engine

        lifecycleScope.launch {
            val result = engine.initialize()
            pdfProgressBar.visibility = View.GONE

            result.fold(
                onSuccess = { count ->
                    if (count == 0) {
                        pdfErrorLayout.visibility = View.VISIBLE
                        tvPdfErrorMsg.text = "PDF has 0 pages."
                    } else {
                        rvPdfPages.visibility = View.VISIBLE
                        tvPdfPageIndicator.text = "1 / $count pages"

                        val adapter = PdfPageAdapter(count, engine, lifecycleScope)
                        rvPdfPages.adapter = adapter

                        rvPdfPages.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                                val firstVisible = layoutManager.findFirstVisibleItemPosition()
                                if (firstVisible != RecyclerView.NO_POSITION) {
                                    tvPdfPageIndicator.text = "${firstVisible + 1} / $count pages"
                                }
                            }
                        })
                    }
                },
                onFailure = { error ->
                    pdfErrorLayout.visibility = View.VISIBLE
                    tvPdfErrorMsg.text = error.localizedMessage ?: "Failed to open PDF"
                }
            )
        }
    }

    // ==========================================
    // 3. XLSX VIEWER SCREEN
    // ==========================================
    private fun openXlsxViewer(file: File) {
        activeScreenType = ScreenType.XLSX_VIEWER
        closeCurrentPdfEngine()
        container.removeAllViews()

        val view = LayoutInflater.from(this).inflate(R.layout.view_xlsx, container, false)
        container.addView(view)

        val btnBack: ImageButton = view.findViewById(R.id.btnBack)
        val tvXlsxTitle: TextView = view.findViewById(R.id.tvXlsxTitle)
        val tvXlsxStats: TextView = view.findViewById(R.id.tvXlsxStats)
        val tableLayout: TableLayout = view.findViewById(R.id.tableLayout)
        val sheetTabsScroll: HorizontalScrollView = view.findViewById(R.id.sheetTabsScroll)
        val sheetTabsContainer: LinearLayout = view.findViewById(R.id.sheetTabsContainer)
        val xlsxProgressBar: ProgressBar = view.findViewById(R.id.xlsxProgressBar)
        val xlsxErrorLayout: View = view.findViewById(R.id.xlsxErrorLayout)
        val tvXlsxErrorMsg: TextView = view.findViewById(R.id.tvXlsxErrorMsg)

        btnBack.setOnClickListener { showFileManager() }
        tvXlsxTitle.text = file.name.replace(Regex("^\\d{10,14}_"), "")

        val engine = FastExcelEngine(file)
        var sheets = listOf<SheetInfo>()
        var selectedSheetIdx = 0

        fun populateTable(sheetData: SheetData) {
            tableLayout.removeAllViews()
            val rows = sheetData.rows
            val cols = sheetData.maxColumns
            tvXlsxStats.text = "${rows.size} rows • $cols columns"

            val density = resources.displayMetrics.density
            val cellPaddingH = (10 * density).toInt()
            val cellPaddingV = (7 * density).toInt()
            val rowNumWidth = (44 * density).toInt()
            val minColWidth = (85 * density).toInt()
            val maxColWidth = (260 * density).toInt()

            // Dynamically calculate column widths based on content length
            val colWidths = IntArray(cols) { minColWidth }
            for (c in 0 until cols) {
                var maxLen = getColumnLetter(c).length
                val sampleRows = minOf(rows.size, 60)
                for (r in 0 until sampleRows) {
                    val len = rows[r].getOrElse(c) { "" }.length
                    if (len > maxLen) maxLen = len
                }
                colWidths[c] = ((maxLen * 9f + 24) * density).toInt().coerceIn(minColWidth, maxColWidth)
            }

            // Header row (#, A, B, C...)
            val headerRow = TableRow(this@MainActivity).apply {
                setBackgroundColor(Color.parseColor("#E2E6EA"))
            }

            val cornerCell = TextView(this@MainActivity).apply {
                text = "#"
                typeface = Typeface.DEFAULT_BOLD
                textSize = 11.5f
                setTextColor(Color.DKGRAY)
                gravity = Gravity.CENTER
                setPadding(cellPaddingH, cellPaddingV, cellPaddingH, cellPaddingV)
                width = rowNumWidth
                setBackgroundColor(Color.parseColor("#D8DDE3"))
            }
            headerRow.addView(cornerCell)

            for (c in 0 until cols) {
                val colCell = TextView(this@MainActivity).apply {
                    text = getColumnLetter(c)
                    typeface = Typeface.DEFAULT_BOLD
                    textSize = 12f
                    setTextColor(Color.parseColor("#333333"))
                    gravity = Gravity.CENTER
                    setPadding(cellPaddingH, cellPaddingV, cellPaddingH, cellPaddingV)
                    width = colWidths[c]
                }
                headerRow.addView(colCell)
            }
            tableLayout.addView(headerRow)

            // Data rows
            for (r in rows.indices) {
                val rowData = rows[r]
                val tr = TableRow(this@MainActivity).apply {
                    setBackgroundColor(if (r % 2 == 0) Color.WHITE else Color.parseColor("#F9FAFB"))
                }

                val rowNumCell = TextView(this@MainActivity).apply {
                    text = (r + 1).toString()
                    textSize = 11.5f
                    setTextColor(Color.GRAY)
                    gravity = Gravity.CENTER
                    setPadding(cellPaddingH, cellPaddingV, cellPaddingH, cellPaddingV)
                    width = rowNumWidth
                    setBackgroundColor(Color.parseColor("#EAECEF"))
                }
                tr.addView(rowNumCell)

                for (c in 0 until cols) {
                    val cellText = rowData.getOrElse(c) { "" }
                    val dataCell = TextView(this@MainActivity).apply {
                        text = cellText
                        textSize = 13f
                        setTextColor(Color.parseColor("#212529"))
                        gravity = Gravity.START or Gravity.CENTER_VERTICAL
                        setPadding(cellPaddingH, cellPaddingV, cellPaddingH, cellPaddingV)
                        width = colWidths[c]
                        maxLines = 4
                        ellipsize = android.text.TextUtils.TruncateAt.END
                    }
                    tr.addView(dataCell)
                }
                tableLayout.addView(tr)
            }
        }

        fun loadSheet(idx: Int) {
            xlsxProgressBar.visibility = View.VISIBLE
            tableLayout.removeAllViews()

            lifecycleScope.launch {
                val res = engine.loadSheetData(idx)
                xlsxProgressBar.visibility = View.GONE
                res.fold(
                    onSuccess = { data ->
                        populateTable(data)
                    },
                    onFailure = { err ->
                        xlsxErrorLayout.visibility = View.VISIBLE
                        tvXlsxErrorMsg.text = err.localizedMessage ?: "Failed to read sheet"
                    }
                )
            }
        }

        fun renderTabs() {
            sheetTabsContainer.removeAllViews()
            if (sheets.size <= 1) {
                sheetTabsScroll.visibility = View.GONE
                return
            }
            sheetTabsScroll.visibility = View.VISIBLE
            sheets.forEachIndexed { index, sheetInfo ->
                val btnTab = Button(this@MainActivity, null, android.R.attr.borderlessButtonStyle).apply {
                    text = sheetInfo.name
                    textSize = 13f
                    isAllCaps = false
                    setPadding(32, 0, 32, 0)
                    if (index == selectedSheetIdx) {
                        setTextColor(Color.parseColor("#2E7D32"))
                        typeface = Typeface.DEFAULT_BOLD
                    } else {
                        setTextColor(Color.parseColor("#757575"))
                        typeface = Typeface.DEFAULT
                    }
                    setOnClickListener {
                        if (selectedSheetIdx != index) {
                            selectedSheetIdx = index
                            renderTabs()
                            loadSheet(index)
                        }
                    }
                }
                sheetTabsContainer.addView(btnTab)
            }
        }

        xlsxProgressBar.visibility = View.VISIBLE
        lifecycleScope.launch {
            val listRes = engine.getSheetList()
            xlsxProgressBar.visibility = View.GONE
            listRes.fold(
                onSuccess = { sheetList ->
                    if (sheetList.isEmpty()) {
                        xlsxErrorLayout.visibility = View.VISIBLE
                        tvXlsxErrorMsg.text = "No sheets found in workbook."
                    } else {
                        sheets = sheetList
                        selectedSheetIdx = 0
                        renderTabs()
                        loadSheet(0)
                    }
                },
                onFailure = { err ->
                    xlsxErrorLayout.visibility = View.VISIBLE
                    tvXlsxErrorMsg.text = err.localizedMessage ?: "Failed to open Excel file"
                }
            )
        }
    }

    private fun getColumnLetter(index: Int): String {
        var num = index
        val sb = StringBuilder()
        while (num >= 0) {
            val rem = num % 26
            sb.insert(0, ('A'.code + rem).toChar())
            num = num / 26 - 1
        }
        return sb.toString()
    }

    // ==========================================
    // 4. DOCX & PPTX OFFICE VIEWER SCREEN
    // ==========================================
    private fun openOfficeViewer(file: File, isPptx: Boolean) {
        activeScreenType = ScreenType.OFFICE_VIEWER
        closeCurrentPdfEngine()
        container.removeAllViews()

        val view = LayoutInflater.from(this).inflate(R.layout.view_office, container, false)
        container.addView(view)

        val btnBack: ImageButton = view.findViewById(R.id.btnBack)
        val tvOfficeTitle: TextView = view.findViewById(R.id.tvOfficeTitle)
        val tvOfficeType: TextView = view.findViewById(R.id.tvOfficeType)
        val webView: WebView = view.findViewById(R.id.webView)
        val officeProgressBar: ProgressBar = view.findViewById(R.id.officeProgressBar)
        val officeErrorLayout: View = view.findViewById(R.id.officeErrorLayout)
        val tvOfficeErrorMsg: TextView = view.findViewById(R.id.tvOfficeErrorMsg)

        btnBack.setOnClickListener { showFileManager() }
        tvOfficeTitle.text = file.name.replace(Regex("^\\d{10,14}_"), "")
        tvOfficeType.text = if (isPptx) "PowerPoint Presentation" else "Word Document"

        webView.webViewClient = WebViewClient()
        webView.settings.apply {
            builtInZoomControls = true
            displayZoomControls = false
            useWideViewPort = true
            loadWithOverviewMode = true
            javaScriptEnabled = false
            allowFileAccess = false
            allowContentAccess = false
            defaultTextEncodingName = "utf-8"
        }

        officeProgressBar.visibility = View.VISIBLE
        webView.visibility = View.GONE
        officeErrorLayout.visibility = View.GONE

        lifecycleScope.launch {
            val result = if (isPptx) {
                OpenXmlHtmlEngine.convertPptxToHtml(file)
            } else {
                OpenXmlHtmlEngine.convertDocxToHtml(file)
            }

            officeProgressBar.visibility = View.GONE
            result.fold(
                onSuccess = { html ->
                    webView.visibility = View.VISIBLE
                    webView.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
                },
                onFailure = { error ->
                    officeErrorLayout.visibility = View.VISIBLE
                    tvOfficeErrorMsg.text = error.localizedMessage ?: "Failed to open document"
                }
            )
        }
    }

    // ==========================================
    // 5. HELP SCREEN
    // ==========================================
    private fun showHelp() {
        activeScreenType = ScreenType.HELP
        closeCurrentPdfEngine()
        container.removeAllViews()

        val view = LayoutInflater.from(this).inflate(R.layout.view_help, container, false)
        container.addView(view)

        val btnHelpBack: ImageButton = view.findViewById(R.id.btnHelpBack)
        btnHelpBack.setOnClickListener { showFileManager() }
    }

    private fun closeCurrentPdfEngine() {
        currentPdfEngine?.close()
        currentPdfEngine = null
    }

    override fun onDestroy() {
        super.onDestroy()
        closeCurrentPdfEngine()
    }
}
