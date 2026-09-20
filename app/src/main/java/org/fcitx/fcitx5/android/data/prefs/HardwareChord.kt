/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 TapFeet Contributors
 */

package org.fcitx.fcitx5.android.data.prefs

import android.view.KeyEvent

/**
 * 「伪修饰键和弦」：`Fn+字母` / `Sym+字母`。
 *
 * **为什么需要它**：[org.fcitx.fcitx5.android.core.KeyState] 只有 Shift / Ctrl / Alt / Meta / Super /
 * Hyper 六个修饰位，**没有 Fn，也没有 Sym**；[HardwareSpecialKeys] 那种伪键只能表达「独立按一下」。
 * 于是「按住 Fn 再按字母」这套在 Titan 键盘上最自然的组合，在现有模型里无处安放。本对象补的正是
 * 这一格：字符串层面加 `Fn+` / `Sym+` 前缀，运行期靠**我们自己跟踪**「修饰键是不是按着」。
 *
 * 存储串形如 `"Fn+e"`（Titan2 / Titan2 Elite）或 `"Sym+e"`（BlackBerry —— 那台机器的 Sym 键上报
 * 右 Alt `Alt_R`，走 `Sym+` 前缀而不是直接写 `Alt+e`：真修饰键的 meta 分不出左右 Alt，会把左 Alt
 * 那份**系统原生键帽符号**一起吞掉，详见 [SYM] 与 `modifierHeld`）。解析时先 [split] 出前缀，
 * 内层键照旧走原来那套（伪键名 → fcitx5 portableString）。
 *
 * ⚠️ 两端约定：
 * - 状态由 [org.fcitx.fcitx5.android.input.FcitxInputMethodService] 的按下/抬起喂进来
 *   （[onKeyDown] / [onKeyUp]），会话结束必须 [reset]；
 * - 读取方是**两份**按键解析拷贝（[org.fcitx.fcitx5.android.input.candidates.HardwareShortcutResolver]
 *   与 `InputView`），项目铁律：改匹配两处同改。
 *
 * ⚠️ 本对象只存**运行期状态**，不落盘。状态僵死比不生效危险得多 —— 一个卡住的「Fn 按住」会把
 * 每次按字母都判成和弦（按 E 就切特效）。所以：抬起即清、会话收尾即清，绝不引入「只能靠超时才
 * 能自愈」的隐含假设（本项目在 CommitEffectsOverlay 的看门狗上已经吃过一次同类亏）。
 */
object HardwareChord {

    /**
     * Titan 系列 Fn 键的**存储前缀名 / 键帽丝印名**。
     *
     * ⚠️ 它**不是**伪键表里的注册名 —— 那边叫 `NavFn`（刻意加 `Nav` 前缀，避开 fcitx5 原生键名
     * `Fn` 的碰撞）。两个串的对应关系只登记在 [HardwareSpecialKeys.Entry.chordModifier]，
     * 判定按住状态必须走那一份；**任何地方都不许拿本常量去比伪键的注册名**（比错不会报错，
     * 只会静默失效 —— 这个坑已经踩过一次）。
     */
    const val FN = "Fn"

    /**
     * SYM 键（独立的物理按钮，专键专用）。
     *
     * ⚠️ SYM 与右 Alt（[KeyEvent.KEYCODE_ALT_RIGHT]）是**两个独立按钮**，识别不能混为一坛：右
     * Alt 是真正的修饰键，走 fcitx5 keysym 路径当作 `Alt_R`；SYM 才是这个伪键，只匹配
     * `KEYCODE_SYM` / `KEYCODE_PICTSYMBOLS`。[HardwareSpecialKeys] 的 `Sym` 条目**已不含**
     * `KEYCODE_ALT_RIGHT`，所以两个名字不再收敛到同一个物理键 —— 右 Alt 不会再被错判成 `Sym`。
     *
     * [setHeld] 给的 `symDown` 只跟踪 SYM 按钮本身（**分左右**，比 meta 可靠）。
     *
     * ⚠️ 黑莓预设**不再**把动作快捷键建在这个前缀上（那边整块不提供这套配置，见
     * [HardwareKeyProfiles.actionShortcutsAvailable]）—— 但前缀本身仍然有效：用户在捕获窗口里
     * 手工拼 `Sym+字母` 照样能绑、能匹配。**绝不要改用 `Alt+字母`**：真修饰键的 meta 分不出左右
     * Alt，会把左 Alt（键帽符号 + Alt Latch 用的那个）一起吞掉，详见 [modifierHeld]。
     */
    const val SYM = "Sym"

    /**
     * 前缀 → 修饰键名。[split] / [compose] 共用；**别处不许再写第二份前缀字面量**
     * （键名抄两份必然漂移，页面的键位绑定已经栽过一次）。
     */
    private val prefixToName: List<Pair<String, String>> = listOf(
        "$FN+" to FN,
        "$SYM+" to SYM,
    )

    /** 可用来拼和弦的**真**修饰键（Fn / Sym 走 [HardwareSpecialKeys] 那一条）。 */
    private val realModifierKeyCodes = setOf(
        KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_ALT_RIGHT,
        KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_CTRL_RIGHT,
        KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT,
    )

    /** 拆出（修饰键名, 内层键串）。不是和弦时返回 `("", 原串)`，调用方按原样处理。 */
    fun split(keyString: String): Pair<String, String> {
        prefixToName.forEach { (prefix, name) ->
            if (keyString.length > prefix.length && keyString.startsWith(prefix, ignoreCase = true)) {
                return name to keyString.substring(prefix.length)
            }
        }
        return "" to keyString
    }

    /** 拼回存储串。修饰键或内层键为空时按内层键原样返回（空内层键 = 不绑定）。 */
    fun compose(modifier: String, inner: String): String =
        if (modifier.isEmpty() || inner.isEmpty()) inner else "$modifier+$inner"

    // ------------------------------------------------------------------ 按住状态

    private var fnDown = false
    private var symDown = false

    /** 被 tap-hold 挂起的符号键；null = 没有挂起。见 [armSymbolTap]。 */
    private var pendingSymbolTapKeyCode: Int? = null

    /**
     * 物理键按下。记录修饰键按住状态，并作废不相关的挂起轻按。
     *
     * ⚠️ 只在**非捕获页**调用（捕获页里按键是给对话框用的，不该污染按住状态）。
     */
    fun onKeyDown(keyCode: Int) {
        HardwareSpecialKeys.entryForKeyCode(keyCode)?.let { setHeld(it, true) }
        // 任何「别的键」按下 ⇒ 这是一次修饰键手势（和弦 / 组合键），挂起的轻按作废。
        // 同键的 auto-repeat 不会命中这个条件，所以按住不放不会被自己清掉。
        if (pendingSymbolTapKeyCode != null && pendingSymbolTapKeyCode != keyCode) {
            pendingSymbolTapKeyCode = null
        }
    }

    /** 物理键抬起。按住状态只认抬起事件来清 —— 不靠超时，也不靠「别的键」替它收尾。 */
    fun onKeyUp(keyCode: Int) {
        HardwareSpecialKeys.entryForKeyCode(keyCode)?.let { setHeld(it, false) }
    }

    /** 会话收尾（失焦 / 解绑输入）：按住状态与挂起一起清，防止僵死成「每个字母都是和弦」。 */
    fun reset() {
        fnDown = false
        symDown = false
        pendingSymbolTapKeyCode = null
    }

    /**
     * 按伪键表里登记的「和弦修饰键」身份记一笔按住状态。
     *
     * ⚠️ 认的是 [HardwareSpecialKeys.Entry.chordModifier]，**不是**注册名 —— `NavFn`（注册名）与
     * `Fn`（键帽丝印名 / 存储前缀）是两个不同的串。曾经在这里拿注册名去比 [FN]，`fnDown` 永远是
     * false，`Fn+字母` 整条链路静默失效：配置界面一切正常，按下去毫无反应，符号窗口照旧抢键。
     */
    private fun setHeld(entry: HardwareSpecialKeys.Entry, down: Boolean) {
        when (entry.chordModifier) {
            FN -> fnDown = down
            SYM -> symDown = down
            // NavBack 之类的伪键不是修饰键，没有按住语义。
            else -> Unit
        }
    }

    /**
     * 该修饰键此刻是否算「按住」。两条路取并集：
     *  - 系统 meta（`isFunctionPressed` / `isSymPressed`）—— 平台真的把 Fn/Sym 当修饰键报时最准；
     *  - 我们自己跟踪的按下状态 —— 平台什么都不报时（Titan 的 Fn 是裸 keyCode 403）唯一的路。
     *
     * ⚠️ Sym **不许**再兜底认 `isAltPressed`（曾经这么写过，是个坑）：`isAltPressed` 读的是
     * META_ALT_ON，**分不出左右 Alt**。黑莓的 SYM 是右 Alt，而 Q25 的左 Alt + 字母恰恰是系统原生的
     * 「键帽符号」输入（Alt Latch 双击锁定也是为它服务的）—— 兜底一挂上，用户按左 Alt 打符号时
     * 动作先命中，那 6 个字母的键帽符号就再也打不出来了。注册表已把 `KEYCODE_ALT_RIGHT` 登记给
     * `Sym`，[setHeld] 的 `symDown` 是**分左右**的可靠状态，用它就够。
     */
    fun modifierHeld(name: String, event: KeyEvent): Boolean = when (name) {
        FN -> event.isFunctionPressed || fnDown
        SYM -> event.isSymPressed || symDown
        else -> false
    }

    /** 这个 keyCode 是不是「可以拿来拼和弦的修饰键」—— tap-hold 的适用条件之一。 */
    fun isChordModifierKeyCode(keyCode: Int): Boolean {
        HardwareSpecialKeys.entryForKeyCode(keyCode)?.let { return it.chordModifier != null }
        return keyCode in realModifierKeyCodes
    }

    // ------------------------------------------------------------- 符号窗口 tap-hold

    /**
     * 挂起「按下即切换符号窗口」。调用方已判定该键就是**符号窗口触发键**。
     *
     * 触发键要「按下就响应」，修饰键要「按住不响应」—— 同一个键两副面孔，天生冲突。标准解法是
     * tap-hold（QMK 的 Mod-Tap）：按下先挂起，松手时若没被别的键用掉，再补上「轻按」那一下。
     * 返回 true 表示已挂起，调用方应消费这次按下（不要切换窗口）。
     *
     * 不满足条件（不是修饰键）时返回 false，符号窗口照旧按下即切换 —— 对 BlackBerry 那种把
     * 符号键绑在纯修饰键上的情形才生效，绑在 `Sym` / `Alt+grave` 之类的普通键上时行为不变。
     */
    fun armSymbolTap(keyCode: Int): Boolean {
        if (!isChordModifierKeyCode(keyCode)) return false
        pendingSymbolTapKeyCode = keyCode
        return true
    }

    /** 松手时取走挂起：true = 这次轻按还欠一次符号窗口切换。 */
    fun consumeSymbolTap(keyCode: Int): Boolean {
        if (pendingSymbolTapKeyCode != keyCode) return false
        pendingSymbolTapKeyCode = null
        return true
    }
}
