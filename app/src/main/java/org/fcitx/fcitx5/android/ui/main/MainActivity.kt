/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.ViewGroup
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.graphics.drawable.DrawerArrowDrawable
import androidx.appcompat.widget.Toolbar
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.forEach
import androidx.core.view.updateLayoutParams
import androidx.navigation.NavController
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.fragment.NavHostFragment
import org.fcitx.fcitx5.android.BuildConfig
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.HardwareKeyProfiles
import org.fcitx.fcitx5.android.databinding.ActivityMainBinding
import org.fcitx.fcitx5.android.input.TouchProbeLog
import org.fcitx.fcitx5.android.ui.main.settings.SettingsRoute
import org.fcitx.fcitx5.android.ui.setup.SetupActivity
import org.fcitx.fcitx5.android.utils.Const
import org.fcitx.fcitx5.android.utils.LauncherAliasManager
import org.fcitx.fcitx5.android.utils.item
import org.fcitx.fcitx5.android.utils.navigateWithAnim
import org.fcitx.fcitx5.android.utils.parcelable
import org.fcitx.fcitx5.android.utils.startActivity
import splitties.dimensions.dp
import splitties.resources.styledColor
import splitties.views.topPadding

class MainActivity : AppCompatActivity() {

    private val viewModel: MainViewModel by viewModels()

    private lateinit var navController: NavController

    // 状态栏高度（px）：既注入 toolbar 顶部 padding，重算 toolbar 高度时也要带上它。
    private var statusBarTopPx = 0

    // 主页右侧「切换键盘预设」按钮，仅主页可见。
    private var homePresetMenuItem: MenuItem? = null

    // 整页 binding：showPresetPicker 改完预设要手动刷新副标题，需要拿到 toolbar。
    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, windowInsets ->
            val statusBars = windowInsets.getInsets(WindowInsetsCompat.Type.statusBars())
            val navBars = windowInsets.getInsets(WindowInsetsCompat.Type.navigationBars())
            binding.root.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                leftMargin = navBars.left
                rightMargin = navBars.right
            }
            binding.toolbar.topPadding = statusBars.top
            statusBarTopPx = statusBars.top
            refreshToolbarHeight(binding.toolbar)
            windowInsets
        }
        setContentView(binding.root)
        // always show toolbar back arrow icon
        // https://android.googlesource.com/platform/frameworks/support/+/32e643112d0217619237a0d7101b50919c6caf51/navigation/navigation-ui/src/main/java/androidx/navigation/ui/AbstractAppBarOnDestinationChangedListener.kt#80
        binding.toolbar.navigationIcon = DrawerArrowDrawable(this).apply { progress = 1f }
        // 主标题、副标题各压一档，两层头部别互相抢视觉。
        binding.toolbar.setTitleTextAppearance(this, R.style.TextAppearance_Toolbar_Title)
        binding.toolbar.setSubtitleTextAppearance(this, R.style.TextAppearance_Toolbar_Subtitle)
        // logo 与标题之间留白，别贴在一起。
        binding.toolbar.setTitleMarginStart(dp(20))
        // show menu icon and other action icons on toolbar
        // don't use `setSupportActionBar(binding.toolbar)` here,
        // because navController would change toolbar title, we need to control it by ourselves
        setupToolbarMenu(binding.toolbar.menu)
        navController = binding.navHostFragment.getFragment<NavHostFragment>().navController
        navController.graph = SettingsRoute.createGraph(navController)
        binding.toolbar.setNavigationOnClickListener {
            // prevent navigate up when child fragment has enabled `OnBackPressedCallback`
            if (onBackPressedDispatcher.hasEnabledCallbacks()) {
                onBackPressedDispatcher.onBackPressed()
                return@setNavigationOnClickListener
            }
            // "minimize" the activity if we can't go back
            navController.navigateUp() || onSupportNavigateUp() || moveTaskToBack(false)
        }
        viewModel.toolbarTitle.observe(this) {
            binding.toolbar.title = it
        }
        viewModel.toolbarShadow.observe(this) {
            binding.toolbar.elevation = dp(if (it) 4f else 0f)
        }
        navController.addOnDestinationChangedListener { _, dest, _ ->
            // 主页导航栏：图标 + 应用名（标题行）+ 版本·布局（副标题行）；子页还原成页面标题。
            // 键盘预设改完返回主页时这个回调会重跑一次，所以不用另找刷新时机。
            if (dest.hasRoute<SettingsRoute.Index>()) {
                binding.toolbar.logo = appIconSized(dp(48))
                binding.toolbar.subtitle = homeSubtitleText()
                viewModel.setToolbarTitle(homeHeaderTitle())
            } else {
                binding.toolbar.logo = null
                binding.toolbar.subtitle = null
                dest.label?.let { viewModel.setToolbarTitle(it.toString()) }
            }
            refreshToolbarHeight(binding.toolbar)
            // 「切换键盘预设」按钮仅主页显示。
            homePresetMenuItem?.isVisible = dest.hasRoute<SettingsRoute.Index>()
            if (dest.hasRoute<SettingsRoute.Theme>()) {
                viewModel.disableToolbarShadow()
            } else {
                viewModel.enableToolbarShadow()
            }
        }
        processIntent(intent)
        checkNotificationPermission()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        processIntent(intent)
    }

    /**
     * Lab-page probe only (no behaviour change): the physical keyboard's touch surface usually
     * lands on the app window, because the IME squeezes its own touchable region down to a sliver in
     * physical-keyboard mode. Overriding here — rather than a view-level touch listener — is what
     * lets the probe see every touch in this window, including the ones a child view consumes.
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        TouchProbeLog.record(TouchProbeLog.PATH_APP_WINDOW, ev)
        return super.dispatchTouchEvent(ev)
    }

    /**
     * Lab-page probe only (no behaviour change): hover and pointer motion — the keyboard surface's
     * pointer/mouse mode, and any real mouse — are delivered through the generic-motion path rather
     * than the touch path, so hooking only [dispatchTouchEvent] makes those coordinates look like
     * they never arrive at all.
     */
    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        TouchProbeLog.record(TouchProbeLog.PATH_APP_MOTION, ev)
        return super.dispatchGenericMotionEvent(ev)
    }

    /* ========== 主页导航栏上的应用信息 ========== */

    /** 主页标题：只放应用名；版本挪到副标题，跟键盘布局同一行。 */
    private fun homeHeaderTitle(): String = appDisplayName()

    /**
     * 应用名跟随「App 显示名称」里选的那套 launcher alias —— 改成「黑莓输入法」导航栏也得叫这个，
     * 不能固定取 app_name。（debug 后缀由 alias 引用的 @string/app_name 自带。）
     */
    private fun appDisplayName(): String = runCatching {
        val key = AppPrefs.getInstance().internal.appDisplayName.getValue()
        val res = LauncherAliasManager.OPTIONS.firstOrNull { it.first == key }?.second
            ?: R.string.app_name
        getString(res)
    }.getOrDefault(getString(R.string.app_name))

    /**
     * 运行时实读已安装 APK 的版本：BuildConfig 是编译期常量，应用内升级后进程里还是旧值。
     * 版本名本身可能已带前缀（gradle.properties 是 `V1.0.11`），带了就别再叠一个 v。
     */
    private val versionLabel: String
        get() {
            @Suppress("DEPRECATION")
            val raw = runCatching {
                packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
            }.getOrDefault(BuildConfig.VERSION_NAME)
            return if (raw.startsWith("v", ignoreCase = true)) raw else "v$raw"
        }

    /** 副标题：版本 + 当前键盘预设名，同一行；不再带「当前布局:」前缀。 */
    private fun homeSubtitleText(): String = getString(
        R.string.main_header_subtitle,
        versionLabel,
        getString(
            HardwareKeyProfiles.labelResFor(
                AppPrefs.getInstance().hardwareKeyboard.keyProfile.getValue()
            )
        )
    )

    /**
     * 导航栏高度：主页要装「图标 + 名称/版本 + 当前布局」两层文本，内容区给 72dp（默认 56dp 太挤）；
     * 子页只有一行标题，保持 56dp。工具栏是 wrap_content + 注入的状态栏 padding，所以按「状态栏 + 内容区」写死高度。
     */
    private fun refreshToolbarHeight(toolbar: Toolbar) {
        val isHome = ::navController.isInitialized &&
            navController.currentDestination?.hasRoute<SettingsRoute.Index>() == true
        val px = statusBarTopPx + dp(if (isHome) 72 else 56)
        toolbar.updateLayoutParams<ViewGroup.MarginLayoutParams> { height = px }
    }

    /**
     * 导航栏 logo。包一层自定义 Drawable 改写 intrinsic 尺寸：Toolbar 是按 drawable 的原始尺寸摆 logo 的，
     * 直接把 app 图标塞进去会撑出一大块，把标题挤没。（不走 DrawableWrapper——本工程 appcompat 版未暴露该类。）
     */
    private fun appIconSized(px: Int): Drawable? = runCatching {
        val icon = applicationInfo.loadIcon(packageManager)
        object : Drawable() {
            override fun draw(canvas: Canvas) = icon.draw(canvas)
            override fun setAlpha(alpha: Int) { icon.alpha = alpha }
            override fun setColorFilter(colorFilter: ColorFilter?) { icon.colorFilter = colorFilter }
            @Suppress("OVERRIDE_DEPRECATION")
            override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
            override fun getIntrinsicWidth() = px
            override fun getIntrinsicHeight() = px
            override fun onBoundsChange(bounds: Rect) { icon.bounds = bounds }
        }
    }.getOrNull()

    private fun processIntent(intent: Intent?) {
        val action = intent?.action ?: return
        when (action) {
            Intent.ACTION_MAIN -> if (SetupActivity.shouldShowUp()) {
                startActivity<SetupActivity>()
            }
            Intent.ACTION_VIEW -> intent.data?.let {
                AlertDialog.Builder(this)
                    .setTitle(R.string.pinyin_dict)
                    .setMessage(R.string.whether_import_dict)
                    .setNegativeButton(android.R.string.cancel) { _, _ -> }
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        navController.popBackStack(SettingsRoute.Index, false)
                        navController.navigateWithAnim(SettingsRoute.PinyinDict(it))
                    }
                    .show()
            }
            Intent.ACTION_RUN -> {
                val route = intent.parcelable<SettingsRoute>(EXTRA_SETTINGS_ROUTE) ?: return
                navController.popBackStack(SettingsRoute.Index, false)
                navController.navigateWithAnim(route)
            }
        }
    }

    private fun setupToolbarMenu(menu: Menu) {
        val iconTint = styledColor(android.R.attr.colorControlNormal)
        menu.item(R.string.save, R.drawable.ic_baseline_save_24, iconTint, true) {
            viewModel.toolbarSaveButtonOnClickListener.value?.invoke()
        }.apply {
            viewModel.toolbarSaveButtonOnClickListener
                .observe(this@MainActivity) { listener -> isVisible = listener != null }
        }
        val aboutMenuItems = listOf(
            menu.item(R.string.faq) {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Const.faqUrl)))
            },
            menu.item(R.string.developer) {
                navController.navigateWithAnim(SettingsRoute.Developer)
            },
            menu.item(R.string.about) {
                navController.navigateWithAnim(SettingsRoute.About)
            }
        )
        viewModel.aboutButton.observe(this@MainActivity) { enabled ->
            aboutMenuItems.forEach { menu -> menu.isVisible = enabled }
        }
        menu.item(R.string.edit, R.drawable.ic_baseline_edit_24, iconTint, true) {
            viewModel.toolbarEditButtonOnClickListener.value?.invoke()
        }.apply {
            viewModel.toolbarEditButtonVisible.observe(this@MainActivity) { isVisible = it }
        }
        menu.item(R.string.delete, R.drawable.ic_baseline_delete_24, iconTint, true) {
            viewModel.toolbarDeleteButtonOnClickListener.value?.invoke()
        }.apply {
            viewModel.toolbarDeleteButtonOnClickListener
                .observe(this@MainActivity) { listener -> isVisible = listener != null }
        }
        menu.item(R.string.clear, R.drawable.ic_baseline_delete_sweep_24, iconTint, true) {
            viewModel.toolbarClearButtonOnClickListener.value?.invoke()
        }.apply {
            viewModel.toolbarClearButtonOnClickListener
                .observe(this@MainActivity) { listener -> isVisible = listener != null }
        }
        // 主页专属：右侧「切换键盘预设」按钮，仅主页可见（可见性在目的地监听里切换）。
        val presetItem = menu.item(R.string.switch_keyboard_preset, R.drawable.ic_baseline_keyboard_24, iconTint, true) {
            showPresetPicker()
        }
        presetItem.isVisible = false
        homePresetMenuItem = presetItem
        // all menus should be invisible and enabled on demand
        menu.forEach { it.isVisible = false }
    }

    /**
     * 主页右上「切换键盘预设」：单选对话框列出 [HardwareKeyProfiles.ids]，
     * 选中即写 keyProfile 并走 [HardwareKeyProfiles.applyProfile] 单一入口（含快捷键预设）。
     * 留在主页，目的地监听不会重跑，这里手动刷新副标题里的预设名。
     */
    private fun showPresetPicker() {
        val prefs = AppPrefs.getInstance()
        val hw = prefs.hardwareKeyboard
        val ids = HardwareKeyProfiles.ids()
        val names = ids.map { getString(HardwareKeyProfiles.labelResFor(it)) }.toTypedArray()
        var checked = ids.indexOf(hw.keyProfile.getValue()).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle(R.string.switch_keyboard_preset)
            .setSingleChoiceItems(names, checked) { _, which -> checked = which }
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val id = ids[checked]
                hw.keyProfile.setValue(id)
                HardwareKeyProfiles.applyProfile(id, prefs)
                binding.toolbar.subtitle = homeSubtitleText()
            }
            .show()
    }

    private var needNotifications by AppPrefs.getInstance().internal.needNotifications

    private fun checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                needNotifications = true
                return
            }
            // do not ask again if user denied the request
            if (!needNotifications) return
            // always show a dialog to explain why we need notification permission,
            // regardless of `shouldShowRequestPermissionRationale(...)`
            AlertDialog.Builder(this)
                .setIconAttribute(android.R.attr.alertDialogIcon)
                .setTitle(R.string.notification_permission_title)
                .setMessage(R.string.notification_permission_message)
                .setNegativeButton(R.string.i_do_not_need_it) { _, _ ->
                    // do not ask again if user denied the request
                    needNotifications = false
                }
                .setPositiveButton(R.string.grant_permission) { _, _ ->
                    requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
                }
                .show()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != 0) return
        // do not ask again if user denied the request
        needNotifications = grantResults.getOrNull(0) == PackageManager.PERMISSION_GRANTED
    }

    override fun onStop() {
        viewModel.fcitx.runIfReady {
            save()
        }
        super.onStop()
    }

    companion object {
        const val EXTRA_SETTINGS_ROUTE = "${BuildConfig.APPLICATION_ID}.EXTRA_SETTINGS_ROUTE"
    }

}
