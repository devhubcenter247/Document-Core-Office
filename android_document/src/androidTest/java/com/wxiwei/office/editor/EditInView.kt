/*
 * Copyright (c) 2026 dongb2002. All rights reserved.
 *
 * Proprietary and confidential. Unauthorized copying, modification or distribution of this
 * file, via any medium, is strictly prohibited without the written permission of dongb2002.
 */
package com.wxiwei.office.editor

import androidx.appcompat.app.AppCompatActivity
import androidx.test.core.app.ActivityScenario
import com.editor.docsdk.DocumentEditor
import com.editor.docsdk.DocumentException
import com.editor.docsdk.DocumentView
import com.wxiwei.office.editor.OpenDocument.onMain
import com.wxiwei.office.reader.OfficeDocumentView
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File

/** Test helper: a file opened in a [DocumentView] with editing started, as an app does it. */
internal object EditInView {
    class Host(val view: DocumentView, val editor: DocumentEditor, val events: MutableList<Pair<Int, Int>>)

    fun edit(file: File, block: suspend Host.() -> Unit) {
        ActivityScenario.launch(AppCompatActivity::class.java).use { scenario ->
            lateinit var view: DocumentView
            var loaded = false
            val events = ArrayList<Pair<Int, Int>>()
            scenario.onActivity { activity ->
                view = DocumentView(activity)
                activity.setContentView(view)
                view.listener = object : DocumentView.Listener {
                    override fun onLoaded(pageCount: Int) { loaded = true }
                    override fun onPageChanged(page: Int, pageCount: Int) { events.add(page to pageCount) }
                    override fun onError(error: DocumentException) {}
                }
                view.open(file)
            }
            runBlocking {
                withTimeout(60_000) { while (!onMain { loaded }) delay(100) }
                // as the app does: the pages are only drawn over a background
                onMain { office(view).documentView?.setBackgroundColor(android.graphics.Color.LTGRAY) }
                val editor = onMain { view.startEditing(showToolbar = false) }!!
                Host(view, editor, events).block()
            }
        }
    }

    fun office(view: DocumentView): OfficeDocumentView =
        (0 until view.childCount).map { view.getChildAt(it) }.filterIsInstance<OfficeDocumentView>().single()
}
