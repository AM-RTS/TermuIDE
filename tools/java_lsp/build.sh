#!/usr/bin/env bash
set -euo pipefail
scripts=$(cd -- "$(dirname -- "$0")" && pwd)
revision=$(tr -d '\r\n' < "$scripts/revision")
[[ $revision =~ ^[0-9a-f]{40}$ ]] || { echo 'Invalid Java server revision' >&2; exit 1; }
mkdir -p "$1"
root=$(cd -- "$1" && pwd)
cd "$root"
curl -fL --retry 3 "https://codeload.github.com/AM-RTS/java-language-server/tar.gz/$revision" -o source.tar.gz
mkdir source
tar -xzf source.tar.gz -C source --strip-components=1
if ! (cd source && mvn -B -Dmaven.test.skip=true package) >build.log 2>&1; then
  tail -n 80 build.log
  exit 1
fi
mkdir -p termuide-java-lsp/{bin,lib}
cp source/dist/classpath/*.jar termuide-java-lsp/lib/
install -m 755 "$scripts/termuide-java-lsp" termuide-java-lsp/bin/
cp source/LICENSE.md termuide-java-lsp/LICENSE.md
cp "$scripts/../../LICENSE.md" termuide-java-lsp/LICENSE-TermuIDE-tools.txt
cp source/src/main/java/org/javacs/ReusableCompiler.java termuide-java-lsp/NOTICE-ReusableCompiler.java
cp "$JAVA_HOME/legal/java.base/LICENSE" termuide-java-lsp/GPL-2.0-with-Classpath-exception.txt
printf 'George Fraser Java language server, TermuIDE fork\nSource: https://github.com/AM-RTS/java-language-server/tree/%s\nBuilt using Maven; JDK 25 required, installed separately.\nDependency versions and notices: source pom.xml and META-INF in the included JARs.\nReusableCompiler retains its Oracle GPLv2 with Classpath Exception notice in the linked source.\n' "$revision" > termuide-java-lsp/SOURCE.txt
tools_revision=$(git -C "$scripts" rev-parse HEAD)
printf 'TermuIDE launcher and packaging: GPLv3, see LICENSE-TermuIDE-tools.txt.\nTools source: https://github.com/AM-RTS/TermuIDE/tree/%s/tools/java_lsp\n' "$tools_revision" >> termuide-java-lsp/SOURCE.txt
tar -czf termuide-java-lsp.tar.gz termuide-java-lsp
sha256sum termuide-java-lsp.tar.gz > termuide-java-lsp.tar.gz.sha256
cp source.tar.gz termuide-java-lsp-sources.tar.gz
sha256sum termuide-java-lsp-sources.tar.gz > termuide-java-lsp-sources.tar.gz.sha256
