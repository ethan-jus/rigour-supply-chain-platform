#!/usr/bin/env python3
"""台式机端发布入口：只管理 rigour-dev-apps，不停止中间件，不删除数据。"""
import argparse
import fcntl
import gzip
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tarfile
import time
from urllib.request import urlopen

ROOT = Path('/srv/rigour-dev/apps')
ROOT.mkdir(parents=True, exist_ok=True)
os.umask(0o077)

def run(args, **kwargs):
    return subprocess.run(args, check=True, **kwargs)

def compose(release, *args):
    env = dict(os.environ, RELEASE_ID=release.name)
    return run(['docker','compose','-f',str(release / 'compose.yaml'),*args],env=env,cwd=release)

def schema_state():
    result = run(['docker','exec','rigour-dev-desktop-mysql-1','sh','-c',
        'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot -Nse "SELECT version,checksum,success FROM rigour_iam.flyway_schema_history ORDER BY installed_rank"'],
        capture_output=True,text=True)
    return result.stdout

def wait_health(url, seconds=150):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        try:
            with urlopen(url, timeout=4) as response:
                if response.status == 200:
                    if '/actuator/' not in url or json.load(response).get('status') == 'UP':
                        return
        except Exception:
            pass
        time.sleep(3)
    raise RuntimeError('服务健康检查超时：' + url)

def switch_current(release):
    temporary = ROOT / 'current.next'
    temporary.unlink(missing_ok=True)
    temporary.symlink_to(release, target_is_directory=True)
    temporary.replace(ROOT / 'current')

parser = argparse.ArgumentParser(description='台式机应用部署、状态、日志和应用回退')
parser.add_argument('action', choices=['deploy','status','logs','rollback'])
parser.add_argument('value', nargs='?')
args = parser.parse_args()
with (ROOT / 'deploy.lock').open('w') as lock:
    fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
    current = ROOT / 'current'
    if args.action in ('status','logs'):
        if not current.exists():
            raise SystemExit('尚未成功发布应用。')
        release = current.resolve()
        print('当前应用版本：' + release.name, flush=True)
        compose(release, *(['ps'] if args.action == 'status' else ['logs','--tail','80']))
    elif args.action == 'rollback':
        if not args.value or not re.fullmatch(r'[0-9]{14}-[0-9a-f]{12}', args.value):
            raise SystemExit('请填写部署记录中的完整旧版本号。')
        release = ROOT / 'releases' / args.value
        if not (release / 'schema-after.txt').exists() or schema_state() != (release / 'schema-after.txt').read_text():
            raise SystemExit('数据库版本与旧应用发布时不同，停止自动回退；不能自动回退数据库。')
        live_hashes = {path.name:hashlib.sha256(path.read_bytes()).hexdigest() for path in (ROOT / 'config').iterdir() if path.is_file()}
        if json.loads((release / 'config-hashes.json').read_text()) != live_hashes:
            raise SystemExit('配置快照不同，需人工核对后回退，未覆盖运行配置。')
        compose(release,'up','-d','--no-build','--pull','never')
        for port in [26881,26880]:
            wait_health(f'http://127.0.0.1:{port}/actuator/health')
        wait_health('http://127.0.0.1:5100/')
        switch_current(release)
        print('应用已回退到：' + release.name)
    else:
        # 首次迁移是单独的人工确认流程；日常发布绝不重灌数据库。
        migration = Path('/mnt/d/RigourDev/backups/cloud-import/迁移验收.json')
        if not migration.is_file() or json.loads(migration.read_text()).get('完整导入已验收') is not True:
            raise SystemExit('尚未完成旧服务器数据库完整迁移验收，停止应用发布；可先运行 build 验证构建。')
        if not args.value:
            raise SystemExit('需要指定 D 盘 downloads 内的发布包。')
        archive = Path(args.value).resolve()
        if archive.parent != Path('/mnt/d/RigourDev/downloads') or not archive.is_file():
            raise SystemExit('发布包必须位于 D:\\RigourDev\\downloads。')
        release_id = archive.name.removesuffix('.tar.gz')
        if not re.fullmatch(r'[0-9]{14}-[0-9a-f]{12}', release_id):
            raise SystemExit('发布包版本号无效。')
        release = ROOT / 'releases' / release_id
        release.mkdir(parents=True, exist_ok=False)
        with tarfile.open(archive) as bundle:
            bundle.extractall(release, filter='data')
        for name, digest in json.loads((release / 'sha256.json').read_text()).items():
            path = (release / name).resolve()
            if not path.is_relative_to(release) or not path.is_file() or hashlib.sha256(path.read_bytes()).hexdigest() != digest:
                raise SystemExit('发布文件完整性校验失败：' + name)
        print('发布包校验通过：' + release_id, flush=True)
        run(['python3',str(release / 'prepare-config.py')])
        compose(release,'config','--quiet')
        compose(release,'build','--pull=false')
        backup = Path('/srv/rigour-dev/apps/backups') / release_id
        backup.mkdir(parents=True)
        shutil.copytree(ROOT / 'config', backup / 'config')
        shutil.copytree(ROOT / 'keys', backup / 'keys')
        with gzip.open(backup / 'iam.sql.gz','wb') as stream:
            result = subprocess.Popen(['docker','exec','rigour-dev-desktop-mysql-1','sh','-c',
                'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysqldump -uroot --single-transaction --set-gtid-purged=OFF --no-tablespaces rigour_iam'],stdout=subprocess.PIPE)
            shutil.copyfileobj(result.stdout,stream)
            if result.wait() != 0:
                raise SystemExit('数据库备份失败，未更新应用。')
        print('已备份本机 IAM 数据库及配置，开始启动应用。', flush=True)
        try:
            compose(release,'up','-d','--no-build','--pull','never','iam')
            wait_health('http://127.0.0.1:26881/actuator/health')
            compose(release,'up','-d','--no-build','--pull','never','gateway','portal')
            wait_health('http://127.0.0.1:26880/actuator/health')
            wait_health('http://127.0.0.1:5100/')
        except Exception:
            print('部署未通过验收，保留旧版本记录和备份；未自动修改或回退数据库。', flush=True)
            compose(release,'ps')
            raise
        (release / 'schema-after.txt').write_text(schema_state())
        hashes = {path.name:hashlib.sha256(path.read_bytes()).hexdigest() for path in (ROOT / 'config').iterdir() if path.is_file()}
        (release / 'config-hashes.json').write_text(json.dumps(hashes,sort_keys=True))
        switch_current(release)
        compose(release,'ps')
        print('应用部署及健康检查完成：http://192.168.12.7:5100', flush=True)
        print('首次部署还需创建管理员并验证真实登录；健康检查不等于业务验收。', flush=True)
