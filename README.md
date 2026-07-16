
<img width="280" height="280" alt="快乐的大脚" src="https://github.com/user-attachments/assets/0af62fcc-6b11-4ec5-ae36-88fee7eb495b" />

# TapFeet IME · 大脚输入法


> 本项目基于 [fcitx5-android](https://github.com/fcitx5-android/fcitx5-android) 二次开发，**专为 Android 物理全键盘手机打造的中文输入法**。  
 
> 原项目将 [Fcitx5](https://github.com/fcitx/fcitx5) 输入法框架及各类引擎移植到 Android 平台，本仓库在此基础上针对实体键盘输入场景做了深度优化。
> 诞生于 Q25（BlackBerry Classic Q20 复刻机）的适配需求，现已扩展支持 **Unihertz Titan 2**、**BlackBerry KEY 系列** 等主流物理键盘 Android 设备。 

---

## 📲 下载与安装

>  [下载](https://github.com/izilooong/TapFeet/releases/tag/v1.0.2-01)

---

## 📱 适配设备

| 设备系列 | 代表型号 | 屏幕 | 适配状态 |
| --- | --- | --- | --- |
| **Q25** | Q25 | ✅ 深度适配 |
| **Unihertz Titan 系列** | Titan2 | ✅ 已适配 |
| **BlackBerry KEY 系列** |  | ✅ 已适配 |

> 理论上支持所有搭载实体 QWERTY 键盘的 Android 设备。以上为已实测型号，其他设备欢迎 [提交反馈](https://github.com/izilooong/TapFeet/issues)。

---

## ⌨️ 物理键盘专项适配

以下特性在所有支持的物理键盘设备上均可使用，部分特性源于 Q25 的深度定制需求。


### 物理键盘选字

物理键盘底排功能键直接映射候选词位置，无需触摸屏幕即可完成选词。

- **Q25 / BlackBerry KEY **：5 大金刚键（⬆️ 0️⃣ 🈳 sym ⬆️）对应候选词 1~5
- **Unihertz Titan 2 **：顶部导航键 + 空格键快捷选词

<img width="935" height="191" alt="image" src="https://github.com/user-attachments/assets/a27f825e-9000-4deb-adbc-e7a055097ab6" />


### Alt + 数字组合键选词

还原 BlackBerry 经典操作逻辑 —— 按住 Alt 或双击锁定 Alt，物理数字键（1~5）直接选词，空格键快速上屏。

<img width="935" height="191" alt="image" src="https://github.com/user-attachments/assets/666baa7e-f821-4529-9350-f10106256b1f" />


### 物理键盘布局切换

使用物理键盘前，需在设置中手动启用：

1. 进入 **选项 → 物理键盘**
2. 选择 **键盘布局预设**，切换为对应的实体键盘布局

启用后，输入法将在物理键盘输入时自动隐藏虚拟键盘并显示悬浮候选窗，触摸屏幕时恢复虚拟键盘模式。


### 物理键盘精确映射

完全适配各设备的物理键盘扫描码，包括 QWERTY 字母区、顶部数字/符号行、功能键区，与 Fcitx5 引擎深度联动，物理按键响应无延迟。



> **项目保持与原项目主线同步更新**，如有特定设备按键映射或 UI 布局的进一步调整需求，欢迎提交 [Issue](https://github.com/izilooong/TapFeet/issues) 或 Pull Request。

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




## 🔧 构建与运行

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

Android SDK 平台、Build-Tools、NDK 和 CMake 请通过 Android Studio 的 SDK Manager 安装（版本号请参考 Versions.kt）。

---

## ❓ 常见问题

### Android Studio 索引耗时过长 / 内存占用高

在项目文件树中，右键 `lib/fcitx5/src/main/cpp/prebuilt` 目录 → Mark Directory as → Excluded，然后重启 IDE。

### Gradle 错误：No variants found for ':app' 或 [CXX1210] ... No compatible library found

检查是否设置了 `_JAVA_OPTIONS` 或 `JAVA_TOOL_OPTIONS` 环境变量，如有则清除（包括 Android Studio 启动脚本中的设置），某些 Gradle 插件会将 stderr 输出视为错误并中止构建。

---

## 🌿 Nix 环境支持

开发环境中已包含合适的 Android SDK 与 NDK。在 Nix 环境下，gradlew 可直接使用。如需安装到手机，执行：

```shell
./gradlew installDebug
```

若使用 Android Studio，请将项目 SDK 路径指向 `$ANDROID_SDK_ROOT`。如 Android Studio 自动生成了错误的 `local.properties`，请手动将 `sdk.dir` 修正为正确的 SDK 路径。

---

## 📄 许可证

本项目继承原项目的 LGPL-2.1 许可证，详见根目录下的 LICENSE 文件。

根据 LGPL-2.1 的要求：

- 若对本项目核心库代码进行了修改，修改部分必须以相同的 LGPL-2.1 许可证公开。
- 若仅将本项目作为动态链接库使用（未修改库本身），您的专有代码可保持闭源，但需在文档中声明使用了 LGPL 库，并附上许可证副本。

---

## 📄 隐私策略

大脚输入法不要求联网权限，也不收集任何个人信息。

---

## 🙏 致谢

本项目的所有基础能力均源自 Fcitx5 官方团队的卓越工作。感谢他们为开源输入法社区所做的贡献。

---

维护者：izilooong · 适配目标：Android 物理全键盘手机
