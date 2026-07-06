# TapFeet IME - Code Wiki

## 1. 项目概述

### 1.1 项目简介

**TapFeet IME**（大脚输入法）是一个基于 [fcitx5-android](https://github.com/fcitx5-android/fcitx5-android) 二次开发的 Android 输入法应用，核心目标是为 **Q25 全键盘手机**（硬件对标 BlackBerry Classic Q20）提供极致的中文输入体验。

| 属性 | 说明 |
|------|------|
| **项目名称** | TapFeet IME / fcitx5-Q25 |
| **应用 ID** | `tapfeet.ime` |
| **许可证** | LGPL-2.1-or-later |
| **主要平台** | Android（针对 Q25 设备优化） |
| **核心框架** | Fcitx5 + libime |

### 1.2 Q25 设备专项适配

项目针对 Q25 设备的独特硬件特性做了以下定向优化：

- **物理键盘选字**：适配物理键盘五大金刚键(⬆️ 0️⃣ 🈳 sym ⬆️)选字
- **组合键选字**：深度适配 Alt + 数字组合键逻辑，物理数字键直接选词（1~5 对应候选词位置）
- **方形屏幕 UI 优化**：针对 1:1 非标准屏幕比例（720×720）优化候选栏显示
- **物理键盘映射**：完全适配 Q25 的物理键盘扫描码
- **性能与续航优化**：精简非核心过渡动画，减少后台词库同步频率

### 1.3 支持的输入法

- **中文**：拼音、双拼、五笔、仓颉、粤拼、注音（通过插件）
- **英语**：含拼写检查
- **日语**：通过 Anthy 插件
- **韩语**：通过 Hangul 插件
- **越南语**：通过 UniKey 插件
- **泰语**：通过 Thai 插件
- **僧伽罗语**：通过 Sayura 插件
- **通用输入法**：通过 RIME 插件

---

## 2. 项目架构

### 2.1 整体架构图

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                           Android 应用层 (Kotlin)                           │
├─────────────────────────────────────────────────────────────────────────────┤
│  UI Layer                                                                    │
│  ├── Settings UI (SettingsFragment, ThemeSettingsFragment, ...)             │
│  ├── Input UI (InputWindow, InputWindowManager, CandidateView, ...)         │
│  └── Setup UI (SetupActivity, SetupFragment, ...)                           │
├─────────────────────────────────────────────────────────────────────────────┤
│  Service Layer                                                               │
│  ├── LifecycleInputMethodService (输入法服务基类)                            │
│  └── FcitxRemoteService (远程服务)                                           │
├─────────────────────────────────────────────────────────────────────────────┤
│  Core Layer                                                                  │
│  ├── Fcitx (核心类，封装 JNI)                                                │
│  ├── FcitxAPI (API 接口定义)                                                │
│  ├── FcitxDaemon (单例管理)                                                 │
│  ├── FcitxDispatcher (线程调度)                                             │
│  └── FcitxLifecycle (生命周期管理)                                          │
├─────────────────────────────────────────────────────────────────────────────┤
│  Data Layer                                                                  │
│  ├── DataManager (文件系统同步)                                              │
│  ├── ThemeManager (主题管理)                                                │
│  ├── ClipboardManager (剪贴板管理)                                           │
│  └── UserDataManager (用户数据管理)                                          │
├─────────────────────────────────────────────────────────────────────────────┤
│                          JNI 层 (C++)                                        │
├─────────────────────────────────────────────────────────────────────────────┤
│  native-lib.cpp (JNI 桥接)                                                   │
│  ├── Fcitx 类 (封装 fcitx5 Instance)                                        │
│  ├── JNI 方法实现                                                           │
│  └── 回调机制 (CandidateList, CommitString, Preedit, ...)                   │
├─────────────────────────────────────────────────────────────────────────────┤
│                        Fcitx5 核心库 (C++)                                   │
├─────────────────────────────────────────────────────────────────────────────┤
│  lib/fcitx5/       - Fcitx5 主框架                                          │
│  lib/libime/       - 输入法引擎库                                            │
│  lib/fcitx5-lua/   - Lua 支持                                               │
│  lib/fcitx5-chinese-addons/ - 中文输入法插件                                 │
├─────────────────────────────────────────────────────────────────────────────┤
│                        输入法插件层                                          │
├─────────────────────────────────────────────────────────────────────────────┤
│  plugin/rime/      - RIME 输入法                                            │
│  plugin/anthy/     - 日语输入法                                             │
│  plugin/hangul/    - 韩语输入法                                             │
│  plugin/chewing/   - 注音输入法                                             │
│  plugin/jyutping/  - 粤拼输入法                                             │
│  plugin/thai/      - 泰语输入法                                             │
│  plugin/unikey/    - 越南语输入法                                           │
│  plugin/sayura/    - 僧伽罗语输入法                                          │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 2.2 模块结构

| 模块 | 路径 | 职责 |
|------|------|------|
| **app** | `app/` | 主应用模块，包含 UI、服务和核心逻辑 |
| **lib:common** | `lib/common/` | 通用工具库 |
| **lib:fcitx5** | `lib/fcitx5/` | Fcitx5 核心库（C++） |
| **lib:libime** | `lib/libime/` | 输入法引擎库（C++） |
| **lib:fcitx5-lua** | `lib/fcitx5-lua/` | Lua 支持库 |
| **lib:fcitx5-chinese-addons** | `lib/fcitx5-chinese-addons/` | 中文输入法插件 |
| **lib:plugin-base** | `lib/plugin-base/` | 插件基础库 |
| **codegen** | `codegen/` | 代码生成模块 |
| **plugin:\*** | `plugin/*/` | 各类输入法插件 |

---

## 3. 核心模块详解

### 3.1 Fcitx 核心模块

#### 3.1.1 Fcitx 类

**文件**: [app/src/main/java/org/fcitx/fcitx5/android/core/Fcitx.kt](file:///d:/tapfeet/tapfeet/app/src/main/java/org/fcitx/fcitx5/android/core/Fcitx.kt)

`Fcitx` 是项目的核心类，封装了所有与 Fcitx5 引擎交互的 JNI 调用。它实现了 `FcitxAPI` 接口，并管理 Fcitx5 的生命周期。

**关键职责**:
- 加载 native 库 (`native-lib`)
- 管理 Fcitx5 的启动/停止
- 封装所有 JNI 方法调用
- 处理 Fcitx5 事件回调
- 管理输入法状态缓存

**核心方法**:

| 方法 | 功能 | 协程安全 |
|------|------|----------|
| `start()` | 启动 Fcitx5 引擎 | 是 |
| `stop()` | 停止 Fcitx5 引擎 | 是 |
| `sendKey()` | 发送按键事件 | 是 |
| `select(idx)` | 选择候选词 | 是 |
| `activateIme(ime)` | 激活指定输入法 | 是 |
| `currentIme()` | 获取当前输入法 | 是 |
| `getGlobalConfig()` | 获取全局配置 | 是 |
| `setGlobalConfig()` | 设置全局配置 | 是 |

**事件处理机制**:

Fcitx5 通过回调机制向 Android 层发送事件。`Fcitx` 类注册了事件处理器，将原生事件转换为 Kotlin 的 `FcitxEvent` 对象，并通过 `eventFlow` 共享流发布。

```kotlin
// 事件处理流程
private fun handleFcitxEvent(event: FcitxEvent<*>) {
    when (event) {
        is FcitxEvent.ReadyEvent -> lifecycleRegistry.postEvent(FcitxLifecycle.Event.ON_READY)
        is FcitxEvent.IMChangeEvent -> inputMethodEntryCached = event.data
        is FcitxEvent.StatusAreaEvent -> { /* 更新状态栏 */ }
        is FcitxEvent.ClientPreeditEvent -> clientPreeditCached = event.data
        is FcitxEvent.InputPanelEvent -> inputPanelCached = event.data
        else -> {}
    }
}
```

#### 3.1.2 FcitxAPI 接口

**文件**: [app/src/main/java/org/fcitx/fcitx5/android/core/FcitxAPI.kt](file:///d:/tapfeet/tapfeet/app/src/main/java/org/fcitx/fcitx5/android/core/FcitxAPI.kt)

定义了 Fcitx5 的 API 接口，所有方法都是挂起函数，确保在 Fcitx 线程中执行。

**接口分类**:

| 类别 | 方法示例 | 功能 |
|------|----------|------|
| **键盘输入** | `sendKey()`, `select()`, `reset()` | 发送按键、选择候选词、重置输入 |
| **输入法管理** | `activateIme()`, `currentIme()`, `enabledIme()` | 激活、查询、管理输入法 |
| **配置管理** | `getGlobalConfig()`, `setAddonConfig()`, `getImConfig()` | 读取和设置配置 |
| **状态查询** | `statusArea()`, `getCandidates()` | 获取状态栏和候选词 |

#### 3.1.3 FcitxDaemon

**文件**: [app/src/main/java/org/fcitx/fcitx5/android/daemon/FcitxDaemon.kt](file:///d:/tapfeet/tapfeet/app/src/main/java/org/fcitx/fcitx5/android/daemon/FcitxDaemon.kt)

管理 Fcitx 单例的连接和生命周期，采用客户端-服务器模式。

**设计要点**:
- 使用 `ReentrantLock` 保证线程安全
- 维护客户端连接列表，当最后一个客户端断开时自动停止 Fcitx
- 提供 `FcitxConnection` 接口供客户端使用

```kotlin
// 连接管理逻辑
fun connect(name: String): FcitxConnection = lock.withLock {
    if (name in clients) return@withLock clients.getValue(name)
    if (realFcitx.lifecycle.currentState == FcitxLifecycle.State.STOPPED) {
        realFcitx.start()
    }
    val new = mkConnection(name)
    clients[name] = new
    return@withLock new
}
```

#### 3.1.4 FcitxDispatcher

**文件**: [app/src/main/java/org/fcitx/fcitx5/android/core/FcitxDispatcher.kt](file:///d:/tapfeet/tapfeet/app/src/main/java/org/fcitx/fcitx5/android/core/FcitxDispatcher.kt)

Fcitx5 的主线程调度器，负责驱动 Fcitx5 的事件循环。

**核心机制**:
- 创建名为 `fcitx-main` 的单线程执行器
- 循环调用 `nativeLoopOnce()` 处理 Fcitx5 事件
- 使用队列处理待执行的协程任务
- 通过 `nativeScheduleEmpty()` 唤醒阻塞的事件循环

#### 3.1.5 FcitxLifecycle

**文件**: [app/src/main/java/org/fcitx/fcitx5/android/core/FcitxLifecycle.kt](file:///d:/tapfeet/tapfeet/app/src/main/java/org/fcitx/fcitx5/android/core/FcitxLifecycle.kt)

管理 Fcitx5 的生命周期状态。

**状态转换**:

```
STOPPED → STARTING → READY → STOPPING → STOPPED
     ↑                    │              │
     └────────────────────┴──────────────┘
```

**状态说明**:

| 状态 | 说明 |
|------|------|
| `STOPPED` | Fcitx5 未运行 |
| `STARTING` | Fcitx5 正在启动 |
| `READY` | Fcitx5 已就绪，可以接收输入 |
| `STOPPING` | Fcitx5 正在停止 |

### 3.2 事件系统

#### 3.2.1 FcitxEvent 类型

**文件**: [app/src/main/java/org/fcitx/fcitx5/android/core/FcitxEvent.kt](file:///d:/tapfeet/tapfeet/app/src/main/java/org/fcitx/fcitx5/android/core/FcitxEvent.kt)

定义了所有从 Fcitx5 引擎发出的事件类型：

| 事件类型 | 数据内容 | 触发时机 |
|----------|----------|----------|
| `CandidateListEvent` | 候选词列表 | 候选词更新时 |
| `CommitStringEvent` | 提交的文本和光标位置 | 文本提交时 |
| `ClientPreeditEvent` | 预编辑文本 | 预编辑内容变化时 |
| `InputPanelEvent` | 输入面板数据（预编辑、辅助文本、标签） | 输入面板更新时 |
| `ReadyEvent` | 无 | Fcitx5 启动完成时 |
| `KeyEvent` | 按键信息 | 键盘事件传递时 |
| `IMChangeEvent` | 输入法条目 | 输入法切换时 |
| `StatusAreaEvent` | 状态栏动作和输入法状态 | 状态栏更新时 |
| `DeleteSurroundingEvent` | 删除范围（前、后） | 需要删除周围文本时 |
| `PagedCandidateEvent` | 分页候选词数据 | 候选词分页更新时 |
| `SwitchInputMethodEvent` | 切换原因和旧输入法 | 输入法切换原因 |

### 3.3 输入窗口管理

#### 3.3.1 InputWindowManager

**文件**: [app/src/main/java/org/fcitx/fcitx5/android/input/wm/InputWindowManager.kt](file:///d:/tapfeet/tapfeet/app/src/main/java/org/fcitx/fcitx5/android/input/wm/InputWindowManager.kt)

管理输入窗口的切换和生命周期。

**核心功能**:
- 管理 `EssentialWindow`（必需窗口，如键盘窗口）
- 处理窗口切换动画
- 管理键盘可见性状态

**窗口类型**:

| 类型 | 说明 |
|------|------|
| `EssentialWindow` | 必需窗口，视图会被缓存，可随时切换 |
| `SimpleInputWindow` | 简单窗口，用于临时界面 |
| `ExtendedInputWindow` | 扩展窗口，支持标题栏 |

### 3.4 数据管理

#### 3.4.1 DataManager

**文件**: [app/src/main/java/org/fcitx/fcitx5/android/core/data/DataManager.kt](file:///d:/tapfeet/tapfeet/app/src/main/java/org/fcitx/fcitx5/android/core/data/DataManager.kt)

管理应用的文件系统层次结构，负责同步主应用和插件的数据。

**核心功能**:
- 检测和加载插件
- 创建文件系统层次结构
- 同步数据变更（创建、更新、删除文件和符号链接）
- 使用设备加密存储（Android N+）

**插件检测流程**:
1. 通过 `PackageManager` 查询 `PLUGIN_INTENT` 匹配的包
2. 解析插件的 `plugin.xml` 描述符
3. 验证 API 版本兼容性
4. 合并插件数据到文件系统层次结构

#### 3.4.2 ThemeManager

**文件**: [app/src/main/java/org/fcitx/fcitx5/android/data/theme/ThemeManager.kt](file:///d:/tapfeet/tapfeet/app/src/main/java/org/fcitx/fcitx5/android/data/theme/ThemeManager.kt)

管理输入法主题，支持内置主题、自定义主题和 Android 12+ 动态取色（Monet）。

**内置主题**:
- Material Light / Dark
- Pixel Light / Dark
- Nord Light / Dark
- DeepBlue
- Monokai
- AMOLED Black

### 3.5 输入法服务

#### 3.5.1 LifecycleInputMethodService

**文件**: [app/src/main/java/org/fcitx/fcitx5/android/input/LifecycleInputMethodService.kt](file:///d:/tapfeet/tapfeet/app/src/main/java/org/fcitx/fcitx5/android/input/LifecycleInputMethodService.kt)

Android 输入法服务的基类，集成了 Android Lifecycle 框架。

---

## 4. JNI 层实现

### 4.1 native-lib.cpp

**文件**: [app/src/main/cpp/native-lib.cpp](file:///d:/tapfeet/tapfeet/app/src/main/cpp/native-lib.cpp)

这是项目的核心 JNI 文件，实现了所有从 Kotlin 到 C++ 的桥接。

#### 4.1.1 Fcitx 类（C++）

封装了 Fcitx5 的 `Instance` 对象，提供单例访问。

**关键成员**:

| 成员 | 类型 | 说明 |
|------|------|------|
| `p_instance` | `fcitx::Instance*` | Fcitx5 实例 |
| `p_dispatcher` | `fcitx::EventDispatcher*` | 事件调度器 |
| `p_frontend` | `fcitx::AddonInstance*` | Android 前端插件 |
| `p_quickphrase` | `fcitx::AddonInstance*` | 快捷短语插件 |
| `p_unicode` | `fcitx::AddonInstance*` | Unicode 输入插件 |
| `p_clipboard` | `fcitx::AddonInstance*` | 剪贴板插件 |

#### 4.1.2 JNI 方法

所有 JNI 方法遵循命名规范 `Java_org_fcitx_fcitx5_android_core_Fcitx_<methodName>`。

**方法分类**:

| 类别 | 方法示例 |
|------|----------|
| **生命周期** | `startupFcitx()`, `exitFcitx()`, `saveFcitxState()` |
| **键盘输入** | `sendKeyToFcitxString()`, `selectCandidate()` |
| **输入法管理** | `setInputMethod()`, `listInputMethods()`, `availableInputMethods()` |
| **配置管理** | `getFcitxGlobalConfig()`, `setFcitxAddonConfig()` |
| **事件循环** | `loopOnce()`, `scheduleEmpty()` |

#### 4.1.3 回调机制

Fcitx5 通过回调函数向 Android 层传递事件：

```cpp
// 候选词列表回调
auto candidateListCallback = [](const std::vector<CandidateEntity> &candidates, const int total) {
    // 将数据转换为 Java 对象并通过 handleFcitxEvent 传递
    env->CallStaticVoidMethod(GlobalRef->Fcitx, GlobalRef->HandleFcitxEvent, 0, *vararg);
};
```

**回调类型**（对应 Kotlin 的 `FcitxEvent`）:

| 回调方法 | 事件类型 |
|----------|----------|
| `setCandidateListCallback` | CandidateListEvent (0) |
| `setCommitStringCallback` | CommitStringEvent (1) |
| `setPreeditCallback` | ClientPreeditEvent (2) |
| `setInputPanelCallback` | InputPanelEvent (3) |
| `setKeyEventCallback` | KeyEvent (5) |
| `setInputMethodChangeCallback` | IMChangeEvent (6) |
| `setStatusAreaUpdateCallback` | StatusAreaEvent (7) |
| `setDeleteSurroundingCallback` | DeleteSurroundingEvent (8) |
| `setPagedCandidateCallback` | PagedCandidateEvent (9) |
| `setSwitchInputMethodCallback` | SwitchInputMethodEvent (10) |

---

## 5. 插件系统

### 5.1 插件架构

项目支持从独立 APK 加载输入法插件。插件通过 `plugin.xml` 描述符注册，并通过 Intent 机制被主应用发现。

### 5.2 插件描述符

每个插件必须在 `res/xml/plugin.xml` 中声明：

```xml
<plugin>
    <apiVersion>1</apiVersion>
    <domain>fcitx5-rime</domain>
    <description>@string/plugin_description</description>
    <hasService>true</hasService>
</plugin>
```

### 5.3 内置插件

| 插件 | 路径 | 功能 |
|------|------|------|
| **rime** | `plugin/rime/` | RIME 输入法引擎 |
| **anthy** | `plugin/anthy/` | 日语 Anthy 输入法 |
| **hangul** | `plugin/hangul/` | 韩语输入法 |
| **chewing** | `plugin/chewing/` | 注音输入法 |
| **jyutping** | `plugin/jyutping/` | 粤拼输入法 |
| **thai** | `plugin/thai/` | 泰语输入法 |
| **unikey** | `plugin/unikey/` | 越南语输入法 |
| **sayura** | `plugin/sayura/` | 僧伽罗语输入法 |
| **clipboard-filter** | `plugin/clipboard-filter/` | 剪贴板过滤器 |

---

## 6. 依赖关系

### 6.1 模块依赖

```
app
├── lib:common
├── lib:fcitx5
├── lib:libime
├── lib:fcitx5-lua
├── lib:fcitx5-chinese-addons
└── lib:plugin-base

plugin:rime
├── lib:fcitx5
├── lib:libime
└── lib:plugin-base

plugin:anthy
├── lib:fcitx5
└── lib:plugin-base

plugin:hangul
├── lib:fcitx5
└── lib:plugin-base

plugin:chewing
├── lib:fcitx5
└── lib:plugin-base

plugin:jyutping
├── lib:fcitx5
├── lib:libime
└── lib:plugin-base

plugin:thai
├── lib:fcitx5
└── lib:plugin-base

plugin:unikey
├── lib:fcitx5
└── lib:plugin-base

plugin:sayura
├── lib:fcitx5
└── lib:plugin-base
```

### 6.2 第三方依赖

| 依赖 | 版本 | 用途 |
|------|------|------|
| **Kotlin** | 2.3.21 | 主要开发语言 |
| **Kotlin Coroutines** | 1.10.2 | 异步编程 |
| **Kotlinx Serialization** | 1.11.0 | JSON 序列化 |
| **AndroidX Lifecycle** | 2.10.0 | 生命周期管理 |
| **AndroidX Navigation** | 2.9.8 | 导航组件 |
| **AndroidX Room** | 2.8.4 | 数据库 |
| **Material Components** | 1.13.0 | UI 组件 |
| **Flexbox** | 3.0.0 | 弹性布局 |
| **Timber** | 5.0.1 | 日志 |
| **Splitties** | 3.0.0 | Android 工具库 |
| **Arrow** | 2.2.2.1 | 函数式编程 |
| **Dependency** | 0.1.2 | 依赖注入 |

---

## 7. 项目运行方式

### 7.1 环境要求

- **Android SDK Platform & Build-Tools**: 35
- **Android NDK (Side by side)**: 25
- **CMake**: 3.22.1
- **extra-cmake-modules**: KDE 构建依赖
- **GNU Gettext**: >= 0.20（需要 `msgfmt` 命令）

### 7.2 构建步骤

```shell
# 克隆仓库
git clone https://github.com/izilooong/fcitx5-Q25.git
cd fcitx5-Q25

# 初始化子模块
git submodule update --init --recursive

# 编译
./gradlew assembleDebug

# 安装到设备
./gradlew installDebug

# 构建发布版本
./gradlew assembleRelease
```

### 7.3 Windows 用户注意事项

1. 开启 Windows 开发者模式（允许创建符号链接）
2. 为 Git 启用符号链接支持：
   ```shell
   git config --global core.symlinks true
   ```
3. 安装 MSYS2 UCRT64 环境并添加到 PATH

### 7.4 Nix 环境支持

项目提供了 Nix 开发环境配置，包含合适的 Android SDK 与 NDK：

```shell
# 在 Nix 环境下直接使用
nix-shell
./gradlew installDebug
```

---

## 8. 关键配置文件

### 8.1 构建配置

| 文件 | 说明 |
|------|------|
| `build.gradle.kts` (根) | 项目级构建配置 |
| `settings.gradle.kts` | 模块配置和仓库管理 |
| `gradle/libs.versions.toml` | 依赖版本管理 |
| `app/build.gradle.kts` | 应用模块构建配置 |

### 8.2 应用配置

| 文件 | 说明 |
|------|------|
| `app/src/main/AndroidManifest.xml` | 应用清单 |
| `app/src/main/res/xml/input_method.xml` | 输入法配置 |
| `app/src/main/res/values/strings.xml` | 字符串资源 |
| `app/src/main/res/values/themes.xml` | 主题配置 |

---

## 9. 目录结构总结

```
tapfeet/
├── app/                    # 主应用模块
│   ├── src/main/java/      # Kotlin 源代码
│   │   ├── core/           # 核心模块（Fcitx, API, Lifecycle）
│   │   ├── daemon/         # 守护进程管理
│   │   ├── data/           # 数据管理（主题、剪贴板、用户数据）
│   │   ├── input/          # 输入相关（服务、窗口、UI）
│   │   ├── ui/             # 用户界面（设置、主界面）
│   │   └── utils/          # 工具类
│   ├── src/main/cpp/       # C++ 源代码（JNI）
│   └── src/main/res/       # 资源文件
├── lib/                    # 核心库模块
│   ├── fcitx5/             # Fcitx5 主框架
│   ├── libime/             # 输入法引擎
│   ├── fcitx5-lua/         # Lua 支持
│   ├── fcitx5-chinese-addons/ # 中文输入法插件
│   └── plugin-base/        # 插件基础库
├── plugin/                 # 输入法插件
│   ├── rime/               # RIME 输入法
│   ├── anthy/              # 日语输入法
│   ├── hangul/             # 韩语输入法
│   ├── chewing/            # 注音输入法
│   ├── jyutping/           # 粤拼输入法
│   ├── thai/               # 泰语输入法
│   ├── unikey/             # 越南语输入法
│   └── sayura/             # 僧伽罗语输入法
├── build-logic/            # 构建逻辑
├── codegen/                # 代码生成
├── gradle/                 # Gradle 配置
├── .github/workflows/      # CI/CD 配置
├── README.md               # 项目说明
└── LICENSE                 # 许可证
```

---

## 10. 扩展开发指南

### 10.1 添加新输入法插件

1. 在 `plugin/` 目录下创建新模块
2. 创建 `plugin.xml` 描述符
3. 实现插件的 C++ 代码
4. 在 `settings.gradle.kts` 中注册模块
5. 在 `app/build.gradle.kts` 的 `fcitxComponent` 中添加依赖

### 10.2 自定义主题

1. 创建 `Theme.Custom` 对象
2. 通过 `ThemeManager.saveTheme()` 保存
3. 通过 `ThemeManager.setNormalModeTheme()` 设置为当前主题

### 10.3 监听 Fcitx5 事件

```kotlin
val connection = FcitxDaemon.connect("my-client")
connection.runOnReady {
    eventFlow.collect { event ->
        when (event) {
            is FcitxEvent.CommitStringEvent -> { /* 处理文本提交 */ }
            is FcitxEvent.CandidateListEvent -> { /* 处理候选词更新 */ }
            // ... 其他事件
        }
    }
}
```

---

## 11. 许可证信息

本项目继承原项目（fcitx5-android）的 **LGPL-2.1-or-later** 许可证：

- 若对核心库代码进行了修改，修改部分必须以相同的 LGPL-2.1 许可证公开
- 若仅将本项目作为动态链接库使用（未修改库本身），专有代码可保持闭源，但需在文档中声明使用了 LGPL 库