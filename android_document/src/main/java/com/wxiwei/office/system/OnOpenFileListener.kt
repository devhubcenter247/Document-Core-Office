package com.wxiwei.office.system

/** Which viewer shows the document. */
enum class OfficeCategory { WORD, EXCEL, POWERPOINT, TEXT }

/** Exact format of the opened document, taken from its file extension. */
enum class OfficeFileType(val extension: String, val category: OfficeCategory) {
    DOC("doc", OfficeCategory.WORD),
    DOCX("docx", OfficeCategory.WORD),
    DOT("dot", OfficeCategory.WORD),
    DOTX("dotx", OfficeCategory.WORD),
    DOTM("dotm", OfficeCategory.WORD),
    XLS("xls", OfficeCategory.EXCEL),
    XLSX("xlsx", OfficeCategory.EXCEL),
    XLSM("xlsm", OfficeCategory.EXCEL),
    XLT("xlt", OfficeCategory.EXCEL),
    XLTX("xltx", OfficeCategory.EXCEL),
    XLTM("xltm", OfficeCategory.EXCEL),
    PPT("ppt", OfficeCategory.POWERPOINT),
    PPTX("pptx", OfficeCategory.POWERPOINT),
    PPTM("pptm", OfficeCategory.POWERPOINT),
    POT("pot", OfficeCategory.POWERPOINT),
    POTX("potx", OfficeCategory.POWERPOINT),
    POTM("potm", OfficeCategory.POWERPOINT),
    TXT("txt", OfficeCategory.TEXT),

    /** Unsupported extension; the reader falls back to showing it as plain text. */
    OTHER("", OfficeCategory.TEXT);

    companion object {
        fun fromPath(path: String?): OfficeFileType {
            val extension = path?.substringAfterLast('.', "")?.lowercase().orEmpty()
            return entries.firstOrNull { it != OTHER && it.extension == extension } ?: OTHER
        }
    }
}

interface OnOpenFileListener {
    /** Called on the main thread once the document view is created. */
    fun onOpenFileSuccess(fileType: OfficeFileType)
    /**
     * The document could not be opened, or failed after it opened (reading the rest of it in the
     * background, layout, drawing). Return true to suppress the library error dialog. Main thread.
     */
    fun onOpenFileFailure(error: OpenFileException): Boolean
}
