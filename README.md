
<img width="280" height="280" alt="快乐的大脚" src="https://github.com/user-attachments/assets/0af62fcc-6b11-4ec5-ae36-88fee7eb495b" />

# TapFeet IME · 大脚输入法

> 基于 [fcitx5-android](https://github.com/fcitx5-android/fcitx5-android) 二次开发，专为**带实体全键盘的 Android 手机**打造的中文输入法：选字、翻页、符号、编辑都能在键盘上完成，不用碰屏幕。
> 原项目将 [Fcitx5](https://github.com/fcitx/fcitx5) 框架与各输入引擎移植到 Android，本仓库在此基础上针对物理键盘做深度适配。
> 从 Q25（BlackBerry Classic 复刻机）起步，现有 BlackBerry / Titan2 / Titan2 Elite / Titan2 Elite（改键）/ Passport 五套键盘预设。
> 完整操作见 [用户操作手册](docs/USER_MANUAL.md)。

---

## 📲 下载与安装

> [下载最新版](https://github.com/izilooong/TapFeet/releases/latest) · 应用内支持在线升级，首次启用按 App 内「设置向导」引导。

---

## 📱 适配设备

| 键盘预设 | 代表机型 | 适配状态 |
| --- | --- | --- |
| **BlackBerry** | Q25（BlackBerry Classic 复刻） | ✅ 深度适配 |
| **Titan2** | Unihertz Titan2 | ✅ 已适配 |
| **Titan2 Elite** | Unihertz Titan2 Elite | ✅ 已适配 |
| **Titan2 Elite（改键）** | Titan2 Elite 改键后 | ✅ 已适配 |
| **Passport** | BlackBerry Passport（P26） | ✅ 已适配 |

> 理论上支持所有实体 QWERTY 键盘的 Android 设备，上表为已有预设、经实测的机型。其他设备欢迎 [提交反馈](https://github.com/izilooong/TapFeet/issues)。

---

## ⌨️ 物理键盘专项适配

以下特性在受支持的物理键盘设备上可用。首次使用请先在「设置 → 物理键盘 → 键盘布局预设」选好机型。

### 巨硬模式（物理键盘选字）

底排功能键直接对应候选词位置，无需触屏。排列模式在「设置 → 候选栏 → 排列」切换：**巨硬模式**按 4-2-1-3-5 居中展开，**普通模式**按 1-2-3-4-5 线性排布；底排物理键快速选字由「设置 → 物理键盘」里的开关控制。

| 候选位 | ④ | ② | ① | ③ | ⑤ |
| --- | --- | --- | --- | --- | --- |
| BlackBerry / Q25 | 左 Shift | `0` | 空格 | SYM（右 Alt） | 右 Shift |
| Titan2 | 左 Shift | Control | 空格 | Tab | 右 Alt |
| Titan2 Elite | 左 Shift | 返回 | 空格 | Fn | 右 Shift |
| Titan2 Elite（改键） | `0` | 返回 | 空格 | Ctrl | Fn |

> Titan2 Elite 底排两端的 Home / 多任务是系统键，IME 收不到，需先在系统「按键设置」里改为 `0` / `Ctrl` 才能用「改键」预设。候选多于 5 个时用候选栏的 `<` / `>` 按钮或各预设翻页键翻页，详见手册。

### Alt + 数字组合键选词

按住 Alt 或双击锁定 Alt 后，数字键 1~5 直接选第 N 个候选，空格上屏首选字。




### 物理键盘布局切换

1. 进入 **设置 → 物理键盘**
2. 选择 **键盘布局预设**，切换为对应的实体键盘布局

<img width="320" height="320" alt="image" src="https://github.com/user-attachments/assets/f090575d-3b74-4220-ba4b-e9cc09bebf3b" />

切换预设会按机型整批重播物理键位与推荐快捷键，自定义过的键位会被覆盖。

### 快捷输入法切换

| 快捷键 | 功能 |
| --- | --- |
| **Alt + 空格** | 在已启用的输入法间循环切换 |
| **Shift + 空格** | 弹出系统输入法选择器 |
| **Shift**（拼音下） | 切换中 / 英文 |
| **点击顶栏输入法图标** | 切到下一个输入法 |

> 顶栏实时显示当前输入法；`Alt + 空格` 可在「设置 → 物理键盘」里改或解绑。

### Alt 锁定

双击锁定键（默认左 Alt）锁定 Alt 状态，之后每次按键自动带 Alt；按 Alt / 空格 / 回车解锁。锁定键可在「设置 → 物理键盘 → Alt 行为」里改，也可关闭该功能；开启后还能用 `Alt + Del` 删除整行。

### 键盘飞字与快捷键自定义

自带触摸面的键盘（如 Titan2 Elite）可在「设置 → 物理键盘 → 飞字」开启手势：上滑选字、左右滑翻页、右上角起手左滑退格、无候选时四向滑移动光标、锁定 Alt 后滑动扩选；各手势有独立开关，另可调灵敏度与误触防护。

「设置 → 快捷键」可自定义编辑与开关动作（复制 / 剪切 / 粘贴 / 撤销、光标与扩选、开关特效 / 音效 / 状态栏等）。点任意一行弹出捕获窗口改键，`Fn + 字母` 这类组合需用窗口内按钮拼出；切换键盘预设时推荐键位会整批重播。

> 项目保持与原项目主线同步更新；设备按键映射或 UI 布局的调整需求，欢迎提交 [Issue](https://github.com/izilooong/TapFeet/issues) 或 Pull Request。

---

## 🎨 候选栏个性化

| 设置项 | 说明 |
| --- | --- |
| **候选栏排列模式** | 巨硬（4-2-1-3-5 居中展开）/ 普通（1-2-3-4-5 线性） |
| **候选栏序号** | 隐藏候选词前的序号 |
| **候选字字体大小** | 调节候选字显示大小 |
| **输入特效** | 选字上屏的粒子动画与连击计数，可关闭 |

---

## 📖 原项目功能概览

### 支持的语言及输入法

- 中文：拼音、双拼、五笔、仓颉、自定义码表（fcitx5-chinese-addons）；注音（Chewing）、粤拼（Jyutping）插件
- 英语（含拼写检查）、日语（Anthy）、韩语（Hangul）、越南语（UniKey）、泰语、僧伽罗语（Sayura）
- 通用输入引擎 RIME，可导入自定义方案；默认自带朙月拼音、地球拼音、注音、仓颉五代、速成五代、笔画 6 套方案

> 通过插件扩展输入引擎，启用即用；另内置剪贴板过滤等工具插件。

### 已实现功能

- 虚拟键盘、可展开候选词视图、剪贴板管理（纯文本）
- 主题系统（自定义配色、背景图、Android 12+ 动态取色）、按键弹出预览
- 长按字母 / 数字键输入键帽符号（黑莓风格）
- 符号 / 表情 / 颜文字窗口，按 QWERTY 键位选入，无需触屏
- 自定义词组从文本批量导入（`nh,1=你好`）、用户词典与拼音词库管理
- 本地语音输入（sherpa-onnx + SenseVoice，模型按需下载、离线识别）
- 按键音效方案（钢琴清音 / 电报嘀嗒 / 木鱼笃笃 / 算盘噼啪等，另有静音）
- 插件系统（从其他 APK 加载输入法插件）、在线升级

---

## 🔧 构建与运行

### 环境依赖

- Android SDK Platform 36、Build-Tools 36.1.0（compileSdk / targetSdk 36，minSdk 23）
- Android NDK 28.0.13004108 (side by side)、CMake 3.31.6
- Gradle 9.4.1、Android Gradle Plugin 9.2.0、Kotlin 2.3.21
- [KDE/extra-cmake-modules](https://github.com/KDE/extra-cmake-modules)
- GNU Gettext >= 0.20（需要 `msgfmt` 命令）

### Windows 用户前置步骤

<details>
<summary>点击展开 Windows 特定配置</summary>

- 开启 [Windows 开发者模式](https://learn.microsoft.com/en-us/windows/apps/get-started/enable-your-device-for-development)（允许创建符号链接）
- 为 Git 启用符号链接支持：
  ```shell
  git config --global core.symlinks true
  ```
</details>

### 克隆与子模块初始化

```shell
git clone https://github.com/izilooong/TapFeet
cd TapFeet
git submodule update --init --recursive
```

### 安装编译工具

```shell
# Arch Linux
sudo pacman -S extra-cmake-modules

# Debian/Ubuntu
sudo apt install extra-cmake-modules gettext

# macOS
brew install extra-cmake-modules gettext

# Windows (MSYS2 UCRT64 环境)
pacman -S mingw-w64-ucrt-x86_64-extra-cmake-modules mingw-w64-ucrt-x86_64-gettext
# 然后将 C:\msys64\ucrt64\bin 添加到 PATH
```

Android SDK 平台、Build-Tools、NDK 和 CMake 请通过 Android Studio 的 SDK Manager 安装（版本见 `build-logic/convention/src/main/kotlin/Versions.kt`）。

构建单 ABI 调试包：

```shell
./gradlew :app:assembleDebug -PbuildABI=arm64-v8a
```

---

## ❓ 常见问题

### Android Studio 索引耗时过长 / 内存占用高

在项目文件树中，右键 `lib/fcitx5/src/main/cpp/prebuilt` 目录 → Mark Directory as → Excluded，然后重启 IDE。

### Gradle 错误：No variants found for ':app' 或 [CXX1210] ... No compatible library found

检查是否设置了 `_JAVA_OPTIONS` 或 `JAVA_TOOL_OPTIONS` 环境变量，如有则清除（包括 Android Studio 启动脚本中的设置），某些 Gradle 插件会将 stderr 输出视为错误并中止构建。

### Q25 长按 Alt 触发系统级 Alt 锁定

Q25 上**长按物理 Alt** 会触发系统 / 固件层的 Alt 粘滞锁定，之后按字母键出的是符号。它发生在 IME 事件上游，TapFeet 无法检测或清除，也与自身的「双击左 Alt 锁定」（见上文 *Alt 锁定*）互不影响。

**解除**：再按一下空格键。

---

## 🌿 Nix 环境支持

开发环境中已包含合适的 Android SDK 与 NDK。在 Nix 环境下，gradlew 可直接使用。如需安装到手机，执行：

```shell
./gradlew installDebug
```

若使用 Android Studio，请将项目 SDK 路径指向 `$ANDROID_SDK_ROOT`。如 Android Studio 自动生成了错误的 `local.properties`，请手动将 `sdk.dir` 修正为正确的 SDK 路径。

---

## 📄 许可证

本项目继承原项目的 LGPL-2.1-or-later 许可证，详见根目录 LICENSE 文件。

- 修改核心库代码：修改部分须以相同许可证公开。
- 仅作动态链接使用（未修改库本身）：专有代码可保持闭源，但需声明使用了 LGPL 库并附许可证副本。

---

## 📄 隐私策略

输入与语音识别默认在设备本地完成，不收集个人信息。

应用声明了网络权限，仅用于在线升级检查、语音模型下载、云拼音等可选功能；关闭后日常输入无需联网。

---

## 🙏 致谢

基础能力源自 Fcitx5 官方团队与 fcitx5-android 项目，感谢开源输入法社区的贡献。

---

维护者：izilooong · 适配目标：Android 物理全键盘手机
