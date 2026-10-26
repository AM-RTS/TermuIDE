# TermuIDE acknowledgements and licensing

TermuIDE (`amrts.termuide`) is an independent, modified fork maintained by AM-RTS and contributors. Fork modifications are copyright (c) 2026 AM-RTS and contributors. Original copyright notices remain with their respective owners. This is not an official TermuC, CodeEditor, Sora or Termux release.

## Application and inherited editor code

- **TermuC — RainbowC0 and contributors**: the upstream IDE, project/Termux integration and C/C++ tooling. Source: https://github.com/RainbowC0/TermuC . GNU General Public License version 3; the unchanged full application license is in [LICENSE.md](LICENSE.md).
- **CodeEditor — MrIkso and contributors**: the inherited editor/document/language foundation. Source: https://github.com/MrIkso/CodeEditor . The upstream repository carries GPL version 3. Individual source files retain their original notices, including the Apache-licensed TextWarrior code below; those notices are not replaced by this acknowledgement.
- **TextWarrior — Tah Wei Hoon and contributors**: document, undo and language/editor utilities. Retained source notices include Copyright (c) 2011 Tah Wei Hoon and Copyright (c) 2013 Tah Wei Hoon. Apache License version 2.0. See the headers in `codeeditor/src/main/java/cn/rbc/codeeditor/`. The fork modifies parts of these utilities and preserves the author notices.

## Bundled libraries

- **Sora editor 0.24.4 — Rosemoe and contributors**: editor rendering and input. Copyright (C) 2020-2024 Rosemoe. GNU Lesser General Public License version 2.1 or, at your option, any later version. Pinned source: https://github.com/Rosemoe/sora-editor/tree/0.24.4 . License: https://github.com/Rosemoe/sora-editor/blob/0.24.4/LICENSE . Used as the published dependency; the fork supplies its own integration instead of a modified Sora library.
- **AndroidX, Jetpack Compose and Material Components — Android Open Source Project and contributors**: application lifecycle and Material 3 interface. Apache License 2.0. Source: https://android.googlesource.com/platform/frameworks/support/ .
- **Kotlin and kotlinx.coroutines — JetBrains and contributors**: language/runtime and observable state. Apache License 2.0. Sources: https://github.com/JetBrains/kotlin and https://github.com/Kotlin/kotlinx.coroutines .

Full GPL v3, LGPL v2.1 and Apache 2.0 texts are bundled in `app/src/main/assets/licenses/` and readable offline through **Settings > About > About TermuIDE**. Existing component copyright and license notices remain applicable.

## External tools

Thanks to the Termux community and the maintainers of clang/clangd, GDB, Python and OpenJDK. Termux and these tools are installed separately; their own licenses apply. They are not bundled with the TermuIDE APK. Termux source: https://github.com/termux/termux-app .

Python autocomplete uses the separately installed **Python LSP Server (pylsp)**, maintained by Python Language Server Contributors and distributed under the MIT license. Source and license: https://github.com/python-lsp/python-lsp-server . It and its providers are not bundled in the APK.

## Fork changes and source

The changes are described in [README.md](README.md#what-is-different-in-this-fork) and [the architecture notes](docs/architecture/multilanguage-runtime.md). Source for this fork remains at https://github.com/AM-RTS/TermuIDE ; the repository name does not determine the Android app name. Preserve these notices and the applicable license texts when redistributing. Publish the corresponding source and build instructions for the exact binary release, including any modifications; a link to a different upstream revision is not a substitute.

TermuIDE is free software under GNU GPL v3. You may copy, modify and redistribute it under that license. It is provided WITHOUT ANY WARRANTY, including the implied warranties of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the full license texts for terms.
