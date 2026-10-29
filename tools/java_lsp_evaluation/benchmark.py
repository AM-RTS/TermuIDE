"""CI-only, stdlib LSP comparison. This does not exercise Android or TermuIDE's adapter."""
import argparse
import json
import os
import pathlib
import platform
import queue
import signal
import statistics
import subprocess
import threading
import time


def read_frame(stream):
    headers = {}
    count = 0
    while True:
        line = stream.readline(8193)
        if not line:
            raise EOFError('Server stdout closed')
        count += len(line)
        if count > 8192 or not line.endswith(b'\r\n'):
            raise ValueError('Invalid LSP header')
        if line == b'\r\n':
            break
        key, value = line.decode('ascii').split(':', 1)
        if key.lower() in headers:
            raise ValueError('Duplicate LSP header')
        headers[key.lower()] = value.strip()
    length = int(headers['content-length'])
    if not 0 < length <= 32 * 1024 * 1024:
        raise ValueError('Invalid LSP payload length')
    payload = stream.read(length)
    if len(payload) != length:
        raise EOFError('Truncated LSP payload')
    return json.loads(payload.decode('utf-8'))


def position(text, offset):
    before = text[:offset]
    return {'line': before.count('\n'),
            'character': len(before.rsplit('\n', 1)[-1].encode('utf-16-le')) // 2}


class Client:
    def __init__(self, command, root, settings, log):
        self.settings, self.root = settings, root
        self.events, self.responses, self.diagnostics = queue.Queue(), {}, {}
        self.sequence = 0
        self.peak_kib = 0
        self.versions = {}
        self.log = log.with_suffix('.jsonl').open('w')
        self.stderr = log.with_suffix('.stderr').open('w')
        env = os.environ.copy()
        for key in ['JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS', 'CLIENT_PORT', 'CLIENT_HOST']:
            env.pop(key, None)
        self.started = time.perf_counter()
        self.process = subprocess.Popen(command, cwd=root, env=env, stdin=subprocess.PIPE,
                                        stdout=subprocess.PIPE, stderr=self.stderr, start_new_session=True)
        self.stop = threading.Event()
        def reader():
            try:
                while True:
                    self.events.put(read_frame(self.process.stdout))
            except Exception as error:
                self.events.put(error)
        def memory():
            while not self.stop.wait(.05):
                self.rss_kib()
        self.reader = threading.Thread(target=reader, daemon=True)
        self.memory = threading.Thread(target=memory, daemon=True)
        self.reader.start()
        self.memory.start()

    def rss_kib(self):
        try:
            fields = dict(line.split(':', 1) for line in pathlib.Path(f'/proc/{self.process.pid}/status').read_text().splitlines() if ':' in line)
            rss = int(fields.get('VmRSS', '0').split()[0])
            self.peak_kib = max(self.peak_kib, int(fields.get('VmHWM', '0').split()[0]), rss)
            return rss
        except (FileNotFoundError, ProcessLookupError):
            return 0

    def send(self, message):
        self.log.write(json.dumps({'send': message}) + '\n')
        self.log.flush()
        body = json.dumps({'jsonrpc': '2.0', **message}, ensure_ascii=False).encode('utf-8')
        self.process.stdin.write(f'Content-Length: {len(body)}\r\n\r\n'.encode() + body)
        self.process.stdin.flush()

    def notify(self, method, params):
        self.send({'method': method, 'params': params})

    def pump(self, timeout):
        message = self.events.get(timeout=max(.001, timeout))
        if isinstance(message, Exception):
            raise message
        self.log.write(json.dumps({'receive': message}) + '\n')
        self.log.flush()
        if 'method' in message:
            method, params = message['method'], message.get('params', {})
            if 'id' in message:
                result = None
                if method == 'workspace/configuration':
                    result = []
                    for item in params.get('items', []):
                        value = self.settings
                        for part in item.get('section', '').split('.'):
                            if part:
                                value = value.get(part, {}) if isinstance(value, dict) else {}
                        result.append(value)
                elif method == 'workspace/workspaceFolders':
                    result = [{'uri': self.root.as_uri(), 'name': self.root.name}]
                elif method == 'workspace/applyEdit':
                    result = {'applied': False, 'failureReason': 'Read-only evaluation client'}
                self.send({'id': message['id'], 'result': result})
            elif method == 'textDocument/publishDiagnostics':
                self.diagnostics[params['uri']] = params
        else:
            self.responses[message['id']] = message

    def request(self, method, params, timeout=25):
        self.sequence += 1
        identity = self.sequence
        self.send({'id': identity, 'method': method, 'params': params})
        deadline = time.perf_counter() + timeout
        while identity not in self.responses:
            remaining = deadline - time.perf_counter()
            if remaining <= 0:
                raise TimeoutError(method)
            try:
                self.pump(remaining)
            except queue.Empty as error:
                raise TimeoutError(method) from error
        response = self.responses.pop(identity)
        if 'error' in response:
            raise RuntimeError(response['error'])
        return response.get('result')

    def document(self, path, text):
        uri = path.as_uri()
        version = self.versions.get(uri, 0) + 1
        self.versions[uri] = version
        self.diagnostics.pop(uri, None)
        if version == 1:
            self.notify('textDocument/didOpen', {'textDocument': {'uri': uri, 'languageId': 'java', 'version': version, 'text': text}})
        else:
            self.notify('textDocument/didChange', {'textDocument': {'uri': uri, 'version': version}, 'contentChanges': [{'text': text}]})
        return version

    def initialize(self):
        result = self.request('initialize', {'processId': None, 'rootUri': self.root.as_uri(),
            'workspaceFolders': [{'uri': self.root.as_uri(), 'name': self.root.name}],
            'capabilities': {'general': {'positionEncodings': ['utf-16']},
                'workspace': {'configuration': True, 'workspaceFolders': True},
                'textDocument': {'completion': {'completionItem': {'snippetSupport': False}},
                                 'publishDiagnostics': {'versionSupport': True}}},
            'initializationOptions': {'settings': self.settings}}, timeout=120)
        self.notify('initialized', {})
        self.notify('workspace/didChangeConfiguration', {'settings': self.settings})
        return result

    def close(self):
        try:
            if self.process.poll() is None:
                try:
                    self.request('shutdown', None, timeout=3)
                    self.notify('exit', None)
                    self.process.wait(timeout=3)
                except Exception:
                    os.killpg(self.process.pid, signal.SIGKILL)
                    self.process.wait(timeout=3)
        finally:
            self.stop.set()
            self.memory.join(timeout=1)
            self.reader.join(timeout=1)
            self.process.stdin.close()
            self.process.stdout.close()
            self.log.close()
            self.stderr.close()


TEMPLATE = '''package demo;
import java.util.List;
import fixture.External;
public class Main {
  public void sample() {
    String text = "example";
    List<String> values = List.of("example");
    String unicode = "🧪"; Object result = CARET;
  }
}
'''
HELPER = 'package demo;\npublic class Helper { public static String greeting() { return "hi"; } }\n'
PERSON = 'package demo;\npublic record Person(String name) {}\n'
COMPACT = 'package demo; public class Compact { public static String compactGreeting() { return "hi"; } }'


def completion(client, file, prefix, expected, absent=None, timeout=5):
    text = TEMPLATE.replace('CARET', prefix)
    client.document(file, text)
    point = position(text, text.index(prefix, text.index('Object result')) + len(prefix))
    attempts, latencies, labels = 0, [], []
    deadline = time.perf_counter() + timeout
    while time.perf_counter() < deadline:
        attempts += 1
        start = time.perf_counter()
        result = client.request('textDocument/completion', {'textDocument': {'uri': file.as_uri()},
                                    'position': point, 'context': {'triggerKind': 1}})
        latencies.append(round((time.perf_counter() - start) * 1000, 2))
        items = result.get('items', []) if isinstance(result, dict) else result or []
        labels = [item.get('label', '') for item in items]
        found = any(label.split('(')[0].split(':')[0].strip() == expected for label in labels)
        stale = absent and any(label.split('(')[0].split(':')[0].strip() == absent for label in labels)
        if found and not stale:
            return {'passed': True, 'attempts': attempts, 'request_ms': latencies, 'labels': labels[:40]}
        time.sleep(.2)
    return {'passed': False, 'attempts': attempts, 'request_ms': latencies, 'labels': labels[:40]}


def evaluate(tools, output, server, heap, dependency):
    folder = output / f'{server}-{heap}'
    root = (folder / 'workspace').resolve()
    source = root / 'src/demo'
    source.mkdir(parents=True)
    main, helper, person, compact = [source / name for name in ['Main.java', 'Helper.java', 'Person.java', 'Compact.java']]
    disk_text = TEMPLATE.replace('CARET', 'text')
    for path, text in [(main, disk_text), (helper, HELPER), (person, PERSON), (compact, COMPACT)]:
        path.write_text(text)
    settings = {'java': {'classPath': [str(dependency)],
        'project': {'sourcePaths': ['src'], 'referencedLibraries': [str(dependency)]},
        'import': {'gradle': {'enabled': False}, 'maven': {'enabled': False}},
        'autobuild': {'enabled': False}, 'configuration': {'updateBuildConfiguration': 'disabled'}}}
    java = str(pathlib.Path(os.environ['JAVA_HOME']) / 'bin/java')
    command = [java, '-Xms64m', f'-Xmx{heap}m', '-XX:+UseG1GC', '-XX:ActiveProcessorCount=2']
    if server == 'george':
        exports = ['api', 'code', 'comp', 'main', 'tree', 'model', 'util']
        command += [f'--add-exports=jdk.compiler/com.sun.tools.javac.{name}=ALL-UNNAMED' for name in exports]
        command += ['--add-opens=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED', '-cp', str(tools/'george/*'), 'org.javacs.Main']
    else:
        launcher = next((tools/'jdtls/plugins').glob('org.eclipse.equinox.launcher_*.jar'))
        command += ['-Declipse.application=org.eclipse.jdt.ls.core.id1', '-Dosgi.bundles.defaultStartLevel=4',
            '-Declipse.product=org.eclipse.jdt.ls.core.product', '-Dlog.level=WARNING',
            '-Dosgi.sharedConfiguration.area=' + str(tools/'jdtls/config_linux'),
            '-Dosgi.sharedConfiguration.area.readOnly=true', '-Dosgi.configuration.cascaded=true',
            '--add-modules=ALL-SYSTEM', '--add-opens=java.base/java.util=ALL-UNNAMED',
            '--add-opens=java.base/java.lang=ALL-UNNAMED', '-jar', str(launcher),
            '-configuration', str(folder.resolve()/'configuration'), '-data', str(folder.resolve()/'metadata')]
    report = {'server': server, 'heap_mib': heap, 'command': command, 'cases': {}}
    client = None
    try:
        print(f'Starting {server}, heap {heap} MiB', flush=True)
        client = Client(command, root, settings, folder/'session')
        capabilities = client.initialize()
        report['initialize_reply_ms'] = round((time.perf_counter() - client.started) * 1000, 2)
        report['capabilities'] = capabilities
        client.document(helper, HELPER)
        client.document(person, PERSON)
        client.document(compact, COMPACT)
        for name, prefix, expected in [('string_and_utf16', 'text.sub', 'substring'),
                ('generic_type', 'values.get(0).sub', 'substring'),
                ('cross_file', 'Helper.gre', 'greeting'),
                ('dependency_jar', 'External.lib', 'libraryGreeting'),
                ('record_accessor', 'new Person("name").na', 'name')]:
            try:
                report['cases'][name] = completion(client, main, prefix, expected, timeout=20 if name == 'string_and_utf16' else 5)
                if name == 'string_and_utf16' and report['cases'][name]['passed']:
                    report['first_useful_completion_ms'] = round((time.perf_counter() - client.started) * 1000, 2)
            except Exception as error:
                report['cases'][name] = {'passed': False, 'error': repr(error)}
        updated = HELPER.replace('greeting', 'farewell')
        client.document(helper, updated)
        report['cases']['unsaved_cross_file_edit'] = completion(client, main, 'Helper.', 'farewell', absent='greeting')
        report['cases']['source_unchanged'] = {'passed': main.read_text() == disk_text and helper.read_text() == HELPER}
        warm = [completion(client, main, 'text.sub', 'substring') for _ in range(5)]
        report['cases']['warm_completion'] = {'passed': all(item['passed'] for item in warm)}
        samples = [sample for item in warm for sample in item['request_ms']]
        report['warm_request_ms'] = {'samples': len(samples), 'median': statistics.median(samples), 'max': max(samples)}
        report['cases']['one_line_package_declaration'] = completion(client, main, 'Compact.comp', 'compactGreeting')
        version = client.document(main, TEMPLATE.replace('CARET', 'missingSymbol'))
        deadline = time.perf_counter() + 15
        diagnostics = []
        while time.perf_counter() < deadline:
            published = client.diagnostics.get(main.as_uri(), {})
            diagnostics = published.get('diagnostics', [])
            if published.get('version', version) == version and any(item.get('severity') == 1 and 'missingSymbol' in item.get('message', '') for item in diagnostics):
                break
            try:
                client.pump(.5)
            except queue.Empty:
                pass
        report['cases']['semantic_diagnostics'] = {'passed': any(item.get('severity') == 1 and 'missingSymbol' in item.get('message', '') for item in diagnostics), 'diagnostics': diagnostics}
        time.sleep(2)
        report['end_rss_mib'] = round(client.rss_kib()/1024, 2)
    except Exception as error:
        report['fatal_error'] = repr(error)
    finally:
        if client:
            report['peak_rss_mib'] = round(client.peak_kib/1024, 2)
            client.close()
    # New process, same workspace metadata; reopen the unsaved text without writing sources.
    if 'fatal_error' not in report:
        restarted = None
        try:
            restarted = Client(command, root, settings, folder/'restart')
            restarted.initialize()
            restarted.document(helper, updated)
            restarted.document(person, PERSON)
            report['cases']['restart_unsaved_buffer'] = completion(restarted, main, 'Helper.', 'farewell', absent='greeting')
        except Exception as error:
            report['cases']['restart_unsaved_buffer'] = {'passed': False, 'error': repr(error)}
        finally:
            if restarted:
                report['restart_peak_rss_mib'] = round(restarted.peak_kib/1024, 2)
                restarted.close()
    report['passed'] = 'fatal_error' not in report and all(case['passed'] for case in report['cases'].values())
    report['workspace_cache_file_bytes'] = sum(p.stat().st_size for name in ['metadata', 'configuration']
        for p in (folder/name).rglob('*') if p.is_file())
    (folder/'result.json').write_text(json.dumps(report, indent=2))
    print(f"Finished {server}, heap {heap}: " + json.dumps({name: case['passed'] for name, case in report['cases'].items()}), flush=True)
    return report


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--tools', type=pathlib.Path, required=True)
    parser.add_argument('--output', type=pathlib.Path, required=True)
    args = parser.parse_args()
    tools, output = args.tools.resolve(), args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    external = output/'dependency-src/fixture/External.java'
    external.parent.mkdir(parents=True)
    external.write_text('package fixture; public class External { public static String libraryGreeting() { return "library"; } }')
    subprocess.run(['javac', '--release', '17', '-d', str(output/'dependency-classes'), str(external)], check=True)
    dependency = output/'dependency.jar'
    subprocess.run(['jar', '--create', '--file', str(dependency), '-C', str(output/'dependency-classes'), '.'], check=True)
    reports = [evaluate(tools, output, server, heap, dependency) for heap in [256, 512] for server in ['george', 'jdtls']]
    preparation = json.loads((tools/'preparation.json').read_text())
    report = {'architecture': platform.machine(), 'platform': platform.platform(), 'cpu_count': os.cpu_count(),
              'preparation': preparation, 'measurements': reports,
              'limits': 'One process trial per heap/server, five warm requests. No filesystem cache flush. Linux JVM RSS, not Android/Termux measurements. Direct LSP client, not TermuIDE adapter or durable draft-store validation.'}
    (output/'comparison.json').write_text(json.dumps(report, indent=2))
    lines = ['| Server | Heap MiB | Checks | Init ms | First completion ms | Warm median ms | Peak RSS MiB |',
             '|---|---:|---|---:|---:|---:|---:|']
    for item in reports:
        passed = sum(case['passed'] for case in item['cases'].values())
        lines.append(f"| {item['server']} | {item['heap_mib']} | {passed}/{len(item['cases'])} | {item.get('initialize_reply_ms', '-')} | {item.get('first_useful_completion_ms', '-')} | {item.get('warm_request_ms', {}).get('median', '-')} | {item.get('peak_rss_mib', '-')} |")
    lines += ['', report['limits']]
    summary = '\n'.join(lines) + '\n'
    (output/'summary.md').write_text(summary)
    if os.environ.get('GITHUB_STEP_SUMMARY'):
        with open(os.environ['GITHUB_STEP_SUMMARY'], 'a') as stream:
            stream.write(summary)
    print(summary)


if __name__ == '__main__':
    main()
