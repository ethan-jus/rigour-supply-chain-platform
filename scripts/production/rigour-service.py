#!/usr/bin/env python3
"""CentOS 7 / Python 3.6：管理本次已迁移的九个 Platform 服务。"""
import argparse
import contextlib
import fcntl
import hashlib
import io
import json
import os
from pathlib import Path
import re
import resource
import shutil
import signal
import stat
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
import uuid
import zipfile

ROOT = Path('/root/rigour_scm')
PROC = Path('/proc')
JAVA = '/usr/local/jdk21/bin/java'
ENV_FILE = Path('/etc/rigour/local-prod.env')
SECRETS = '/etc/rigour/local-secrets'
RUNTIME = Path('/etc/rigour/service-runtime.json')
ORDER = ('gateway', 'iam', 'foundation', 'hr', 'crm', 'erp', 'order', 'integration', 'bi')
STOP_ORDER = ('gateway', 'bi', 'integration', 'order', 'erp', 'crm', 'hr', 'foundation', 'iam')
PORTS = dict(zip(ORDER, (26880, 26881, 26892, 26889, 26883, 26884, 26885, 26882, 26888)))
HEAPS = {'gateway': (None, None), 'iam': (None, None), 'foundation': (64, 384),
         'hr': (64, 256), 'crm': (64, 384), 'erp': (128, 512), 'order': (64, 384),
         'integration': (128, 512), 'bi': (64, 256)}
SWITCHES = {
    'integration': {'product_media_worker_enabled': 'rigour.integration.product-media.worker-enabled',
                    'dhb_orchestration_enabled': 'rigour.integration.dhb.orchestration.enabled'},
    'bi': {'refresh_enabled': 'rigour.analytics.supply-dashboard.refresh.enabled'},
}
STOP_TIMEOUT = 45
START_TIMEOUT = 120


class OperationError(Exception):
    pass


def require(condition, message):
    if not condition:
        raise OperationError(message)


def jar_name(service):
    return service + '-server.local-secrets.jar'


def digest(path):
    result = hashlib.sha256()
    with path.open('rb') as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b''):
            result.update(chunk)
    return result.hexdigest()


def regular_file(path):
    require(path.is_file() and not path.is_symlink(), '需要普通文件：' + str(path))


def atomic_text(path, text):
    fd, name = tempfile.mkstemp(prefix='.' + path.name, dir=str(path.parent))
    try:
        with os.fdopen(fd, 'w') as handle:
            handle.write(text)
        os.replace(name, str(path))
    finally:
        if os.path.exists(name):
            os.unlink(name)


def validate_jar(path):
    regular_file(path)
    try:
        with zipfile.ZipFile(str(path)) as outer:
            libraries = [n for n in outer.namelist()
                         if n.startswith('BOOT-INF/lib/rigour-platform-starter-') and n.endswith('.jar')]
            require(len(libraries) == 1, 'JAR 未包含唯一的 rigour-platform-starter：' + path.name)
            with zipfile.ZipFile(io.BytesIO(outer.read(libraries[0]))) as inner:
                for name in ('LocalSecretStore', 'LocalSecretsEnvironmentPostProcessor'):
                    require('com/rigour/platform/secrets/' + name + '.class' in inner.namelist(),
                            'JAR 缺少本机解密组件：' + path.name)
                factories = inner.read('META-INF/spring.factories').decode('utf-8')
                require('org.springframework.boot.EnvironmentPostProcessor=' in factories and
                        'com.rigour.platform.secrets.LocalSecretsEnvironmentPostProcessor' in factories,
                        'JAR 未注册解密组件：' + path.name)
    except (zipfile.BadZipFile, KeyError, UnicodeError):
        raise OperationError('JAR 结构或解密注册不正确：' + path.name)


def read_runtime():
    data = {}
    if RUNTIME.exists():
        regular_file(RUNTIME)
        mode = RUNTIME.stat()
        require(mode.st_uid == 0 and stat.S_IMODE(mode.st_mode) == 0o600,
                '运行参数文件必须为 root 所有、600 权限：' + str(RUNTIME))
        with RUNTIME.open() as handle:
            data = json.load(handle)
    require(isinstance(data, dict) and set(data).issubset(ORDER), '运行参数包含未知服务')
    result = {}
    for service in ORDER:
        xms, xmx = HEAPS[service]
        values = {'xms_mib': xms, 'xmx_mib': xmx}
        values.update({key: False for key in SWITCHES.get(service, {})})
        override = data.get(service, {})
        require(isinstance(override, dict) and set(override).issubset(values),
                '运行参数字段错误：' + service)
        values.update(override)
        for key in ('xms_mib', 'xmx_mib'):
            value = values[key]
            require(value is None or (type(value) is int and value > 0), '堆大小必须是正整数或 null')
        require(values['xms_mib'] is None or values['xmx_mib'] is None or
                values['xms_mib'] <= values['xmx_mib'], 'Xms 不能大于 Xmx：' + service)
        for key in SWITCHES.get(service, {}):
            require(type(values[key]) is bool, '后台任务开关必须是 true/false：' + service)
        result[service] = values
    return result


def java_args(service, runtime):
    config = runtime[service]
    args = [JAVA]
    for key, flag in (('xms_mib', '-Xms'), ('xmx_mib', '-Xmx')):
        if config[key] is not None:
            args.append(flag + str(config[key]) + 'm')
    args += ['-jar', jar_name(service), '--spring.profiles.active=prod', '--spring.flyway.enabled=false']
    if service == 'iam':
        args += ['--rigour.iam.bootstrap.' + key + '.enabled=false'
                 for key in ('tenant-admin', 'scdp-client', 'local-signing-key')]
    for key, prop in SWITCHES.get(service, {}).items():
        args.append('--' + prop + '=' + str(config[key]).lower())
    return args


def process_snapshot(pid):
    """PID + 内核启动时间共同标识进程；不输出命令行或环境变量。"""
    try:
        folder = PROC / str(pid)
        raw = (folder / 'stat').read_text()
        fields = raw[raw.rfind(')') + 2:].split()
        if fields[0] == 'Z':
            return None
        args = (folder / 'cmdline').read_bytes().split(b'\0')
        if not args or Path(os.fsdecode(args[0])).name != 'java' or b'-jar' not in args:
            return None
        index = args.index(b'-jar') + 1
        jar = Path(os.fsdecode(args[index]))
        if not jar.is_absolute():
            jar = Path(os.readlink(str(folder / 'cwd'))) / jar
        return (int(pid), fields[19], str(jar.resolve()))
    except (OSError, IndexError, ValueError):
        return None


def find_process(service):
    target = str((ROOT / jar_name(service)).resolve())
    legacy = str((ROOT / (service + '-server.jar')).resolve())
    matches = []
    for folder in PROC.iterdir():
        if not folder.name.isdigit():
            continue
        snapshot = process_snapshot(folder.name)
        if snapshot and snapshot[2] == legacy:
            raise OperationError('发现旧命名 JAR 的进程，请先人工核对：' + service)
        if snapshot and snapshot[2] == target:
            matches.append(snapshot)
    require(len(matches) <= 1, '发现多个同服务进程，拒绝自动操作：' + service)
    return matches[0] if matches else None


def listeners(service):
    result = subprocess.run(['ss', '-lntpH', 'sport = :' + str(PORTS[service])],
                            stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                            universal_newlines=True, timeout=5)
    require(result.returncode == 0, '无法读取端口状态：' + service)
    return result.stdout


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def health(service):
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())
    try:
        with opener.open('http://127.0.0.1:{}/actuator/health'.format(PORTS[service]), timeout=4) as response:
            payload = response.read(65537)
            if len(payload) > 65536:
                return response.status, 'UNKNOWN'
            data = json.loads(payload.decode('utf-8'))
            value = data.get('status') if isinstance(data, dict) else None
            return response.status, value if value in ('UP', 'DOWN', 'OUT_OF_SERVICE') else 'UNKNOWN'
    except urllib.error.HTTPError as error:
        return error.code, 'UNKNOWN'
    except (OSError, ValueError, UnicodeError, urllib.error.URLError):
        return 0, 'UNKNOWN'


def ready(service, snapshot):
    if not snapshot or process_snapshot(snapshot[0]) != snapshot:
        return False
    if not re.search(r'pid=' + str(snapshot[0]) + r',', listeners(service)):
        return False
    return health(service) == (200, 'UP')


def preflight(service, jar):
    validate_jar(jar)
    regular_file(ENV_FILE)
    mode = ENV_FILE.stat()
    require(mode.st_uid == 0 and stat.S_IMODE(mode.st_mode) == 0o600,
            'local-prod.env 必须为 root 所有、600 权限')
    regular_file(ROOT / 'rigour-local-secrets-tool.jar')
    check = subprocess.run([JAVA, '-Xms16m', '-Xmx32m', '-jar',
                            str(ROOT / 'rigour-local-secrets-tool.jar'), 'check', SECRETS, service],
                           stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=30)
    require(check.returncode == 0, '本机密文校验失败：' + service)
    find_process(service)
    print('PREFLIGHT_OK: ' + service, flush=True)


def stop_one(service):
    snapshot = find_process(service)
    if not snapshot:
        require(not listeners(service), '未发现目标进程，但端口被占用：' + service)
        print('STOPPED: ' + service + '（本来就未运行）', flush=True)
        return
    pid = snapshot[0]
    require(pid > 1 and process_snapshot(pid) == snapshot, '进程身份已变化：' + service)
    print('STOPPING: {} PID={}'.format(service, pid), flush=True)
    try:
        os.kill(pid, signal.SIGTERM)
    except ProcessLookupError:
        pass
    deadline = time.monotonic() + STOP_TIMEOUT
    while process_snapshot(pid) == snapshot and time.monotonic() < deadline:
        time.sleep(1)
    require(process_snapshot(pid) != snapshot,
            '{} 在 {} 秒内未退出；停止后续操作，不强制结束'.format(service, STOP_TIMEOUT))
    require(find_process(service) is None, '发现新的同服务进程，请核对是否有自动拉起：' + service)
    require(not listeners(service), '进程退出后端口仍被占用：' + service)
    print('STOPPED: {} PID={}'.format(service, pid), flush=True)


def start_one(service, runtime):
    existing = find_process(service)
    if existing:
        require(ready(service, existing), '已有进程但未就绪，请检查日志：' + service)
        print('ALREADY_READY: {} PID={}'.format(service, existing[0]), flush=True)
        return
    require(not listeners(service), '端口被占用，拒绝重复启动：' + service)
    log = ROOT / (service + '-local-secrets.log')
    if log.exists() or log.is_symlink():
        regular_file(log)
    # source 只在新子进程里生效；显式注入目录及 scope，随后 exec 保持 PID 不变。
    bootstrap = ('set +x; set -e; set -a; source "$1"; shift; '
                 'export RIGOUR_LOCAL_SECRETS_ENABLED=true; '
                 'export RIGOUR_LOCAL_SECRETS_DIR="$1"; shift; '
                 'export RIGOUR_LOCAL_SECRETS_SERVICE="$1"; shift; exec "$@"')
    fd = os.open(str(log), os.O_WRONLY | os.O_CREAT | os.O_APPEND | os.O_NOFOLLOW, 0o600)
    with os.fdopen(fd, 'ab', buffering=0) as output:
        os.fchmod(output.fileno(), 0o600)
        child = subprocess.Popen(['/bin/bash', '-c', bootstrap, 'rigour-start',
                                  str(ENV_FILE), SECRETS, service] + java_args(service, runtime),
                                 cwd=str(ROOT), stdin=subprocess.DEVNULL, stdout=output,
                                 stderr=subprocess.STDOUT, start_new_session=True)
    atomic_text(ROOT / (service + '-local-secrets.pid'), str(child.pid) + '\n')
    print('STARTING: {} PID={}'.format(service, child.pid), flush=True)
    deadline = time.monotonic() + START_TIMEOUT
    while time.monotonic() < deadline:
        require(child.poll() is None, '进程已退出，请查看 ' + log.name)
        snapshot = process_snapshot(child.pid)
        if snapshot and snapshot[2] == str((ROOT / jar_name(service)).resolve()) and ready(service, snapshot):
            print('READY: {} PID={} PORT={} HTTP=200 STATUS=UP'.format(
                service, child.pid, PORTS[service]), flush=True)
            return
        time.sleep(2)
    raise OperationError('健康检查超时；保留进程和日志，请检查 ' + log.name)


def release_folder(kind, identifier):
    require(re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9._-]{0,79}', identifier) is not None,
            '版本号只允许字母、数字、点、下划线、短横线')
    parent = ROOT / kind
    folder = parent / identifier
    require(not parent.is_symlink() and not folder.is_symlink() and folder.is_dir(), '版本目录不存在或是链接')
    return folder


def checked_source(folder, service):
    sums = folder / 'SHA256SUMS'
    regular_file(sums)
    entries = {}
    for line in sums.read_text().splitlines():
        match = re.fullmatch(r'([0-9a-f]{64})  ([A-Za-z0-9._-]+)', line)
        require(match is not None, 'SHA256SUMS 格式不正确')
        require(match.group(2) not in entries, 'SHA256SUMS 含重复文件')
        entries[match.group(2)] = match.group(1)
    source = folder / jar_name(service)
    regular_file(source)
    require(source.name in entries and digest(source) == entries[source.name], 'JAR 校验和不匹配：' + service)
    validate_jar(source)
    return source, entries[source.name]


def deploy_one(service, source, expected, runtime):
    target = ROOT / jar_name(service)
    fd, temporary = tempfile.mkstemp(prefix='.' + service + '-deploy-', suffix='.jar', dir=str(ROOT))
    os.close(fd)
    backup_id = None
    try:
        # 新包和备份均在停服务之前准备，避免磁盘不足时先停掉旧服务。
        shutil.copyfile(str(source), temporary)
        require(digest(Path(temporary)) == expected, '准备新包时校验失败：' + service)
        if target.exists() or target.is_symlink():
            regular_file(target)
            backup_id = time.strftime('%Y%m%dT%H%M%SZ', time.gmtime()) + '-' + uuid.uuid4().hex[:8]
            backup = ROOT / 'backups' / backup_id
            require(not (ROOT / 'backups').is_symlink(), 'backups 目录不能是链接')
            backup.mkdir(mode=0o700, parents=True)
            shutil.copyfile(str(target), str(backup / target.name))
            atomic_text(backup / 'SHA256SUMS', digest(backup / target.name) + '  ' + target.name + '\n')
            print('BACKUP: {} {}'.format(service, backup_id), flush=True)
        stop_one(service)
        os.replace(temporary, str(target))
        start_one(service, runtime)
    except Exception:
        if backup_id:
            print('可人工回退：python3 /root/rigour_scm/rigour-service.py rollback {} {}'.format(
                service, backup_id), file=sys.stderr)
        raise
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


@contextlib.contextmanager
def operation_lock():
    fd = os.open(str(ROOT / '.rigour-service.lock'), os.O_CREAT | os.O_RDWR | os.O_NOFOLLOW, 0o600)
    try:
        try:
            fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            raise OperationError('已有服务管理操作正在执行，请等待结束')
        yield
    finally:
        os.close(fd)


def selected(names):
    require(bool(names), '必须明确指定服务名或 all')
    if names == ['all']:
        return list(ORDER)
    require('all' not in names and all(n in ORDER for n in names), '未知服务；setting 对应 foundation')
    return [service for service in ORDER if service in names]


def memory_report():
    for line in (PROC / 'meminfo').read_text().splitlines():
        if line.startswith('MemAvailable:'):
            print('MEM_AVAILABLE_MIB={:.0f}'.format(int(line.split()[1]) / 1024), flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='action')
    for action in ('start', 'stop', 'restart', 'status', 'check'):
        sub.add_parser(action).add_argument('services', nargs='+', help='服务名列表或 all')
    deploy = sub.add_parser('deploy', help='校验新包、备份、停旧、替换、启动')
    deploy.add_argument('release_id')
    deploy.add_argument('services', nargs='+')
    rollback = sub.add_parser('rollback', help='回退 JAR；不回退数据库、配置或凭据')
    rollback.add_argument('service', choices=ORDER)
    rollback.add_argument('backup_id')
    args = parser.parse_args()
    require(args.action is not None, '请指定操作；使用 --help 查看用法')
    require(sys.platform.startswith('linux') and os.geteuid() == 0, '请在生产 Linux 的 root 终端执行')
    require(ROOT.is_dir() and not ROOT.is_symlink(), '服务目录不存在或是链接：' + str(ROOT))
    os.umask(0o077)
    resource.setrlimit(resource.RLIMIT_CORE, (0, 0))
    services = [args.service] if args.action == 'rollback' else selected(args.services)
    if args.action in ('status', 'check'):
        all_ready = True
        for service in services:
            snapshot = find_process(service)
            ok = ready(service, snapshot) if snapshot else False
            all_ready = all_ready and ok
            print('{} PID={} PORT={} {}'.format(service, snapshot[0] if snapshot else '-', PORTS[service],
                                              'READY' if ok else ('NOT_READY' if snapshot else 'STOPPED')))
        memory_report()
        return 0 if args.action == 'status' or all_ready else 1
    with operation_lock():
        if args.action == 'stop':
            for service in services:
                find_process(service)
            for service in STOP_ORDER:
                if service in services:
                    stop_one(service)
        else:
            runtime = read_runtime()
            candidates = {}
            if args.action in ('deploy', 'rollback'):
                folder = release_folder('releases' if args.action == 'deploy' else 'backups',
                                        args.release_id if args.action == 'deploy' else args.backup_id)
                for service in services:
                    candidates[service] = checked_source(folder, service)
            for service in services:
                preflight(service, candidates[service][0] if service in candidates else ROOT / jar_name(service))
            for service in services:
                if service in candidates:
                    deploy_one(service, candidates[service][0], candidates[service][1], runtime)
                else:
                    if args.action == 'restart':
                        stop_one(service)
                    start_one(service, runtime)
                memory_report()
    return 0


if __name__ == '__main__':
    try:
        sys.exit(main())
    except OperationError as error:
        print('停止：' + str(error), file=sys.stderr)
        sys.exit(1)
    except (OSError, ValueError, subprocess.SubprocessError):
        # 不把环境变量、配置解析内容或子进程输出带进错误日志。
        print('停止：文件、参数或系统命令检查失败；请检查路径、权限、JSON 格式及服务日志', file=sys.stderr)
        sys.exit(1)
    except KeyboardInterrupt:
        print('操作被中断；先运行 status all 核对现状，再决定下一步', file=sys.stderr)
        sys.exit(130)
