# TapFeet IME (大脚输入法)

Android 输入法应用，基于 [fcitx5-android](https://github.com/fcitx5-android/fcitx5-android) 二次开发，为 Q25（BlackBerry Classic）全键盘手机优化中文输入体验。LGPL-2.1-or-later 许可证。

## Project

- **Stack**: Kotlin 2.3.21, Gradle (Kotlin DSL), Android SDK 35, NDK 25, CMake, C++ (JNI)
- **App ID**: `tapfeet.ime`
- **Entry points**:
  - `app/.../FcitxApplication.kt` — Application class
  - `app/.../ui/main/MainActivity.kt` — Main config UI
  - `app/.../ui/setup/SetupActivity.kt` — First-time setup
  - `app/.../input/LifecycleInputMethodService.kt` — IME service base class
  - `app/src/main/cpp/native-lib.cpp` — JNI bridge to Fcitx5 C++ core
- **Modules**: `app`, `:lib:common`, `:lib:fcitx5`, `:lib:libime`, `:lib:fcitx5-lua`, `:lib:fcitx5-chinese-addons`, `:lib:plugin-base`, `:codegen`, several `:plugin:*` modules

## Commands

```shell
./gradlew assembleDebug      # Build debug APK
./gradlew installDebug       # Build + install to connected device
./gradlew assembleRelease    # Build release APK (requires signing config)
./gradlew test               # Run unit tests (JUnit 4)
./gradlew clean              # Clean all build artifacts
./gradlew lint               # Run lint checks
git submodule update --init --recursive  # Required after clone
```

Build requires Android SDK 35, NDK 25, CMake 3.22.1, `extra-cmake-modules`, and GNU Gettext (`msgfmt`). On Windows: Developer Mode + Git symlinks enabled.

## Architecture

| Layer | Module | Role |
|-------|--------|------|
| **UI** | `app` | Settings, input window, setup — Android Fragments + ViewBinding |
| **Service** | `app` | `LifecycleInputMethodService` (IME), `FcitxRemoteService` |
| **Core** | `app/.../core/` | `Fcitx` (JNI wrapper), `FcitxAPI`, `FcitxDispatcher`, `FcitxLifecycle`, event system (`FcitxEvent`) |
| **Daemon** | `app/.../daemon/` | `FcitxDaemon` — singleton connection manager, client-server pattern |
| **Data** | `app/.../data/` | `DataManager` (filesystem sync), `ThemeManager`, `ClipboardManager`, `AppPrefs` |
| **C++ Core** | `lib/fcitx5/` | Fcitx5 main framework (C++) |
| **C++ Engine** | `lib/libime/` | Input method engine library (C++) |
| **C++ Addons** | `lib/fcitx5-lua/`, `lib/fcitx5-chinese-addons/` | Lua support, Chinese IME addons |
| **Plugins** | `plugin/*/` | Language IME plugins (anthy, rime, hangul, chewing, jyutping, thai, unikey, sayura, clipboard-filter) |

Events flow: C++ callbacks → `native-lib.cpp` JNI → `Fcitx.handleFcitxEvent()` → `FcitxEvent` sealed classes → `SharedFlow` → subscribers.

## Conventions

- **License header**: Every file must start with SPDX header (`SPDX-License-Identifier: LGPL-2.1-or-later` + `SPDX-FileCopyrightText`).
- **Kotlin style**: Official Kotlin code style (`kotlin.code.style=official`).
- **Package**: `org.fcitx.fcitx5.android.*` — sub-packages mirror feature (`core`, `data`, `input`, `ui`, `utils`, `daemon`, `provider`).
- **Async**: Kotlin Coroutines for all async work; `FcitxAPI` methods are `suspend` functions. `SharedFlow` for event streams.
- **Logging**: Use `Timber` (not `android.util.Log`).
- **UI**: ViewBinding enabled; AndroidX Fragments + Navigation component. Preference-based settings.
- **DI**: Simple manual DI via `Dependency` library (not Hilt/Koin) — see `app/.../provider/`.
- **Serialization**: `kotlinx.serialization` (JSON).
- **Persistence**: Room database for clipboard/user data; KSP for annotation processing.
- **Tests**: JUnit 4 runner (`androidx.test.runner.AndroidJUnitRunner`). Unit tests in `app/src/test/`.
- **Annotations**: `@Keep` for JNI-touched members, `@CallSuper` for lifecycle overrides.
- **C++**: JNI methods follow naming `Java_org_fcitx_fcitx5_android_core_Fcitx_<method>`.

## Notes

## Q25 适配分析

针对 `app/src/main/java/org/fcitx/fcitx5/android/` 目录分析，适配集中在物理键盘输入和 1:1 方形屏幕优化上：

### InputDeviceManager (input/InputDeviceManager.kt)
物理/虚拟键盘模式的切换中枢。引入 `FloatingCandidatesMode` 三档设置：
- `InputDevice`（跟随输入设备）— 检测到物理键盘按键自动切到硬键盘模式（隐藏虚拟键盘，显示悬浮候选窗）
- `SystemDefault` — 让系统决定
- `Disabled` — 永远使用虚拟键盘

触摸事件（`TOOL_TYPE_FINGER/STYLUS`）恢复虚拟键盘模式。

### Alt Latch 锁 Alt（FcitxInputMethodService）
- **双击左侧 Alt** 锁定 Alt 状态，后续按键自动附加 `META_ALT_ON`
- 按 Alt / Space / Enter 解锁，模拟 BlackBerry Classic 的 Alt 锁定体验
- `Alt + 数字` 组合键可直接物理选词

### 物理键盘选字（InputView + CandidateUi）
- `CandidateUi` 定义 5 个 BlackBerry 槽位常量：`BlackBerryLeftSlot(0)` / `InnerLeftSlot(1)` / `CenterSlot(2)` / `InnerRightSlot(3)` / `RightSlot(4)`
- `InputView.shortcutTargetForKeyCode()` 映射底排物理键到候选词位置：
  - **SHIFT_LEFT** → 槽位 0（最左）
  - **0 键** → 槽位 1
  - **SPACE** → 选中第一个候选
  - **SYM / ALT_RIGHT** → 槽位 3
  - **SHIFT_RIGHT** → 槽位 5（最右）
- 候选数为 1~5 时各有独立精细映射；大于 5 时使用通用 mapping

### 始终声明 InputView / 禁用全屏
- `FcitxInputMethodService.onEvaluateInputViewShown() = true` — 始终"认为"输入视图可见，确保硬件键盘触发时能显示悬浮候选窗
- `onEvaluateFullscreenMode() = false` — 禁用全屏模式，适配 Q25 的 1:1 方形屏幕（720×720）

### 悬浮候选窗（candidates/floating/）
- 物理键盘模式下自动弹出
- `inlineSuggestions` 在物理键盘模式下被禁用（避免 inline 建议与物理键盘候选冲突）
- 虚拟键盘模式隐藏、物理键盘模式保持可见

### 设置 UI 增强（ui/main/modified/）
- `MyPreferenceFragment` / `MySwitchPreference` / `MyListPreferenceDialogFragment` 等自定义 Preference 组件
- 通过反射恢复 Preference 默认值（`mDefaultValue` 字段）
- `Functions.kt` 提供 `restore()` 扩展函数和 `fixDialogMargin()` 工具

<!-- Quick-add notes below -->


# 原则
1. 不保留向后兼容。过时的直接删，别加兼容层、别写migration、别留fallback。
2. 选能满足当前需求的最简单实现。不要预防性抽象，不要多此一举的配置层。
3. 系统分层长。先跑通一个最小的端到端版本，再往上加东西。绝不为了未完成的复杂度拆掉能跑的东西。
4. 组件保持模块化，关注点分离。
5. 优先用成熟的、有人维护的库。没有明确理由别自己重写。
6. 先翻项目里已有的依赖能做什么，再考虑加新包或自己写。别上来就假设库里没有。
7. 架构决策往长了做。不接受"先这样以后再换"的临时方案。
8. 先看成熟产品怎么解决同一个问题，用已验证的模式，别从零发明。


