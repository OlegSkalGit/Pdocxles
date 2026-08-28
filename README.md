# Pdocxles

**Pdocxles** is an ultra-lightweight, 100% native, open-source Android document viewer for opening and viewing 4 modern document formats completely offline: **PDF**, **DOCX**, **XLSX**, and **PPTX**.

Built with pure native Android SDK (Kotlin) and lightweight View architecture without heavy runtimes, proprietary SDKs, or Apache POI:
- ⚡ **Ultra-Compact APK Size:** **~850 KB**.
- 🧠 **Memory-Safe & High Performance:** Native `PdfRenderer` with LRU Bitmap caching, 100% native streaming OpenXML `XmlPullParser` for XLSX/DOCX/PPTX, zero OOM errors.
- 🔒 **100% Offline & Private:** Zero network permissions, zero telemetry, no third-party cloud dependencies.
- 📱 **Fast & Fluid UI:** Native Views (`AppCompat`, `RecyclerView`, `CardView`), bidirectional spreadsheet matrix, custom multi-touch pinch-to-zoom (1.0x–5.0x), double-tap zoom, and 2D panning.
- 🎯 **Strict Document Associations:** Clean Intent Filters for only the 4 target formats (`.pdf`, `.docx`, `.xlsx`, `.pptx`).

---

## 🚀 Automated APK Build

Use the automated self-contained build scripts to build and sign the release APK on any machine:

### 🪟 Windows:
```cmd
_BUILD_apk_.bat
```

### 🐧 Linux & 🍎 macOS:
```bash
chmod +x _BUILD_apk_.sh
./_BUILD_apk_.sh
```

The build script automatically detects or provisions portable OpenJDK 17, Gradle 8.7, and Android SDK command-line tools, compiles the release APK with ProGuard/R8 resource shrinking, and outputs the signed APK to the root directory as `Pdocxles_yy.MM.dd_HHmm.apk`.

---

## 🛠️ Architecture & Tech Stack

- **Language & Concurrency:** Kotlin + Coroutines (`Dispatchers.IO`)
- **UI Framework:** Pure Native Android Views & XML (`AppCompat`, `RecyclerView`, `CardView`)
- **Document Engines:**
  - **PDF:** Native `android.graphics.pdf.PdfRenderer` + LRU Bitmap Caching + Multi-touch `TouchZoomLayout`.
  - **XLSX:** 100% Native OpenXML streaming reader (`org.xmlpull.v1.XmlPullParser`) + SharedStrings parser + Dynamic column layout + Multi-sheet tabs + Pinch-to-Zoom.
  - **DOCX:** Native OpenXML parser + Table grid layout preservation (`<w:tblGrid>`, `<w:tcW>`) + Embedded media extraction (Base64 Data URIs) + Styled Web rendering.
  - **PPTX:** Native OpenXML slide card parser + Slide relationships + Embedded image viewer.
- **Storage & System Integration:** Scoped Storage-compliant `DocumentStorageManager` with `Intent.ACTION_VIEW` deep-linking and Storage Access Framework (SAF).

---

## 📜 License

MIT License.
