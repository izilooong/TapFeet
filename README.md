
# Fcitx5 · Q25 全键盘适配版（BlackBerry Classic 硬件复刻）

[![build status](https://img.shields.io/jenkins/build.svg?jobUrl=https://jenkins.fcitx-im.org/job/android/job/fcitx5-android/)]

> 本项目基于 [fcitx5-android](https://github.com/fcitx5-android/fcitx5-android) 二次开发，**核心目标是为 Q25 全键盘手机提供极致的中文输入体验**。  
> Q25 是一款延续了经典 **BlackBerry Classic（Q20）** 硬件设计的 Android 设备，具备 1:1 方形屏幕、物理 QWERTY 全键盘及标志性的实体按键布局。  
> 原项目将 [Fcitx5](https://github.com/fcitx/fcitx5) 输入法框架及各类引擎移植到 Android 平台，功能强大且高度可扩展。

---

## 📲 下载与安装

> **本适配版**暂未提供预编译 APK，如需在 Q25 上使用，请参考下方的“构建与运行”章节自行编译。

---

## ⌨️ 针对 Q25（BlackBerry Classic）的专项适配

本仓库针对 **Q25 设备（硬件对标 BlackBerry Classic Q20）** 的独特硬件特性，做了以下定向优化：

<img width="300" height="500" alt="blackberry-dark" src="https://github.com/user-attachments/assets/ba82d97a-733b-46a5-be4e-aba617ea7a60" />
<img width="300" height="500" alt="blackberry-light" src="https://github.com/user-attachments/assets/34ba9746-a1bb-4cae-8e4f-d27056b0e91d" />


### 物理键盘选字

适配物理键盘5大金刚键(⬆️ 0️⃣ 🈳 sym ⬆️)选字，提升输入效率。

<img width="935" height="191" alt="image" src="https://github.com/user-attachments/assets/a27f825e-9000-4deb-adbc-e7a055097ab6" />


### 组合键选字

深度适配 Q25 的 **Alt + 数字 组合键** 逻辑，物理数字键直接选词（1~5 对应候选词位置）、物理空格键快速上屏等高频操作，还原 BlackBerry 经典输入手感。

<img width="935" height="191" alt="image" src="https://github.com/user-attachments/assets/666baa7e-f821-4529-9350-f10106256b1f" />


### 硬件形态对标 Classic (Q20)

针对 Q25 继承自 Q20 的 **1:1 方形屏幕（720×720）** 及 **物理 QWERTY 全键盘** 布局，对输入法界面比例、候选词条高度和按键触控区域进行精确调校，避免画面拉伸或误触。

### 物理键盘映射（Classic 键位复刻）

完全适配 Q25 的物理键盘扫描码，包括经典的 QWERTY 字母区、顶部的数字/符号行，确保与 Fcitx5 引擎深度联动，物理按键响应无延迟。

### 方形屏幕 UI 优化

针对 1:1 非标准屏幕比例，优化候选栏在输入状态下的显示逻辑。当物理键盘弹出时，自动调整悬浮候选窗的位置和字体大小，防止遮挡应用核心内容区域。


### 性能与续航优化

针对 Q25 的硬件配置（CPU/内存），精简非核心过渡动画，减少后台词库同步频率，确保输入响应流畅且省电。

> **后续适配工作将保持与原项目主线同步更新**，如有 Q25 特定按键映射或 UI 布局的进一步调整，欢迎提交 Issue 或 Pull Request。

---

## 📖 原项目功能概览

### 支持的语言及输入法

- 英语（含拼写检查）
- 中文：拼音、双拼、五笔、仓颉、自定义码表（基于 fcitx5-chinese-addons）
  - 注音（通过 Chewing 插件）
  - 粤拼（通过 Jyutping 插件，基于 libime-jyutping）
- 越南语（通过 UniKey 插件，支持 Telex、VNI、VIQR）
- 日语（通过 Anthy 插件）
- 韩语（通过 Hangul 插件）
- 僧伽罗语（通过 Sayura 插件）
- 泰语（通过 Thai 插件）
- 通用输入法（通过 RIME 插件，支持导入自定义方案）

### 已实现功能

- 虚拟键盘（布局暂不支持自定义）
- 可展开的候选词视图
- 剪贴板管理（仅支持纯文本）
- 主题系统（自定义配色、背景图片、Android 12+ 动态取色）
- 按键弹出预览
- 长按弹出符号快捷输入
- 符号与 Emoji 选择器
- 插件系统（支持从其他 APK 加载输入法插件）
- 物理键盘连接时显示悬浮候选面板

### 计划中的功能

- 自定义键盘布局
- 更多输入法插件接入

---

## 🖼️ 界面预览

| 拼音 · Material Light 主题（按键边框开启） | 自然码双拼 · Pixel Dark 主题（按键边框关闭） |
| :-: | :-: |
| <img src="https://github.com/fcitx5-android/fcitx5-android/assets/13914967/bd429247-62d9-4c78-bab8-70ef3ce47588" width="300px"> | <img src="https://github.com/fcitx5-android/fcitx5-android/assets/13914967/3ae969c1-7ed0-4f92-a5df-19dc8c90a8c3" width="300px"> |

| Emoji 选择器 · Pixel Light 主题（按键边框开启） | 符号选择器 · Material Dark 主题（按键边框关闭） |
| :-: | :-: |
| <img src="https://user-images.githubusercontent.com/13914967/202181845-6a5f6bb2-a877-468c-851a-fd7e66e64ed4.png" width="300px"> | <img src="https://user-images.githubusercontent.com/13914967/202181861-dd253439-1d5e-4f5f-9535-934f28796a6b.png" width="300px"> |

---

## 🔧 构建与运行（适配 Q25 设备）

### 环境依赖

- Android SDK Platform & Build-Tools 35
- Android NDK (Side by side) 25 & CMake 3.22.1（可通过 Android Studio SDK Manager 或 sdkmanager 安装）
- [KDE/extra-cmake-modules](https://github.com/KDE/extra-cmake-modules)
- GNU Gettext >= 0.20（需要 `msgfmt` 命令）

### Windows 用户前置步骤

<details>
<summary>点击展开 Windows 特定配置</summary>

- 开启 [Windows 开发者模式](https://learn.microsoft.com/en-us/windows/apps/get-started/enable-your-device-for-development)（允许创建符号链接）
- 为 Git 启用符号链接支持：
  ```shell
  git config --global core.symlinks true
</details>
克隆与子模块初始化
shell
git clone https://github.com/izilooong/fcitx5-Q25.git
cd fcitx5-Q25
git submodule update --init --recursive
安装编译工具
shell
# Arch Linux
sudo pacman -S extra-cmake-modules

# Debian/Ubuntu
sudo apt install extra-cmake-modules gettext

# macOS
brew install extra-cmake-modules gettext

# Windows (MSYS2 UCRT64 环境)
pacman -S mingw-w64-ucrt-x86_64-extra-cmake-modules mingw-w64-ucrt-x86_64-gettext
# 然后将 C:\msys64\ucrt64\bin 添加到 PATH
Android SDK 平台、Build-Tools、NDK 和 CMake 请通过 Android Studio 的 SDK Manager 安装（版本号请参考 Versions.kt）。

# 常见问题
Android Studio 索引耗时过长 / 内存占用高
在项目文件树中，右键 lib/fcitx5/src/main/cpp/prebuilt 目录 → Mark Directory as → Excluded，然后重启 IDE。

Gradle 错误：No variants found for ':app' 或 [CXX1210] ... No compatible library found
检查是否设置了 _JAVA_OPTIONS 或 JAVA_TOOL_OPTIONS 环境变量，如有则清除（包括 Android Studio 启动脚本中的设置），某些 Gradle 插件会将 stderr 输出视为错误并中止构建。

# 🌿 Nix 环境支持
开发环境中已包含合适的 Android SDK 与 NDK。在 Nix 环境下，gradlew 可直接使用。如需安装到手机，执行：

shell
./gradlew installDebug
若使用 Android Studio，请将项目 SDK 路径指向 $ANDROID_SDK_ROOT。如 Android Studio 自动生成了错误的 local.properties，请手动将 sdk.dir 修正为正确的 SDK 路径。

# 🤝 参与贡献 / 社区
Trello 看板：https://trello.com/b/gftk6ZdV/kanban

Matrix 频道：https://matrix.to/#/#fcitx5-android:mozilla.org

Telegram 讨论组：@fcitx5_android_group（原 @fcitx5_android）

欢迎提交 Issue 或 Pull Request，尤其是针对 Q25 或其他全键盘设备的适配改进。

# 📄 许可证
本项目继承原项目的 LGPL-2.1 许可证，详见根目录下的 LICENSE 文件。

根据 LGPL-2.1 的要求：

若对本项目核心库代码进行了修改，修改部分必须以相同的 LGPL-2.1 许可证公开。

若仅将本项目作为动态链接库使用（未修改库本身），您的专有代码可保持闭源，但需在文档中声明使用了 LGPL 库，并附上许可证副本。


# 🙏 致谢
本项目的所有基础能力均源自 Fcitx5 官方团队 的卓越工作。感谢他们为开源输入法社区所做的贡献。

---

维护者：izilooong · 适配目标：Q25 全键盘手机（BlackBerry Classic）
