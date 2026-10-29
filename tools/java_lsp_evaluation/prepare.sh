#!/usr/bin/env bash
set -euo pipefail
scripts=$(cd -- "$(dirname -- "$0")" && pwd)
root=$1
mkdir -p "$root"
cd "$root"
root=$PWD

# Pin source and binaries; never run upstream scripts that download desktop JDKs.
revision=$(tr -d '\r\n' < "$scripts/../java_lsp/revision")
repository=AM-RTS/java-language-server
bash "$scripts/../java_lsp/build.sh" "$root/java-server"
cp java-server/build.log build.log
mkdir george
cp java-server/termuide-java-lsp/lib/*.jar george/

archive=jdt-language-server-1.63.0-202610191322.tar.gz
curl -fL --retry 3 "https://download.eclipse.org/jdtls/milestones/1.63.0/$archive" -o jdtls.tar.gz
printf '%s  %s\n' b1f2abe0b3cccb462cf64fb4a5587c7d09df60cf8be7748b152cbd89d018bdc4 jdtls.tar.gz | sha256sum --check
mkdir jdtls
tar -xzf jdtls.tar.gz -C jdtls
# Compare the shipped JDT distribution with the server-only George distribution.
tar -czf george-server.tar.gz -C george .
python3 - "$root" "$revision" "$repository" <<'PY'
import hashlib, json, os, pathlib, subprocess, sys
root = pathlib.Path(sys.argv[1])
def size(folder):
    return sum(p.stat().st_size for p in folder.rglob('*') if p.is_file())
report = {'george_revision': sys.argv[2], 'george_repository': sys.argv[3], 'jdtls_version': '1.63.0',
          'java_version': subprocess.run(['java', '-version'], capture_output=True, text=True, check=True).stderr,
          'george_tests': 'not run in this job; server packaging only; fork regressions run in its own CI',
          'jdk_excluded_from_server_sizes': True,
          'host_full_jdk_file_bytes': size(pathlib.Path(os.environ['JAVA_HOME'])),
          'servers': {}}
for name, archive in [('george', 'george-server.tar.gz'), ('jdtls', 'jdtls.tar.gz')]:
    report['servers'][name] = {'installed_file_bytes': size(root/name),
        'archive_bytes': (root/archive).stat().st_size,
        'archive_sha256': hashlib.sha256((root/archive).read_bytes()).hexdigest()}
(root/'preparation.json').write_text(json.dumps(report, indent=2))
print(json.dumps(report, indent=2))
PY
