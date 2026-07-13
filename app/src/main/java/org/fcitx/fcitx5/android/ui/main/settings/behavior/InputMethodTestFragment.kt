/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2025 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.behavior

import android.os.Bundle
import android.view.KeyEvent
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import org.fcitx.fcitx5.android.R
import splitties.dimensions.dp
import splitties.views.backgroundColor
import splitties.views.padding
import splitties.views.textAppearance

class InputMethodTestFragment : Fragment() {

    private lateinit var keyCodeText: TextView
    private lateinit var modifiersText: TextView
    private lateinit var actionText: TextView
    private lateinit var charText: TextView
    private lateinit var logText: TextView

    private val logBuilder = StringBuilder()
    private var keyEventCount = 0

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val context = requireContext()

        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )

            // Main input area with hint
            val scrollView = ScrollView(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    0,
                    1f
                )
                isFillViewport = true
                addView(TextView(context).apply {
                    setText(R.string.test_input_hint)
                    padding = dp(16)
                    textAppearance = android.R.style.TextAppearance_Material_Body1
                })
            }
            addView(scrollView)

            // Bottom KeyCode display panel
            val bottomPanel = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                setBackgroundColor(0xFFF5F5F5.toInt())
                padding = dp(12)

                keyCodeText = TextView(context).apply {
                    textAppearance = android.R.style.TextAppearance_Material_Subhead
                    setTextColor(0xFF333333.toInt())
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                }
                addView(keyCodeText)

                modifiersText = TextView(context).apply {
                    textAppearance = android.R.style.TextAppearance_Material_Body2
                    setTextColor(0xFF666666.toInt())
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                }
                addView(modifiersText)

                actionText = TextView(context).apply {
                    textAppearance = android.R.style.TextAppearance_Material_Body2
                    setTextColor(0xFF666666.toInt())
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                }
                addView(actionText)

                charText = TextView(context).apply {
                    textAppearance = android.R.style.TextAppearance_Material_Body2
                    setTextColor(0xFF666666.toInt())
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                }
                addView(charText)

                // Divider
                addView(View(context).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        dp(1)
                    )
                    setBackgroundColor(0xFFCCCCCC.toInt())
                })

                TextView(context).apply {
                    setText(R.string.key_event_log)
                    padding = dp(8)
                    textAppearance = android.R.style.TextAppearance_Material_Medium
                    setTextColor(0xFF333333.toInt())
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                }.also { addView(it) }

                logText = TextView(context).apply {
                    textAppearance = android.R.style.TextAppearance_Material_Caption
                    setTextColor(0xFF666666.toInt())
                    setHorizontallyScrolling(true)
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                }
                addView(logText)
            }
            addView(bottomPanel)

            // Set up key listener on the root view
            isFocusableInTouchMode = true
            requestFocus()
            setOnKeyListener { _, keyCode, event ->
                handleKeyEvent(keyCode, event)
                true
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        updateDisplay(0, null, null, null)

        // Apply window insets for bottom padding
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val navBars = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            v.updatePadding(bottom = navBars.bottom)
            insets
        }
    }

    private fun handleKeyEvent(keyCode: Int, event: KeyEvent) {
        val keyCodeName = KeyEvent.keyCodeToString(keyCode)
        val actionName = when (event.action) {
            KeyEvent.ACTION_DOWN -> "ACTION_DOWN"
            KeyEvent.ACTION_UP -> "ACTION_UP"
            KeyEvent.ACTION_MULTIPLE -> "ACTION_MULTIPLE"
            else -> "UNKNOWN(${event.action})"
        }

        // Build modifiers string
        val modifiers = buildModifiersString(event)

        // Get character if applicable
        val char = event.unicodeChar.let { unicode ->
            if (unicode != 0) {
                val ch = unicode.toChar()
                val hex = "U+%04X".format(unicode)
                "'$ch' ($hex)"
            } else {
                null
            }
        }

        // Update display
        updateDisplay(keyCode, keyCodeName, actionName, modifiers, char)

        // Add to log
        keyEventCount++
        val logLine = "#$keyEventCount: $keyCodeName($keyCode) $actionName${modifiers.takeIf { it.isNotEmpty() }?.let { " [$it]" } ?: ""}${char?.let { " $it" } ?: ""}"
        logBuilder.insert(0, logLine + "\n")
        if (logBuilder.length > 2000) {
            logBuilder.delete(2000, logBuilder.length)
        }
        logText.text = logBuilder.toString()
    }

    private fun buildModifiersString(event: KeyEvent): String {
        val mods = mutableListOf<String>()
        if (event.isShiftPressed) mods.add("SHIFT")
        if (event.isCtrlPressed) mods.add("CTRL")
        if (event.isAltPressed) mods.add("ALT")
        if (event.isMetaPressed) mods.add("META")
        if (event.isCapsLockOn) mods.add("CAPS_LOCK")
        if (event.isNumLockOn) mods.add("NUM_LOCK")
        if (event.isScrollLockOn) mods.add("SCROLL_LOCK")
        if (event.isSymPressed) mods.add("SYM")
        if (event.isFunctionPressed) mods.add("FN")
        return mods.joinToString(" + ")
    }

    private fun updateDisplay(
        keyCode: Int,
        keyCodeName: String?,
        actionName: String?,
        modifiers: String?,
        char: String? = null
    ) {
        keyCodeText.text = if (keyCodeName != null && keyCode != 0) {
            getString(R.string.key_code_format, keyCodeName, keyCode)
        } else {
            getString(R.string.key_code_waiting)
        }

        modifiersText.text = if (!modifiers.isNullOrEmpty()) {
            getString(R.string.modifiers_format, modifiers)
        } else {
            getString(R.string.modifiers_none)
        }

        actionText.text = if (actionName != null) {
            getString(R.string.action_format, actionName)
        } else {
            getString(R.string.action_waiting)
        }

        charText.text = if (char != null) {
            getString(R.string.char_format, char)
        } else {
            getString(R.string.char_none)
        }
    }
}