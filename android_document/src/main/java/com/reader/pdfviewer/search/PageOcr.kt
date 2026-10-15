package com.reader.pdfviewer.search

import android.graphics.Bitmap
import android.graphics.RectF
import com.google.mlkit.common.MlKitException
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * A word recognized by OCR.
 * @param start index of the word in [OcrLine.text]
 * @param box bounds of the word in page-relative coordinates (0..1)
 */
class OcrWord(val start: Int, val end: Int, val box: RectF)

/** A line recognized by OCR, its words are joined by single spaces in [text] */
class OcrLine(val text: String, val words: List<OcrWord>)

/**
 * On-device text recognition of rendered pages, used when a page has no usable text layer.
 */
internal class PageOcr {
    private var recognizer: TextRecognizer? = null

    private fun recognizer(): TextRecognizer {
        return recognizer ?: TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS).also { recognizer = it }
    }

    suspend fun recognize(bitmap: Bitmap): List<OcrLine> {
        val text = process(InputImage.fromBitmap(bitmap, 0))
        val width = bitmap.width.toFloat()
        val height = bitmap.height.toFloat()
        val lines = ArrayList<OcrLine>()
        for (block in text.textBlocks) {
            for (line in block.lines) {
                val builder = StringBuilder()
                val words = ArrayList<OcrWord>()
                for (element in line.elements) {
                    val box = element.boundingBox ?: continue
                    if (builder.isNotEmpty()) {
                        builder.append(' ')
                    }
                    val start = builder.length
                    builder.append(element.text)
                    words.add(
                        OcrWord(
                            start, builder.length,
                            RectF(box.left / width, box.top / height, box.right / width, box.bottom / height)
                        )
                    )
                }
                if (words.isNotEmpty()) {
                    lines.add(OcrLine(builder.toString(), words))
                }
            }
        }
        return lines
    }

    /** Recognizes [image]; while Google Play services still downloads the model (first use), waits for it. */
    private suspend fun process(image: InputImage): Text {
        var waited = 0L
        while (true) {
            try {
                return suspendCancellableCoroutine { cont ->
                    recognizer().process(image)
                        .addOnSuccessListener { cont.resume(it) }
                        .addOnFailureListener { cont.resumeWithException(it) }
                }
            } catch (e: MlKitException) {
                if (e.errorCode != MlKitException.UNAVAILABLE || waited >= MODEL_WAIT_MS) throw e
                delay(MODEL_RETRY_MS)
                waited += MODEL_RETRY_MS
            }
        }
    }

    fun close() {
        recognizer?.close()
        recognizer = null
    }

    private companion object {
        const val MODEL_RETRY_MS = 2_000L
        const val MODEL_WAIT_MS = 90_000L
    }
}
