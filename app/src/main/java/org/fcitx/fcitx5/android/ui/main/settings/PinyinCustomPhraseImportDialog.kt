/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2024 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings

import android.app.AlertDialog
import android.content.Context
import android.view.Gravity
import android.widget.ScrollView
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.pinyin.customphrase.CustomPhraseImporter
import org.fcitx.fcitx5.android.data.pinyin.customphrase.ImportResult
import org.fcitx.fcitx5.android.data.pinyin.customphrase.PinyinCustomPhrase
import org.fcitx.fcitx5.android.utils.materialTextInput
import org.fcitx.fcitx5.android.utils.onNegativeButtonClick
import org.fcitx.fcitx5.android.utils.onPositiveButtonClick
import org.fcitx.fcitx5.android.utils.str
import splitties.views.dsl.core.add
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.textView
import splitties.views.dsl.core.verticalLayout
import splitties.views.dsl.core.wrapContent
import splitties.views.setPaddingDp

/**
 * Two-step importer for custom phrases.
 *
 * Step 1: a multi-line paste box with format hints.
 * Step 2: a parsed preview — successful entries (auto pinyin flagged with
 * "⟵ auto"), skipped duplicates and failed lines — before committing.
 *
 * Parsing runs off the main thread because it may read the pinyin table file.
 */
object PinyinCustomPhraseImportDialog {

    fun show(
        context: Context,
        existing: List<PinyinCustomPhrase>,
        onConfirm: (List<PinyinCustomPhrase>) -> Unit
    ) {
        showInput(context, "", existing, onConfirm)
    }

    private fun showInput(
        context: Context,
        initialText: String,
        existing: List<PinyinCustomPhrase>,
        onConfirm: (List<PinyinCustomPhrase>) -> Unit,
        errorMessage: String? = null
    ) {
        val (inputLayout, inputField) = context.materialTextInput {
            hint = context.getString(R.string.import_custom_phrase_hint)
        }
        inputField.apply {
            isSingleLine = false
            maxLines = 12
            gravity = Gravity.TOP or Gravity.START
            if (initialText.isNotEmpty()) setText(initialText)
        }
        val message = errorMessage ?: context.getString(R.string.import_custom_phrase_format)
        val container = context.verticalLayout {
            setPaddingDp(20, 10, 20, 0)
            add(textView {
                text = message
                textSize = 12f
            }, lParams(matchParent, wrapContent))
            add(inputLayout, lParams(matchParent, wrapContent))
        }
        AlertDialog.Builder(context)
            .setTitle(R.string.import_custom_phrase_title)
            .setView(container)
            .setPositiveButton(R.string.next, null)
            .setNegativeButton(android.R.string.cancel, null)
            .show()
            .onPositiveButtonClick {
                val self = this
                val text = inputField.str
                if (text.isBlank()) {
                    inputField.error = context.getString(R.string.import_custom_phrase_empty)
                    return@onPositiveButtonClick false
                }
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
                scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        CustomPhraseImporter.import(text, existing)
                    }
                    if (result.phrases.isEmpty() && result.errors.isEmpty()) {
                        inputField.error = context.getString(R.string.import_custom_phrase_no_valid)
                        return@launch
                    }
                    self.dismiss()
                    showPreview(context, text, result, existing, onConfirm, scope)
                }
                false
            }
    }

    private fun showPreview(
        context: Context,
        originalText: String,
        result: ImportResult,
        existing: List<PinyinCustomPhrase>,
        onConfirm: (List<PinyinCustomPhrase>) -> Unit,
        scope: CoroutineScope
    ) {
        val preview = buildString {
            appendLine(
                context.getString(
                    R.string.import_custom_phrase_summary,
                    result.phrases.size, result.duplicated, result.errors.size
                )
            )
            if (result.phrases.isNotEmpty()) {
                appendLine()
                appendLine(context.getString(R.string.import_custom_phrase_preview_head))
                result.phrases.take(30).forEach { p ->
                    append("  ").append(p.serialize())
                    if (p.key in result.autoKeys) append("  ⟵ auto")
                    appendLine()
                }
                if (result.phrases.size > 30) appendLine("  …")
            }
            if (result.errors.isNotEmpty()) {
                appendLine()
                appendLine(context.getString(R.string.import_custom_phrase_errors_head))
                result.errors.take(20).forEach { append("  ").appendLine(it) }
                if (result.errors.size > 20) appendLine("  …")
            }
        }
        val scroll = ScrollView(context).apply {
            addView(TextView(context).apply {
                text = preview
                textSize = 12f
                setPaddingDp(20, 10, 20, 10)
            })
        }
        AlertDialog.Builder(context)
            .setTitle(R.string.import_custom_phrase_preview)
            .setView(scroll)
            .setPositiveButton(R.string.import_, null)
            .setNegativeButton(R.string.back, null)
            .show()
            .onPositiveButtonClick {
                onConfirm(result.phrases)
                true
            }
            .onNegativeButtonClick {
                showInput(context, originalText, existing, onConfirm)
                true
            }
            .also { it.setOnDismissListener { scope.cancel() } }
    }
}
