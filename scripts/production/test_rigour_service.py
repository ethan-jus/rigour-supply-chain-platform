"""离线验证发布/停服边界；不连接生产、Nacos 或业务数据库。"""
import importlib.util
import io
import json
import os
from pathlib import Path
import signal
import subprocess
import tempfile
import unittest
from unittest import mock
import zipfile

SCRIPTS = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('rigour_service', str(SCRIPTS / 'rigour-service.py'))
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)


def fixture_jar(path, version='one', encrypted=True):
    inner = io.BytesIO()
    with zipfile.ZipFile(inner, 'w') as archive:
        if encrypted:
            for name in ('LocalSecretStore', 'LocalSecretsEnvironmentPostProcessor'):
                archive.writestr('com/rigour/platform/secrets/' + name + '.class', b'fixture')
            archive.writestr('META-INF/spring.factories',
                             'org.springframework.boot.EnvironmentPostProcessor=\\\n'
                             'com.rigour.platform.secrets.LocalSecretsEnvironmentPostProcessor\n')
    with zipfile.ZipFile(str(path), 'w') as archive:
        archive.writestr('BOOT-INF/lib/rigour-platform-starter-1.0.jar', inner.getvalue())
        archive.writestr('version.txt', version)


class ServiceTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.proc = self.root / 'proc'
        self.proc.mkdir()
        self.patches = [mock.patch.object(m, 'ROOT', self.root), mock.patch.object(m, 'PROC', self.proc),
                        mock.patch.object(m, 'RUNTIME', self.root / 'runtime.json')]
        for patch in self.patches:
            patch.start()

    def tearDown(self):
        for patch in reversed(self.patches):
            patch.stop()
        self.temp.cleanup()

    def process(self, pid, name, stamp='123'):
        folder = self.proc / str(pid)
        folder.mkdir(exist_ok=True)
        fields = ['S'] + ['0'] * 18 + [stamp] + ['0'] * 10
        (folder / 'stat').write_text('{} (java) {}'.format(pid, ' '.join(fields)))
        (folder / 'cmdline').write_bytes(('java\0-jar\0' + name + '\0--spring.profiles.active=prod\0').encode())
        if not (folder / 'cwd').exists():
            (folder / 'cwd').symlink_to(self.root)
        return folder

    def test_scope_excludes_ruoyi_nacos_sales_and_ai(self):
        self.process(200, 'ruoyi-gateway.jar')
        self.process(201, 'nacos-server.jar')
        self.process(202, m.jar_name('gateway'))
        self.assertEqual(m.find_process('gateway')[0], 202)
        self.assertEqual(len(m.selected(['all'])), 9)
        for service in ('ruoyi', 'nacos', 'sales', 'ai', 'setting'):
            with self.assertRaises(m.OperationError):
                m.selected([service])

    def test_duplicate_and_legacy_processes_refuse_operation(self):
        self.process(200, m.jar_name('hr'))
        self.process(201, m.jar_name('hr'))
        with self.assertRaises(m.OperationError):
            m.find_process('hr')
        self.process(202, 'order-server.jar')
        with self.assertRaises(m.OperationError):
            m.find_process('order')

    def test_runtime_preserves_known_heaps_and_disabled_jobs(self):
        config = m.read_runtime()
        self.assertIn('-Xmx384m', m.java_args('order', config))
        self.assertIn('-Xmx256m', m.java_args('hr', config))
        self.assertIn('--rigour.analytics.supply-dashboard.refresh.enabled=false', m.java_args('bi', config))
        self.assertIn('--rigour.integration.product-media.worker-enabled=false', m.java_args('integration', config))
        self.assertIn('--rigour.integration.dhb.orchestration.enabled=false', m.java_args('integration', config))
        self.assertIn('--rigour.iam.bootstrap.local-signing-key.enabled=false', m.java_args('iam', config))
        self.assertFalse(any(arg.startswith('-Xmx') for arg in m.java_args('gateway', config)))
        self.assertTrue(all('--spring.flyway.enabled=false' in m.java_args(s, config) for s in m.ORDER))

    def test_runtime_rejects_unknown_fields_and_bad_heaps(self):
        fake = mock.Mock()
        fake.exists.return_value = True
        fake.is_file.return_value = True
        fake.is_symlink.return_value = False
        fake.stat.return_value = mock.Mock(st_uid=0, st_mode=0o100600)
        for data in ({'bi': {'password': 'not-allowed'}}, {'bi': {'xmx_mib': True}},
                     {'bi': {'xms_mib': 512, 'xmx_mib': 256}}):
            fake.open.side_effect = lambda: io.StringIO(json.dumps(data))
            with mock.patch.object(m, 'RUNTIME', fake), self.assertRaises(m.OperationError):
                m.read_runtime()

    def test_stop_uses_term_and_does_not_kill_reused_pid(self):
        self.process(200, m.jar_name('order'))
        def replace_pid(pid, sig):
            self.assertEqual((pid, sig), (200, signal.SIGTERM))
            self.process(200, 'ruoyi-gateway.jar', stamp='124')
        with mock.patch.object(m.os, 'kill', side_effect=replace_pid) as kill, \
                mock.patch.object(m, 'listeners', return_value=''):
            m.stop_one('order')
        kill.assert_called_once_with(200, signal.SIGTERM)

    def test_stop_rejects_replacement_service_process(self):
        self.process(200, m.jar_name('order'))
        def replace_pid(pid, sig):
            self.process(200, m.jar_name('order'), stamp='124')
        with mock.patch.object(m.os, 'kill', side_effect=replace_pid) as kill, \
                mock.patch.object(m, 'listeners', return_value=''), self.assertRaises(m.OperationError):
            m.stop_one('order')
        kill.assert_called_once_with(200, signal.SIGTERM)

    def test_stop_timeout_never_sends_kill(self):
        self.process(200, m.jar_name('order'))
        with mock.patch.object(m.os, 'kill') as kill, mock.patch.object(m, 'STOP_TIMEOUT', 1), \
                mock.patch.object(m.time, 'monotonic', side_effect=[0, 0, 2]), \
                mock.patch.object(m.time, 'sleep'), self.assertRaises(m.OperationError):
            m.stop_one('order')
        kill.assert_called_once_with(200, signal.SIGTERM)

    def test_health_must_belong_to_selected_process(self):
        self.process(200, m.jar_name('order'))
        snapshot = m.find_process('order')
        with mock.patch.object(m, 'listeners', return_value='users:(("java",pid=201,fd=8))'), \
                mock.patch.object(m, 'health', return_value=(200, 'UP')) as health:
            self.assertFalse(m.ready('order', snapshot))
            health.assert_not_called()
        for response in ((200, 'DOWN'), (503, 'UP'), (302, 'UP')):
            with mock.patch.object(m, 'listeners', return_value='users:(("java",pid=200,fd=8))'), \
                    mock.patch.object(m, 'health', return_value=response):
                self.assertFalse(m.ready('order', snapshot))

    def test_start_is_detached_and_keeps_bi_refresh_off(self):
        child = mock.Mock(pid=200)
        child.poll.return_value = None
        snapshot = (200, '123', str((self.root / m.jar_name('bi')).resolve()))
        with mock.patch.object(m, 'find_process', return_value=None), \
                mock.patch.object(m, 'listeners', return_value=''), \
                mock.patch.object(m, 'process_snapshot', return_value=snapshot), \
                mock.patch.object(m, 'ready', return_value=True), \
                mock.patch.object(m.subprocess, 'Popen', return_value=child) as popen:
            m.start_one('bi', m.read_runtime())
        command = popen.call_args[0][0]
        self.assertIn('--rigour.analytics.supply-dashboard.refresh.enabled=false', command)
        self.assertTrue(popen.call_args[1]['start_new_session'])
        self.assertEqual((self.root / 'bi-local-secrets.pid').read_text(), '200\n')
        self.assertEqual((self.root / 'bi-local-secrets.log').stat().st_mode & 0o777, 0o600)

    def test_checksum_and_crypto_component_gate(self):
        folder = self.root / 'release'
        folder.mkdir()
        jar = folder / m.jar_name('bi')
        fixture_jar(jar)
        sums = folder / 'SHA256SUMS'
        sums.write_text(m.digest(jar) + '  ' + jar.name + '\n')
        self.assertEqual(m.checked_source(folder, 'bi')[0], jar)
        jar.write_bytes(b'changed')
        with self.assertRaises(m.OperationError):
            m.checked_source(folder, 'bi')
        fixture_jar(jar, encrypted=False)
        with self.assertRaises(m.OperationError):
            m.validate_jar(jar)
        sums.write_text('0' * 64 + '  ../escape.jar\n')
        with self.assertRaises(m.OperationError):
            m.checked_source(folder, 'bi')

    def test_deploy_prepares_backup_before_stop_and_preserves_failed_release(self):
        target = self.root / m.jar_name('bi')
        source = self.root / 'new.jar'
        fixture_jar(target, 'old')
        fixture_jar(source, 'new')
        old_hash, new_hash = m.digest(target), m.digest(source)
        def stop(service):
            self.assertEqual(m.digest(target), old_hash)
            self.assertEqual(len(list((self.root / 'backups').glob('*/SHA256SUMS'))), 1)
        with mock.patch.object(m, 'stop_one', side_effect=stop), \
                mock.patch.object(m, 'start_one', side_effect=m.OperationError('startup failed')), \
                self.assertRaises(m.OperationError):
            m.deploy_one('bi', source, new_hash, m.read_runtime())
        self.assertEqual(m.digest(target), new_hash)
        backup = next((self.root / 'backups').iterdir())
        self.assertEqual(m.checked_source(backup, 'bi')[1], old_hash)
        self.assertEqual(list(self.root.glob('.bi-deploy-*')), [])

    def test_prepare_failure_does_not_stop_service(self):
        source = self.root / 'new.jar'
        fixture_jar(source)
        with mock.patch.object(m, 'stop_one') as stop, self.assertRaises(m.OperationError):
            m.deploy_one('bi', source, '0' * 64, m.read_runtime())
        stop.assert_not_called()

    def test_concurrent_operation_is_rejected(self):
        with m.operation_lock(), self.assertRaises(m.OperationError):
            with m.operation_lock():
                self.fail('lock should reject')

    def test_batch_stops_after_first_start_failure(self):
        with mock.patch.object(m.sys, 'argv', ['manager', 'restart', 'all']), \
                mock.patch.object(m.sys, 'platform', 'linux'), mock.patch.object(m.os, 'geteuid', return_value=0), \
                mock.patch.object(m.resource, 'setrlimit'), mock.patch.object(m, 'preflight'), \
                mock.patch.object(m, 'stop_one') as stop, \
                mock.patch.object(m, 'start_one', side_effect=m.OperationError('failed')) as start, \
                self.assertRaises(m.OperationError):
            m.main()
        stop.assert_called_once_with('gateway')
        self.assertEqual(start.call_count, 1)

    def test_failed_maven_produces_no_release(self):
        scripts = self.root / 'scripts' / 'production'
        scripts.mkdir(parents=True)
        (scripts / 'package-release.sh').write_bytes((SCRIPTS / 'package-release.sh').read_bytes())
        wrapper = self.root / 'mvnw'
        wrapper.write_text('#!/bin/sh\nexit 42\n')
        wrapper.chmod(0o700)
        result = subprocess.run(['bash', str(scripts / 'package-release.sh'), '--without-docker', 'bi'],
                                stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        self.assertEqual(result.returncode, 42)
        self.assertFalse((self.root / 'target' / 'production-release').exists())

    def test_package_and_upload_use_exact_selected_services(self):
        scripts = self.root / 'scripts' / 'production'
        scripts.mkdir(parents=True)
        for name in ('package-release.sh', 'rigour-service.py', 'service-runtime.json.example'):
            (scripts / name).write_bytes((SCRIPTS / name).read_bytes())
        for service in ('iam', 'bi'):
            folder = self.root / 'services' / ('rg-scdp-' + service) / (service + '-server') / 'target'
            folder.mkdir(parents=True)
            fixture_jar(folder / (service + '-server.jar'))
        wrapper = self.root / 'mvnw'
        wrapper.write_text('#!/bin/sh\nprintf "%s\\n" "$@" > maven-arguments.txt\n')
        wrapper.chmod(0o700)
        bin_dir = self.root / 'bin'
        bin_dir.mkdir()
        git = bin_dir / 'git'
        git.write_text('#!/bin/sh\ncase "$1" in\nrev-parse) echo abcdef0123456789 ;;\n'
                       'status) echo " M example" ;;\n*) exit 91 ;;\nesac\n')
        git.chmod(0o700)
        environment = dict(os.environ, PATH=str(bin_dir) + os.pathsep + os.environ['PATH'])
        result = subprocess.run(['bash', str(scripts / 'package-release.sh'), '--without-docker', 'bi', 'iam', 'bi'],
                                env=environment, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                universal_newlines=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        release = next((self.root / 'target' / 'production-release').iterdir())
        metadata = json.loads((release / 'release.json').read_text())
        self.assertEqual(metadata['services'], ['iam', 'bi'])
        self.assertTrue(metadata['working_tree_dirty'])
        self.assertEqual(metadata['git_head'], 'abcdef0123456789')
        self.assertEqual(m.checked_source(release, 'bi')[0].name, m.jar_name('bi'))
        arguments = (self.root / 'maven-arguments.txt').read_text().splitlines()
        self.assertIn('-am', arguments)
        self.assertIn('services/rg-scdp-iam/iam-server,services/rg-scdp-bi/bi-server', arguments)
        self.assertFalse(any('skipTests' in argument for argument in arguments))
        exclusions = next(a for a in arguments if a.startswith('-Dtest='))
        self.assertEqual(exclusions.count('!'), 12)
        self.assertFalse((scripts / '__pycache__').exists())

        # SSH/SCP 用本地桩替代；此测试不会连接任何主机。
        calls = self.root / 'network-calls.txt'
        environment['RIGOUR_TEST_CALLS'] = str(calls)
        for name in ('ssh', 'scp'):
            stub = bin_dir / name
            stub.write_text('#!/bin/sh\necho "' + name + '" >> "$RIGOUR_TEST_CALLS"\n' +
                            ('cat >/dev/null\n' if name == 'ssh' else ''))
            stub.chmod(0o700)
        command = ['bash', str(SCRIPTS / 'upload-release.sh'), 'root@example.invalid', str(release), '22']
        upload = subprocess.run(command, env=environment, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                universal_newlines=True)
        self.assertEqual(upload.returncode, 0, upload.stderr)
        self.assertIn('deploy ' + release.name + ' iam bi', upload.stdout)
        self.assertEqual(calls.read_text().splitlines(), ['ssh', 'scp', 'ssh'])
        calls.unlink()
        (release / m.jar_name('bi')).write_bytes(b'corrupt')
        rejected = subprocess.run(command, env=environment, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        self.assertNotEqual(rejected.returncode, 0)
        self.assertFalse(calls.exists())


if __name__ == '__main__':
    unittest.main()
