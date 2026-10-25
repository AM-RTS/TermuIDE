# TermuIDE

[English README](README.md) · [许可证](LICENSE.md) · [致谢](NOTICE.md)

TermuIDE 是由 AM-RTS 和贡献者维护的独立分支，基于 RainbowC0 和贡献者开发的 [TermuC](https://github.com/RainbowC0/TermuC)。它通过 Termux 提供 C/C++ IDE 功能，并加入基本的 Python 3、Java 控制台运行支持。它不是 TermuC 或 Termux 的官方版本。

## 此分支的变化

- 应用名为 **TermuIDE**，包名为 **`amrts.termuide`**。
- 使用 Material 3 和 Jetpack Compose 界面、自适应文件浏览器、无障碍标签及可恢复的表单/对话框状态。
- 使用 Sora 0.24.4 编辑器，复用原有文档、撤销、词法分析和 clangd 集成。
- 增加未保存文档的持久恢复、后台保存、修订检查、可选自动保存以及中断保护。
- 共享运行配置支持入口文件、逐项传递的程序参数及工作目录。
- 简化项目/标签页协调，并修复 GDB 浮动控件及调试信息更新问题。
- 在“设置 → 关于 → 关于 TermuIDE”中提供致谢、源码链接与离线许可证全文。

Python/Java 目前仅提供基本运行功能，不包含语言服务器、调试器、依赖管理或 Maven/Gradle 集成。C/C++ 继续使用 clangd 和 GDB。

## 安装与项目

最低系统版本为 Android 8.1（API 27），目标 API 为 34；构建需要 JDK 17。请使用[本分支发布页](https://github.com/AM-RTS/TermuC/releases)中的 APK 或从源码构建。上游 F-Droid 软件包仍是 TermuC。

新包名会单独安装，不会自动导入旧 TermuC/测试版的私有设置、权限、运行配置和恢复草稿。卸载旧应用前应先保存或导出未保存内容，并重新授予 TermuIDE 存储、Termux 命令和悬浮窗权限。

项目配置文件改为 **`termuide.json`**，不再自动识别 `termuc.json`。旧项目可手动重命名配置文件并检查命令和路径，或重新创建项目。

新建项目需要使用尚不存在的目标文件夹，以免模板覆盖已有文件。创建失败会保留当前项目和未保存文档；存储错误可能留下不完整的新文件夹，请先检查其内容，再使用新名称重试。

## 致谢与许可证

本应用保留 [GNU GPL v3](LICENSE.md) 许可证。感谢 TermuC（RainbowC0 及贡献者）、CodeEditor（MrIkso 及贡献者）、TextWarrior（Tah Wei Hoon 及贡献者）、Sora（Rosemoe 及贡献者）、AndroidX、Material、Kotlin 和 Termux 社区。Sora 使用 LGPL 2.1 或更新版本；带有原始声明的 TextWarrior 文件以及 AndroidX/Kotlin 使用 Apache 2.0。各组件保留其原始版权和许可证声明；详见 [NOTICE.md](NOTICE.md)。软件不提供担保。

更名后的 TermuIDE、关于/许可证界面和工作区重构已通过 [GitHub Actions 签名发布构建](https://github.com/AM-RTS/TermuC/actions/runs/40168892630)。本地 Gradle/构建以及所有测试和设备检查仍未运行；构建和签名成功不代表应用行为已验证。每次推送到 master 会自动发布带有签名 APK、校验值和变更记录的预发行版，详见 [自动发布说明](README.md#automated-prereleases)。
