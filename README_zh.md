# TermuIDE

**Android 上的 IDE，由 Termux 提供工具运行环境。**

[English](README.md) · [下载](https://github.com/AM-RTS/TermuIDE/releases) · [使用指南（英文）](docs/usage.md) · [构建说明（英文）](docs/building.md)

TermuIDE 是 [TermuC](https://github.com/RainbowC0/TermuC) 的独立分支，由 AM-RTS 和贡献者维护，提供 C/C++ IDE 功能及基本 Python、Java 控制台运行支持。它不是 TermuC 或 Termux 的官方版本。最低系统版本为 **Android 8.1 / API 27**。

## 此分支新增或改进

- Material 3 Compose 界面、自适应文件浏览器、无障碍标签及可恢复的表单状态。
- Sora 编辑器，保留原有文档、撤销和词法分析工具。
- 未保存文档的持久恢复、后台保存和可选自动保存。
- Python pylsp 集成及可选 Java 补全/诊断。
- 顶部文件切换/关闭菜单、按需打开的诊断面板、底部撤销/重做/保存操作。
- 入口文件、参数和工作目录配置，以及更新后的 GDB 浮动控件。
- 应用内交互终端，支持 Ctrl+C、EOF 和强制停止；需要 Termux Python，仍可选择在 Termux 中运行。

实际应用截图见[英文首页](README.md#screenshots)。

## 开始使用

1. 安装本分支 APK 和 Termux（需要支持 RUN_COMMAND 返回结果的版本，0.109+）。
2. 授予存储、Termux 命令权限并启用 Termux 外部应用访问；GDB 浮动控件还需悬浮窗权限。
3. 打开 **⋮ → Language tools → Install / Repair**，在 Termux 中确认安装。Java 需要与应用匹配的已发布工具包；[手动安装说明](docs/usage.md#java)也可使用。
4. 在分析设置中选择语言服务器，打开源码后点击运行。

C/C++ 使用 clangd 和 GDB。Python 补全使用 pylsp，诊断和格式化取决于插件。Java 单文件运行需要完整 JDK 11+；补全/诊断另需 JDK 25 和 TermuIDE Java 工具包。Python/Java 调试、依赖管理、Maven/Gradle 项目导入及 Android APK 开发暂不支持。

项目配置为 `termuide.json`，不自动识别旧 `termuc.json`。已有项目继续使用其配置命令；选择 Python/Java 标签不会替换这些命令。

## 保存和恢复

未保存内容会保存在私有恢复草稿中，重启后恢复，不覆盖源文件。关闭脏标签会提供保存/丢弃/取消。自动保存默认关闭，可在编辑器设置中开启。

恢复不能保证最近一次检查点之前的输入。卸载或清除应用数据会删除私有草稿。新包名 `amrts.termuide` 不会导入旧应用的设置、权限或草稿；卸载旧应用前先保存或导出未保存内容。

## 致谢与许可证

感谢 TermuC（RainbowC0 及贡献者）、CodeEditor（MrIkso 及贡献者）、TextWarrior（Tah Wei Hoon 及贡献者）、Sora（Rosemoe 及贡献者）、AndroidX、Compose Material 3、Kotlin、Termux 和 Java 语言服务器社区。

应用保留 [GNU GPL v3](LICENSE.md)；Sora 使用 LGPL 2.1 或更新版本，带有原始声明的 TextWarrior 文件以及 AndroidX/Kotlin 使用 Apache 2.0。详见 [NOTICE.md](NOTICE.md)。完整许可证也可在应用“设置 → 关于 → 关于 TermuIDE”中离线阅读。分发时须保留声明并提供对应版本源码与构建说明。软件不提供担保。
