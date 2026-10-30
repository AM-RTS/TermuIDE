set -euo pipefail
server=$1
environment=$2
revision=$3
java_installer=$4
export PATH="$PREFIX/bin:/system/bin"
unset PYTHONPATH PYTHONHOME
mkdir -p "$HOME/.cache"
command -v flock >/dev/null || { echo 'Termux util-linux is missing. Run pkg install util-linux, then retry.' >&2; exit 127; }
exec 9>"$HOME/.cache/termuide-language-tools.lock"
flock -n 9 || { echo 'Another language-tools setup is running in Termux.' >&2; exit 1; }

previous_java=''
finish() {
  code=$?
  trap - EXIT
  if [ -n "$previous_java" ]; then
    update-alternatives --set java "$previous_java" || code=1
  fi
  if [ "$code" -eq 0 ]; then
    printf '\nLanguage tools installed. Return to TermuIDE to check their status.\n'
  else
    printf '\nSetup failed (exit %s). Read the messages above, then return to TermuIDE to retry.\n' "$code" >&2
  fi
  exit "$code"
}
trap finish EXIT

case "$server" in
  clangd)
    apt-get update
    apt-get install --no-install-recommends --no-upgrade --reinstall clang gdb make
    command -v clangd >/dev/null
    ;;
  pylsp)
    if [ -n "$environment" ]; then
      python="$environment/bin/python"
      [ -x "$python" ] || { echo 'The selected Python environment is missing. Create it or use the default environment.' >&2; exit 127; }
    else
      apt-get update
      apt-get install --no-install-recommends --no-upgrade python python-pip
      python="$PREFIX/bin/python"
    fi
    # pip dependencies such as ujson may need a native build on Android.
    if ! command -v clang >/dev/null; then
      apt-get update
      apt-get install --no-install-recommends --no-upgrade clang
    fi
    cd "$HOME"
    "$python" -I -m pip install --disable-pip-version-check --upgrade --force-reinstall 'python-lsp-server==1.15.0'
    "$python" -I -c 'import pylsp'
    ;;
  java)
    jdk="$PREFIX/lib/jvm/java-25-openjdk/bin/java"
    if [ ! -x "$jdk" ]; then
      if [ -f "$PREFIX/var/lib/dpkg/alternatives/java-profile" ]; then
        echo 'This older Termux Java installation uses separate alternatives. Update your existing OpenJDK package in Termux before installing JDK 25, then retry. Your current Java selection was not changed.' >&2
        exit 1
      fi
      if command -v java >/dev/null; then
        previous_java=$(readlink -f "$(command -v java)")
        update-alternatives --query java | grep -Fx "Alternative: $previous_java" >/dev/null || {
          previous_java=''
          echo 'Your Java command is not registered with Termux alternatives. Install JDK 25 manually to preserve this custom runtime.' >&2
          exit 1
        }
      fi
    fi
    apt-get update
    packages=(python)
    [ -x "$jdk" ] || packages+=(openjdk-25)
    apt-get install --no-install-recommends --no-upgrade "${packages[@]}"
    cd "$HOME"
    "$PREFIX/bin/python" -I -c "$java_installer" "$revision"
    "$PREFIX/bin/termuide-java-lsp" --check
    ;;
  *) echo 'Unsupported language tools.' >&2; exit 64 ;;
esac
