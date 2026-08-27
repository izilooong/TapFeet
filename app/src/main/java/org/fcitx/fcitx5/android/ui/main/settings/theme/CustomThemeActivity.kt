/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.theme

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Parcelable
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.webkit.MimeTypeMap
import android.widget.LinearLayout
import android.widget.SeekBar
import androidx.activity.addCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContract
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.parcelize.Parcelize
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemeFilesManager
import org.fcitx.fcitx5.android.data.theme.ThemePreset
import org.fcitx.fcitx5.android.ui.common.withLoadingDialog
import org.fcitx.fcitx5.android.ui.main.CropImageActivity.CropContract
import org.fcitx.fcitx5.android.ui.main.CropImageActivity.CropOption
import org.fcitx.fcitx5.android.ui.main.CropImageActivity.CropResult
import org.fcitx.fcitx5.android.utils.DarkenColorFilter
import org.fcitx.fcitx5.android.utils.item
import org.fcitx.fcitx5.android.utils.parcelable
import splitties.dimensions.dp
import splitties.resources.color
import splitties.resources.resolveThemeAttribute
import splitties.resources.styledColor
import splitties.resources.styledDrawable
import splitties.views.backgroundColor
import splitties.views.bottomPadding
import splitties.views.dsl.appcompat.switch
import splitties.views.dsl.constraintlayout.before
import splitties.views.dsl.constraintlayout.below
import splitties.views.dsl.constraintlayout.bottomOfParent
import splitties.views.dsl.constraintlayout.centerHorizontally
import splitties.views.dsl.constraintlayout.constraintLayout
import splitties.views.dsl.constraintlayout.endOfParent
import splitties.views.dsl.constraintlayout.lParams
import splitties.views.dsl.constraintlayout.matchConstraints
import splitties.views.dsl.constraintlayout.startOfParent
import splitties.views.dsl.constraintlayout.topOfParent
import splitties.views.dsl.constraintlayout.topToTopOf
import splitties.views.dsl.core.add
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.seekBar
import splitties.views.dsl.core.textView
import splitties.views.dsl.core.view
import splitties.views.dsl.core.wrapContent
import splitties.views.dsl.core.wrapInScrollView
import splitties.views.gravityVerticalCenter
import splitties.views.horizontalPadding
import splitties.views.textAppearance
import splitties.views.topPadding
import java.io.File
import java.util.UUID

class CustomThemeActivity : AppCompatActivity() {

    sealed interface BackgroundResult : Parcelable {
        @Parcelize
        data class Updated(val theme: Theme.Custom) : BackgroundResult

        @Parcelize
        data class Created(val theme: Theme.Custom) : BackgroundResult

        @Parcelize
        data class Deleted(val name: String) : BackgroundResult
    }

    class Contract : ActivityResultContract<Pair<Theme.Custom?, Boolean>, BackgroundResult?>() {
        override fun createIntent(context: Context, input: Pair<Theme.Custom?, Boolean>): Intent =
            Intent(context, CustomThemeActivity::class.java).apply {
                putExtra(ORIGIN_THEME, input.first)
                putExtra(NEW_COLOR_ONLY, input.second)
            }

        override fun parseResult(resultCode: Int, intent: Intent?): BackgroundResult? =
            intent?.parcelable(RESULT)
    }

    private val toolbar by lazy {
        view(::Toolbar) {
            backgroundColor = styledColor(android.R.attr.colorPrimary)
            elevation = dp(4f)
        }
    }

    private lateinit var previewUi: KeyboardPreviewUi

    private fun createTextView(@StringRes string: Int? = null, ripple: Boolean = false) = textView {
        if (string != null) {
            setText(string)
        }
        gravity = gravityVerticalCenter
        textAppearance = resolveThemeAttribute(android.R.attr.textAppearanceListItem)
        horizontalPadding = dp(16)
        if (ripple) {
            background = styledDrawable(android.R.attr.selectableItemBackground)
        }
    }

    private val variantLabel by lazy {
        createTextView(R.string.dark_keys, ripple = true)
    }
    private val variantSwitch by lazy {
        switch {
            // Use dark keys by default
            isChecked = false
        }
    }

    private val brightnessLabel by lazy {
        createTextView(R.string.brightness)
    }
    private val brightnessValue by lazy {
        createTextView()
    }
    private val brightnessSeekBar by lazy {
        seekBar {
            max = 100
        }
    }

    private val cropLabel by lazy {
        createTextView(R.string.recrop_image, ripple = true)
    }

    private val scrollView by lazy {
        val lineHeight = dp(48)
        val itemMargin = dp(30)
        constraintLayout {
            bottomPadding = dp(24)
            // Plain top-to-bottom chain via `below` only. When a middle control is
            // set GONE (e.g. color-only theme), the chain simply skips it and the
            // following views stick right under the last visible one — no stray gaps.
            add(previewUi.root, lParams(wrapContent, wrapContent) {
                topOfParent()
                centerHorizontally()
            })
            add(cropLabel, lParams(matchConstraints, lineHeight) {
                below(previewUi.root)
                centerHorizontally(itemMargin)
            })
            add(variantLabel, lParams(matchConstraints, lineHeight) {
                below(cropLabel)
                startOfParent(itemMargin)
                before(variantSwitch)
            })
            add(variantSwitch, lParams(wrapContent, lineHeight) {
                topToTopOf(variantLabel)
                endOfParent(itemMargin)
            })
            add(brightnessLabel, lParams(matchConstraints, lineHeight) {
                below(variantLabel)
                startOfParent(itemMargin)
                before(brightnessValue)
            })
            add(brightnessValue, lParams(wrapContent, lineHeight) {
                topToTopOf(brightnessLabel)
                endOfParent(itemMargin)
            })
            add(brightnessSeekBar, lParams(matchConstraints, wrapContent) {
                below(brightnessLabel)
                centerHorizontally(itemMargin)
            })
            val colorList = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
            }
            colorContainer = colorList
            add(colorList, lParams(matchConstraints, wrapContent) {
                below(brightnessSeekBar)
                centerHorizontally(itemMargin)
            })
        }.wrapInScrollView {
            isFillViewport = true
        }
    }

    private val ui by lazy {
        constraintLayout {
            add(toolbar, lParams(matchParent, wrapContent) {
                topOfParent()
                centerHorizontally()
            })
            add(scrollView, lParams {
                below(toolbar)
                centerHorizontally()
                bottomOfParent()
            })
        }
    }

    private var newCreated = true

    private lateinit var theme: Theme.Custom

    private lateinit var colorContainer: LinearLayout
    private val colorSwatches = mutableMapOf<ColorField, View>()
    private var currentBackgroundDrawable: Drawable? = null

    private data class ColorField(
        @StringRes val label: Int,
        val get: (Theme.Custom) -> Int,
        val set: (Theme.Custom, Int) -> Theme.Custom
    )

    private data class ColorGroup(
        @StringRes val title: Int,
        val fields: List<ColorField>
    )

    private val COLOR_GROUPS = listOf(
        ColorGroup(R.string.color_group_overall, listOf(
            ColorField(R.string.background_color, { it.backgroundColor }, { t, c -> t.copy(backgroundColor = c) }),
            ColorField(R.string.keyboard_color, { it.keyboardColor }, { t, c -> t.copy(keyboardColor = c) }),
            ColorField(R.string.divider_color, { it.dividerColor }, { t, c -> t.copy(dividerColor = c) }),
            ColorField(R.string.clipboard_entry_color, { it.clipboardEntryColor }, { t, c -> t.copy(clipboardEntryColor = c) })
        )),
        ColorGroup(R.string.color_group_candidate, listOf(
            ColorField(R.string.bar_color, { it.barColor }, { t, c -> t.copy(barColor = c) }),
            ColorField(R.string.candidate_text_color, { it.candidateTextColor }, { t, c -> t.copy(candidateTextColor = c) }),
            ColorField(R.string.candidate_label_color, { it.candidateLabelColor }, { t, c -> t.copy(candidateLabelColor = c) }),
            ColorField(R.string.candidate_comment_color, { it.candidateCommentColor }, { t, c -> t.copy(candidateCommentColor = c) })
        )),
        ColorGroup(R.string.color_group_key, listOf(
            ColorField(R.string.key_background_color, { it.keyBackgroundColor }, { t, c -> t.copy(keyBackgroundColor = c) }),
            ColorField(R.string.key_text_color, { it.keyTextColor }, { t, c -> t.copy(keyTextColor = c) }),
            ColorField(R.string.key_press_highlight_color, { it.keyPressHighlightColor }, { t, c -> t.copy(keyPressHighlightColor = c) }),
            ColorField(R.string.key_shadow_color, { it.keyShadowColor }, { t, c -> t.copy(keyShadowColor = c) }),
            ColorField(R.string.spacebar_color, { it.spaceBarColor }, { t, c -> t.copy(spaceBarColor = c) })
        )),
        ColorGroup(R.string.color_group_special_key, listOf(
            ColorField(R.string.alt_key_background_color, { it.altKeyBackgroundColor }, { t, c -> t.copy(altKeyBackgroundColor = c) }),
            ColorField(R.string.alt_key_text_color, { it.altKeyTextColor }, { t, c -> t.copy(altKeyTextColor = c) }),
            ColorField(R.string.accent_key_background_color, { it.accentKeyBackgroundColor }, { t, c -> t.copy(accentKeyBackgroundColor = c) }),
            ColorField(R.string.accent_key_text_color, { it.accentKeyTextColor }, { t, c -> t.copy(accentKeyTextColor = c) })
        )),
        ColorGroup(R.string.color_group_popup, listOf(
            ColorField(R.string.popup_background_color, { it.popupBackgroundColor }, { t, c -> t.copy(popupBackgroundColor = c) }),
            ColorField(R.string.popup_text_color, { it.popupTextColor }, { t, c -> t.copy(popupTextColor = c) })
        )),
        ColorGroup(R.string.color_group_generic, listOf(
            ColorField(R.string.generic_active_background_color, { it.genericActiveBackgroundColor }, { t, c -> t.copy(genericActiveBackgroundColor = c) }),
            ColorField(R.string.generic_active_foreground_color, { it.genericActiveForegroundColor }, { t, c -> t.copy(genericActiveForegroundColor = c) })
        ))
    )

    private class BackgroundStates {
        lateinit var launcher: ActivityResultLauncher<CropOption>
        var srcImageExtension: String? = null
        var srcImageBuffer: ByteArray? = null
        var cropRect: Rect? = null
        var cropRotation: Int = 0
        lateinit var croppedBitmap: Bitmap
        lateinit var filteredDrawable: BitmapDrawable
        lateinit var srcImageFile: File
        lateinit var croppedImageFile: File
    }

    private val backgroundStates by lazy { BackgroundStates() }

    private inline fun whenHasBackground(
        block: BackgroundStates.(Theme.Custom.CustomBackground) -> Unit,
    ) {
        if (theme.backgroundImage != null)
            block(backgroundStates, theme.backgroundImage!!)
    }

    private fun BackgroundStates.setKeyVariant(
        background: Theme.Custom.CustomBackground,
        darkKeys: Boolean
    ) {
        val template = if (darkKeys) ThemePreset.TransparentLight else ThemePreset.TransparentDark
        theme = template.deriveCustomBackground(
            theme.name,
            background.croppedFilePath,
            background.srcFilePath,
            brightnessSeekBar.progress,
            background.cropRect,
            background.cropRotation
        )
        applyThemeToPreview()
        refreshSwatches()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // recover from bundle
        val colorOnly = intent?.getBooleanExtra(NEW_COLOR_ONLY, false) ?: false
        val originTheme = intent?.parcelable<Theme.Custom>(ORIGIN_THEME)?.also { t ->
            theme = t
            whenHasBackground {
                croppedImageFile = File(it.croppedFilePath)
                srcImageFile = File(it.srcFilePath)
                cropRect = it.cropRect
                cropRotation = it.cropRotation
                croppedBitmap = BitmapFactory.decodeFile(it.croppedFilePath)
                filteredDrawable = BitmapDrawable(resources, croppedBitmap)
                currentBackgroundDrawable = filteredDrawable
            }
            newCreated = false
        }
        // create new
        if (originTheme == null) {
            if (colorOnly) {
                // blank color-only theme: derive from a neutral preset, without a background image
                theme = ThemePreset.PixelDark.deriveCustomNoBackground(UUID.randomUUID().toString())
                newCreated = true
            } else {
                val (n, c, s) = ThemeFilesManager.newCustomBackgroundImages()
                backgroundStates.apply {
                    croppedImageFile = c
                    srcImageFile = s
                }
                // Use dark keys by default
                theme = ThemePreset.TransparentDark.deriveCustomBackground(n, c.path, s.path)
                newCreated = true
            }
        }
        previewUi = KeyboardPreviewUi(this, theme)
        if (theme.backgroundImage == null) {
            brightnessLabel.visibility = View.GONE
            cropLabel.visibility = View.GONE
            variantLabel.visibility = View.GONE
            variantSwitch.visibility = View.GONE
            brightnessSeekBar.visibility = View.GONE
        }
        enableEdgeToEdge()
        ViewCompat.setOnApplyWindowInsetsListener(ui) { _, windowInsets ->
            val statusBars = windowInsets.getInsets(WindowInsetsCompat.Type.statusBars())
            val navBars = windowInsets.getInsets(WindowInsetsCompat.Type.navigationBars())
            ui.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                leftMargin = navBars.left
                rightMargin = navBars.right
            }
            toolbar.topPadding = statusBars.top
            scrollView.bottomPadding = navBars.bottom
            windowInsets
        }
        // show Activity label on toolbar
        setSupportActionBar(toolbar)
        // show back button
        supportActionBar!!.setDisplayHomeAsUpEnabled(true)
        setContentView(ui)
        buildColorRows()
        whenHasBackground { background ->
            brightnessSeekBar.progress = background.brightness
            variantSwitch.isChecked = !theme.isDark
            launcher = registerForActivityResult(CropContract()) {
                when (it) {
                    CropResult.Fail -> {
                        if (newCreated) {
                            cancel()
                        }
                    }
                    is CropResult.Success -> {
                        if (newCreated) {
                            srcImageExtension = MimeTypeMap.getSingleton()
                                .getExtensionFromMimeType(contentResolver.getType(it.srcUri))
                            srcImageBuffer =
                                contentResolver.openInputStream(it.srcUri)!!
                                    .use { x -> x.readBytes() }
                        }
                        cropRect = it.rect
                        cropRotation = it.rotation
                        croppedBitmap = it.bitmap
                        filteredDrawable = BitmapDrawable(resources, croppedBitmap)
                        currentBackgroundDrawable = filteredDrawable
                        updateState()
                    }
                }
            }
            cropLabel.setOnClickListener {
                launchCrop(previewUi.intrinsicWidth, previewUi.intrinsicHeight)
            }
            variantLabel.setOnClickListener {
                variantSwitch.isChecked = !variantSwitch.isChecked
            }
            // attach OnCheckedChangeListener after calling setChecked (isChecked in kotlin)
            variantSwitch.setOnCheckedChangeListener { _, isChecked ->
                setKeyVariant(background, darkKeys = isChecked)
            }
            brightnessSeekBar.setOnSeekBarChangeListener(object :
                SeekBar.OnSeekBarChangeListener {
                override fun onStartTrackingTouch(bar: SeekBar) {}
                override fun onStopTrackingTouch(bar: SeekBar) {}

                override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                    if (fromUser) updateState()
                }
            })
        }

        if (newCreated) {
            cropLabel.visibility = View.GONE
            whenHasBackground {
                previewUi.onSizeMeasured = { w, h ->
                    launchCrop(w, h)
                }
            }
        } else {
            whenHasBackground {
                updateState()
            }
        }

        onBackPressedDispatcher.addCallback {
            cancel()
        }
    }

    private fun BackgroundStates.launchCrop(w: Int, h: Int) {
        if (newCreated) {
            launcher.launch(CropOption.New(w, h))
        } else {
            launcher.launch(
                CropOption.Edit(
                    width = w,
                    height = h,
                    Uri.fromFile(srcImageFile),
                    initialRect = cropRect,
                    initialRotation = cropRotation
                )
            )
        }
    }

    @SuppressLint("SetTextI18n")
    private fun BackgroundStates.updateState() {
        val progress = brightnessSeekBar.progress
        brightnessValue.text = "$progress%"
        filteredDrawable.colorFilter = DarkenColorFilter(100 - progress)
        previewUi.setBackground(filteredDrawable)
    }

    private fun applyThemeToPreview() {
        previewUi.setTheme(theme, currentBackgroundDrawable)
    }

    private fun refreshSwatches() {
        colorSwatches.forEach { (field, view) -> setSwatchColor(view, field.get(theme)) }
    }

    private fun setSwatchColor(view: View, color: Int) {
        val lum = (0.299f * ((color shr 16) and 0xFF) +
                   0.587f * ((color shr 8) and 0xFF) +
                   0.114f * (color and 0xFF)) / 255f
        val border = if (lum > 0.5f) 0x22000000 else 0x22FFFFFF
        view.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(8f)
            setColor(color)
            setStroke(dp(1), border)
        }
    }

    private fun buildColorRows() {
        colorContainer.removeAllViews()
        colorSwatches.clear()
        colorContainer.addView(createTextView(R.string.theme_colors).apply {
            layoutParams = LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(8)
                bottomMargin = dp(4)
            }
        })
        COLOR_GROUPS.forEachIndexed { gi, group ->
            if (gi > 0) {
                colorContainer.addView(View(this).apply {
                    background = styledDrawable(android.R.attr.listDivider)
                    layoutParams = LinearLayout.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT, dp(1)
                    ).apply { topMargin = dp(12) }
                })
            }
            val accent = theme.accentKeyBackgroundColor.let {
                if (((it ushr 24) and 0xFF) > 0 && it != 0) it else 0xFF2196F3.toInt()
            }
            val accentBar = View(this).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(2f)
                    setColor(accent)
                }
                layoutParams = LinearLayout.LayoutParams(dp(4), dp(18))
            }
            val headerTitle = textView {
                setText(group.title)
                gravity = gravityVerticalCenter
                textAppearance = resolveThemeAttribute(android.R.attr.textAppearanceListItem)
                typeface = android.graphics.Typeface.defaultFromStyle(android.graphics.Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, dp(24), 1f).apply {
                    marginStart = dp(12)
                }
            }
            val header = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = gravityVerticalCenter
                setPadding(dp(16), dp(10), dp(16), dp(4))
                addView(accentBar)
                addView(headerTitle)
            }
            colorContainer.addView(header)
            group.fields.forEach { field ->
                val swatch = View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply {
                        marginStart = dp(16)
                    }
                }
                setSwatchColor(swatch, field.get(theme))
                val label = textView {
                    setText(field.label)
                    gravity = gravityVerticalCenter
                    textAppearance = resolveThemeAttribute(android.R.attr.textAppearanceListItem)
                    layoutParams = LinearLayout.LayoutParams(0, dp(52), 1f)
                }
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = gravityVerticalCenter
                    foreground = styledDrawable(android.R.attr.selectableItemBackground)
                    setPadding(dp(16), 0, dp(16), 0)
                    addView(label)
                    addView(swatch)
                    setOnClickListener {
                        ColorPickerDialog(this@CustomThemeActivity, field.get(theme)) { newColor ->
                            theme = field.set(theme, newColor)
                            setSwatchColor(swatch, newColor)
                            applyThemeToPreview()
                        }.show()
                    }
                }
                colorContainer.addView(row)
            }
        }
    }

    private fun cancel() {
        setResult(
            RESULT_CANCELED,
            Intent().apply { putExtra(RESULT, null as BackgroundResult?) }
        )
        finish()
    }

    private fun done() {
        lifecycleScope.withLoadingDialog(this) {
            whenHasBackground {
                withContext(Dispatchers.IO) {
                    croppedImageFile.delete()
                    croppedImageFile.outputStream().use {
                        croppedBitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                    if (newCreated) {
                        if (srcImageExtension != null) {
                            srcImageFile = File("${srcImageFile.absolutePath}.$srcImageExtension")
                            theme = theme.copy(
                                backgroundImage = it.copy(
                                    srcFilePath = srcImageFile.absolutePath
                                )
                            )
                        }
                        srcImageFile.writeBytes(srcImageBuffer!!)
                    }
                }
            }
            setResult(
                RESULT_OK,
                Intent().apply {
                    var newTheme = theme
                    whenHasBackground {
                        newTheme = theme.copy(
                            backgroundImage = it.copy(
                                brightness = brightnessSeekBar.progress,
                                cropRect = cropRect,
                                cropRotation = cropRotation
                            )
                        )
                    }
                    putExtra(
                        RESULT,
                        if (newCreated)
                            BackgroundResult.Created(newTheme)
                        else
                            BackgroundResult.Updated(newTheme)
                    )
                })
            finish()
        }
    }

    private fun delete() {
        setResult(
            RESULT_OK,
            Intent().apply {
                putExtra(RESULT, BackgroundResult.Deleted(theme.name))
            }
        )
        finish()
    }

    private fun promptDelete() {
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_theme)
            .setMessage(getString(R.string.delete_theme_msg, theme.name))
            .setPositiveButton(android.R.string.ok) { _, _ ->
                delete()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        if (!newCreated) {
            val iconTint = color(R.color.red_400)
            menu.item(R.string.save, R.drawable.ic_baseline_delete_24, iconTint, true) {
                promptDelete()
            }
        }
        val iconTint = styledColor(android.R.attr.colorControlNormal)
        menu.item(R.string.save, R.drawable.ic_baseline_check_24, iconTint, true) {
            done()
        }
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem) = when (item.itemId) {
        android.R.id.home -> {
            cancel()
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    companion object {
        const val RESULT = "result"
        const val ORIGIN_THEME = "origin_theme"
        const val NEW_COLOR_ONLY = "new_color_only"
    }
}
