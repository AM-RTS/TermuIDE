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
- 点击顶部文件名可切换或关闭已打开文件；未保存的修改仍使用保存/丢弃/取消提示。底部提供诊断、撤销、重做和保存按钮；诊断面板只在点击后打开，显示各文件的消息、诊断代码和当前执行状态/退出码，点击消息可跳转到对应位置。行号区域的留白不会修改源文件缩进。

Python/Java 提供基本控制台运行功能。Python 自动补全使用单独安装的 pylsp，Java 语言服务器尚未选定；Python/Java 调试器、依赖管理和 Maven/Gradle 集成仍未实现。C/C++ 继续使用 clangd 和 GDB。

Python 自动补全：在 Termux 中运行 `pkg install python` 和 `python -m pip install python-lsp-server`，确认 `pylsp --help` 可用；在 TermuIDE 的分析设置中选择语言服务器并打开 `.py` 文件。安装后可关闭 Python 标签页并重新打开以重试，或重新应用语言服务器设置。不同语言/文件夹或项目使用独立会话和端口，同一范围的标签页共享服务器。诊断、格式化等功能取决于安装的 pylsp 插件；暂不提供虚拟环境选择。

未保存或恢复的文本直接同步给服务器，不会为了补全写入源文件。补全修改支持撤销和原有持久恢复；服务器不可用时仍可编辑和恢复。GitHub 预发行流程使用真实 pylsp 检查兄弟模块补全，但不代表已验证手机或 Termux 传输。

## 安装与项目

最低系统版本为 Android 8.1（API 27），目标 API 为 34；构建需要 JDK 17。请使用[本分支发布页](https://github.com/AM-RTS/TermuIDE/releases)中的 APK 或从源码构建。上游 F-Droid 软件包仍是 TermuC。

新包名会单独安装，不会自动导入旧 TermuC/测试版的私有设置、权限、运行配置和恢复草稿。卸载旧应用前应先保存或导出未保存内容，并重新授予 TermuIDE 存储、Termux 命令和悬浮窗权限。

项目配置文件改为 **`termuide.json`**，不再自动识别 `termuc.json`。旧项目可手动重命名配置文件并检查命令和路径，或重新创建项目。

新建项目需要使用尚不存在的目标文件夹，以免模板覆盖已有文件。创建失败会保留当前项目和未保存文档；存储错误可能留下不完整的新文件夹，请先检查其内容，再使用新名称重试。

## 致谢与许可证

本应用保留 [GNU GPL v3](LICENSE.md) 许可证。感谢 TermuC（RainbowC0 及贡献者）、CodeEditor（MrIkso 及贡献者）、TextWarrior（Tah Wei Hoon 及贡献者）、Sora（Rosemoe 及贡献者）、AndroidX、Material、Kotlin 和 Termux 社区。Sora 使用 LGPL 2.1 或更新版本；带有原始声明的 TextWarrior 文件以及 AndroidX/Kotlin 使用 Apache 2.0。各组件保留其原始版权和许可证声明；详见 [NOTICE.md](NOTICE.md)。软件不提供担保。

更名后的 TermuIDE、关于/许可证界面和工作区重构已通过 [GitHub Actions 签名发布构建](https://github.com/AM-RTS/TermuIDE/actions/runs/40169775316)。预发行流程现在要求 JVM/Robolectric 回归测试通过，失败会阻止构建、签名和发布，并保留已生成的测试报告。覆盖工作区选择、恢复后的关闭提示、保存/丢弃、持久草稿和项目创建。本地 Gradle/构建/测试、手机和模拟器检查仍跳过；主机测试不能代替真机验证。每次推送到 master 会在检查成功后发布带有签名 APK、校验值和变更记录的预发行版，详见 [自动发布说明](README.md#automated-prereleases)。
