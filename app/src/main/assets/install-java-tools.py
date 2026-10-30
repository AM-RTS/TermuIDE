"""Download a matching published TermuIDE server; never replace a working install on failure."""
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import subprocess
import sys
import tarfile
import tempfile
import urllib.request
import uuid

RELEASES = 'https://api.github.com/repos/AM-RTS/TermuIDE/releases?per_page=20'
DOWNLOAD_PREFIX = 'https://github.com/AM-RTS/TermuIDE/releases/download/'


def fetch(url, destination, limit):
    request = urllib.request.Request(url, headers={'User-Agent': 'TermuIDE-language-tools', 'Accept': 'application/vnd.github+json'})
    total = 0
    with urllib.request.urlopen(request, timeout=60) as response, destination.open('wb') as output:
        if not response.geturl().startswith('https://'):
            raise ValueError('Insecure download redirect')
        while chunk := response.read(65536):
            total += len(chunk)
            if total > limit:
                raise ValueError('Download exceeded its size limit')
            output.write(chunk)


def unpack(archive, directory, revision):
    seen, total = set(), 0
    with tarfile.open(archive, mode='r|gz') as bundle:
        for member in bundle:
            path = PurePosixPath(member.name)
            if (path.is_absolute() or '..' in path.parts or not path.parts or
                    path.parts[0] != 'termuide-java-lsp' or member.name in seen or
                    not (member.isdir() or member.isfile())):
                raise ValueError('Unsafe Java tools archive entry')
            seen.add(member.name)
            total += member.size
            if len(seen) > 512 or total > 64 * 1024 * 1024:
                raise ValueError('Java tools archive exceeded its extraction limit')
            target = directory.joinpath(*path.parts)
            if member.isdir():
                target.mkdir(parents=True, exist_ok=True)
            else:
                target.parent.mkdir(parents=True, exist_ok=True)
                with bundle.extractfile(member) as source, target.open('xb') as output:
                    shutil.copyfileobj(source, output)
                target.chmod(0o644)
    root = directory / 'termuide-java-lsp'
    source = (root / 'SOURCE.txt').read_text()
    if f'Source: https://github.com/AM-RTS/java-language-server/tree/{revision}\n' not in source:
        return None
    launcher = root / 'bin/termuide-java-lsp'
    if not launcher.is_file() or not (root / 'lib/java-language-server.jar').is_file():
        raise ValueError('Incomplete Java tools archive')
    launcher.chmod(0o755)
    return launcher


def install(revision, home, prefix):
    if not re.fullmatch('[0-9a-f]{40}', revision):
        raise ValueError('Invalid pinned Java server revision')
    share = home / '.local/share'
    share.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='.termuide-java-download-', dir=share) as scratch:
        scratch = Path(scratch)
        listing = scratch / 'releases.json'
        fetch(RELEASES, listing, 2 * 1024 * 1024)
        releases = json.loads(listing.read_text())
        if not isinstance(releases, list):
            raise ValueError('Invalid GitHub release listing')
        for release in releases:
            if release.get('draft'):
                continue
            asset = next((asset for asset in release.get('assets', []) if asset.get('name') == 'termuide-java-lsp.tar.gz'), None)
            if asset is None:
                continue
            url, digest = asset.get('browser_download_url', ''), asset.get('digest', '')
            if not url.startswith(DOWNLOAD_PREFIX) or not re.fullmatch('sha256:[0-9a-f]{64}', digest or ''):
                raise ValueError('Published Java tools lack a trusted download URL or SHA-256 digest')
            print('Downloading Java tools from', release.get('tag_name'), flush=True)
            archive = scratch / 'tools.tar.gz'
            fetch(url, archive, 32 * 1024 * 1024)
            if hashlib.sha256(archive.read_bytes()).hexdigest() != digest.removeprefix('sha256:'):
                raise ValueError('Java tools checksum mismatch')
            candidate = Path(tempfile.mkdtemp(prefix='termuide-java-tools-', dir=share))
            published = False
            link = prefix / 'bin' / ('.termuide-java-lsp-' + uuid.uuid4().hex)
            try:
                launcher = unpack(archive, candidate, revision)
                if launcher is None:
                    continue
                subprocess.run([str(launcher), '--check'], check=True)
                command = prefix / 'bin/termuide-java-lsp'
                if command.exists() and not command.is_symlink():
                    raise ValueError('An unmanaged termuide-java-lsp command already exists; move it before retrying')
                link.symlink_to(launcher)
                os.replace(link, command)
                published = True
                return
            finally:
                link.unlink(missing_ok=True)
                if not published:
                    shutil.rmtree(candidate)
    raise ValueError('No matching Java tools bundle is published in the latest 20 releases yet. Install a released TermuIDE build with Java tools, or retry after its prerelease is published. Existing tools were kept.')


if __name__ == '__main__':
    try:
        install(sys.argv[1], Path(os.environ['HOME']), Path(os.environ['PREFIX']))
    except Exception as error:
        print('Java tools setup failed:', error, file=sys.stderr)
        sys.exit(1)
