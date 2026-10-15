package com.alf06.document.reader.ui.home.tools.translate

import android.content.Context
import android.graphics.Color
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.common.MlKitException
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentifier
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.reader.pdfviewer.tools.PdfSource
import com.reader.pdfviewer.tools.PdfTools
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.roundToInt

enum class TranslateError { NoText, UnknownLanguage, ModelDownload, Failed }

class TranslateException(val error: TranslateError, cause: Throwable? = null) : IOException(error.name, cause)

sealed interface TranslateStep {
    data class Reading(val done: Int, val total: Int) : TranslateStep

    data class Downloading(val percent: Int?) : TranslateStep
    data class Translating(val done: Int, val total: Int) : TranslateStep
    data object Writing : TranslateStep
}

class PdfTranslator(private val context: Context, private val pdfTools: PdfTools) {
    private class Page(val width: Float, val height: Float, val paragraphs: List<String>)

    suspend fun translate(
        source: PdfSource,
        pages: List<Int>,
        targetLanguage: String,
        output: File,
        onStep: (TranslateStep) -> Unit,
    ) {
        val read = pages.mapIndexed { index, page ->
            onStep(TranslateStep.Reading(index, pages.size))
            val text = pdfTools.pageText(source, page)
            Page(text?.pageWidth ?: A4_WIDTH, text?.pageHeight ?: A4_HEIGHT, paragraphsOf(text?.text.orEmpty()))
        }
        val all = read.flatMap { it.paragraphs }
        if (all.isEmpty()) throw TranslateException(TranslateError.NoText)

        val sourceLanguage = identify(all)
        val translated = if (sourceLanguage == targetLanguage) read else {
            translateParagraphs(read, sourceLanguage, targetLanguage, all.size, onStep)
        }
        onStep(TranslateStep.Writing)
        write(translated, output)
    }

    private suspend fun identify(paragraphs: List<String>): String {
        val sample = paragraphs.joinToString(" ").take(LANGUAGE_SAMPLE_LENGTH)
        val client = LanguageIdentification.getClient()
        val tag = try {
            identifyWaiting(client, sample)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw TranslateException(TranslateError.Failed, e)
        } finally {
            client.close()
        }

        return TranslateLanguage.fromLanguageTag(tag.substringBefore('-'))
            ?: throw TranslateException(TranslateError.UnknownLanguage)
    }

    /** Identifies [sample]; while Google Play services still downloads the model (first use), waits for it. */
    private suspend fun identifyWaiting(client: LanguageIdentifier, sample: String): String {
        var waited = 0L
        while (true) {
            try {
                return client.identifyLanguage(sample).await()
            } catch (e: MlKitException) {
                if (e.errorCode != MlKitException.UNAVAILABLE || waited >= MODEL_WAIT_MS) throw e
                delay(MODEL_RETRY_MS)
                waited += MODEL_RETRY_MS
            }
        }
    }

    private suspend fun translateParagraphs(
        pages: List<Page>,
        sourceLanguage: String,
        targetLanguage: String,
        total: Int,
        onStep: (TranslateStep) -> Unit,
    ): List<Page> {
        val options = TranslatorOptions.Builder()
            .setSourceLanguage(sourceLanguage)
            .setTargetLanguage(targetLanguage)
            .build()
        val translator = Translation.getClient(options)
        try {
            download(translator, sourceLanguage, targetLanguage, onStep)
            var done = 0
            return pages.map { page ->
                val paragraphs = page.paragraphs.map { paragraph ->
                    currentCoroutineContext().ensureActive()
                    onStep(TranslateStep.Translating(done++, total))
                    try {
                        translator.translate(paragraph).await()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        throw TranslateException(TranslateError.Failed, e)
                    }
                }
                Page(page.width, page.height, paragraphs)
            }
        } finally {
            translator.close()
        }
    }

    private suspend fun download(
        translator: Translator,
        sourceLanguage: String,
        targetLanguage: String,
        onStep: (TranslateStep) -> Unit,
    ) = coroutineScope {
        val missing = missingModels(sourceLanguage, targetLanguage)
        onStep(TranslateStep.Downloading(if (missing > 0) 0 else null))
        val watcher = if (missing == 0) null else {
            val tracker = ModelDownloadTracker(context, missing)
            launch {
                var last = 0
                while (true) {
                    delay(DOWNLOAD_POLL_MS)
                    val percent = tracker.percent()
                    if (percent != last) onStep(TranslateStep.Downloading(percent))
                    last = percent
                }
            }
        }
        try {
            translator.downloadModelIfNeeded().await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw TranslateException(TranslateError.ModelDownload, e)
        } finally {
            watcher?.cancel()
        }
    }

    private suspend fun missingModels(sourceLanguage: String, targetLanguage: String): Int {
        val downloaded = try {
            RemoteModelManager.getInstance()
                .getDownloadedModels(TranslateRemoteModel::class.java).await()
                .map { it.language }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return 0
        }
        return (setOf(sourceLanguage, targetLanguage) - TranslateLanguage.ENGLISH - downloaded.toSet()).size
    }

    private suspend fun write(pages: List<Page>, output: File) = withContext(Dispatchers.IO) {
        val document = PdfDocument()
        try {
            var number = 1
            for (page in pages) {
                currentCoroutineContext().ensureActive()
                val width = page.width.roundToInt().coerceAtLeast(1)
                val height = page.height.roundToInt().coerceAtLeast(1)

                val scale = page.width / A4_WIDTH
                val margin = MARGIN * scale
                val paint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
                    textSize = TEXT_SIZE * scale
                    color = Color.BLACK
                }
                val contentWidth = (width - 2 * margin).roundToInt().coerceAtLeast(1)
                val contentHeight = height - 2 * margin

                val text = page.paragraphs.joinToString("\n\n").ifEmpty { " " }
                val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, contentWidth)
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .setLineSpacing(0f, LINE_SPACING)
                    .build()

                var line = 0
                do {
                    val top = layout.getLineTop(line)
                    var end = line
                    while (end + 1 < layout.lineCount && layout.getLineBottom(end + 1) - top <= contentHeight) end++
                    val bottom = layout.getLineBottom(end)
                    val pdfPage = document.startPage(PdfDocument.PageInfo.Builder(width, height, number++).create())
                    pdfPage.canvas.apply {
                        translate(margin, margin)
                        clipRect(0f, 0f, contentWidth.toFloat(), (bottom - top).toFloat())
                        translate(0f, -top.toFloat())
                        layout.draw(this)
                    }
                    document.finishPage(pdfPage)
                    line = end + 1
                } while (line < layout.lineCount)
            }
            val temp = File(output.parentFile, "${output.name}.tmp")
            try {
                temp.outputStream().use(document::writeTo)
                if (!temp.renameTo(output)) throw IOException("Cannot move ${temp.name} to ${output.name}")
            } finally {
                temp.delete()
            }
        } finally {
            document.close()
        }
    }

    private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
        addOnSuccessListener { cont.resume(it) }
        addOnFailureListener { cont.resumeWithException(it) }
    }

    companion object {
        private const val A4_WIDTH = PdfTools.A4_WIDTH
        private const val A4_HEIGHT = 842f
        private const val MARGIN = 48f
        private const val TEXT_SIZE = 11f
        private const val LINE_SPACING = 1.2f
        private const val LANGUAGE_SAMPLE_LENGTH = 1000
        private const val DOWNLOAD_POLL_MS = 300L
        private const val MODEL_RETRY_MS = 2_000L
        private const val MODEL_WAIT_MS = 90_000L
        private const val PARAGRAPH_END = ".!?:;。！？"

        internal fun paragraphsOf(text: String): List<String> {
            val lines = text.split("\r\n", "\n", "\r").map { it.trim() }
            val longest = lines.maxOfOrNull { it.length } ?: 0
            val paragraphs = ArrayList<String>()
            val current = StringBuilder()
            fun flush() {
                if (current.isNotBlank()) paragraphs += current.toString()
                current.clear()
            }
            for (line in lines) {
                if (line.isEmpty()) {
                    flush()
                    continue
                }
                if (current.isNotEmpty()) {
                    if (current.last() == '-') current.setLength(current.length - 1) else current.append(' ')
                }
                current.append(line)
                if (line.last() in PARAGRAPH_END && line.length < longest * SHORT_LINE_RATIO) flush()
            }
            flush()
            return paragraphs
        }

        private const val SHORT_LINE_RATIO = 0.8f
    }
}
