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
from web_health import check_web
from service_catalog import CORE, DOMAINS, PORTS, schemas_for, select_services
from business_config import prepare as prepare_business, mysql

ROOT = Path('/srv/rigour-dev/apps')
ROOT.mkdir(parents=True, exist_ok=True)
os.umask(0o077)

def run(args, **kwargs):
    return subprocess.run(args, check=True, **kwargs)

def compose(release, *args):
    env = dict(os.environ, RELEASE_ID=release.name)
    files = ['-f',str(release / 'compose.yaml')]
    if (release / 'compose.business.json').exists():
        files += ['-f',str(release / 'compose.business.json')]
    return run(['docker','compose',*files,*args],env=env,cwd=release)

def query(sql):
    return mysql(sql)

def release_services(release):
    return ['web' if name == 'portal' else name for name in json.loads((release / '发布记录.json').read_text()).get('部署服务', CORE)]

def schema_state(selected):
    state = {}
    for schema in schemas_for(selected):
        exists = query(f"SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='{schema}' AND table_name='flyway_schema_history';").strip()
        state[schema] = query(f'SELECT version,checksum,success FROM {schema}.flyway_schema_history ORDER BY installed_rank;') if exists == '1' else None
    return state

def backup_before_deploy(release_id):
    """在生成配置、授权或执行任何迁移之前备份所有现有业务库与本机配置。"""
    backup = ROOT / 'backups' / release_id
    backup.mkdir(parents=True, exist_ok=False)
    for name in ['config','keys']:
        if (ROOT / name).exists():
            shutil.copytree(ROOT / name, backup / name)
    shutil.copy2(ROOT.parent / '.env',backup / 'middleware.env')
    known = schemas_for(select_services())
    existing = set(query('SELECT schema_name FROM information_schema.schemata;').splitlines())
    schemas = [name for name in known if name in existing]
    if 'rigour_iam' not in schemas:
        raise RuntimeError('未找到IAM业务库，停止发布。')
    partial = backup / 'business.sql.gz.partial'
    with gzip.open(partial,'wb') as stream:
        result = subprocess.Popen(['docker','exec','rigour-dev-desktop-mysql-1','sh','-c',
            'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysqldump -uroot --single-transaction --routines --events --triggers --set-gtid-purged=OFF --no-tablespaces --databases ' + ' '.join(schemas)],stdout=subprocess.PIPE)
        with result.stdout:
            shutil.copyfileobj(result.stdout,stream)
        if result.wait() != 0:
            raise RuntimeError('数据库备份失败，未更新应用。')
    # 完整读到EOF以验证gzip CRC，防止只记录了半截备份。
    with gzip.open(partial,'rb') as stream:
        while stream.read(1024*1024):
            pass
    final = backup / 'business.sql.gz'
    partial.replace(final)
    with final.open('rb') as stream:
        digest = hashlib.file_digest(stream,'sha256').hexdigest()
    (backup / '备份记录.json').write_text(json.dumps({'数据库': schemas,'文件':final.name,'SHA256':digest},ensure_ascii=False,indent=2))
    print('已备份本机业务库、配置与密钥：' + str(backup),flush=True)
    return backup

def check_release(release):
    for service in release_services(release):
        if service in PORTS:
            wait_health(f'http://127.0.0.1:{PORTS[service]}/actuator/health',240)
    wait_health('http://127.0.0.1:5100/')
    check_web()

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
        if not release.is_dir() or not (release / 'schema-after.json').exists():
            raise SystemExit('目标版本缺少完整业务库快照，不能自动回退到旧的仅IAM发布流程。')
        if set(release_services(release)) != set(release_services(current.resolve())):
            raise SystemExit('回退目标服务范围不同，停止自动回退，避免遗留新服务容器。')
        if schema_state(release_services(release)) != json.loads((release / 'schema-after.json').read_text()):
            raise SystemExit('数据库版本与旧应用发布时不同，停止自动回退；不能自动回退数据库。')
        live_hashes = {path.name:hashlib.sha256(path.read_bytes()).hexdigest() for path in (ROOT / 'config').iterdir() if path.is_file()}
        if json.loads((release / 'config-hashes.json').read_text()) != live_hashes:
            raise SystemExit('配置快照不同，需人工核对后回退，未覆盖运行配置。')
        compose(release,'up','-d','--no-build','--pull','never')
        check_release(release)
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
        selected = release_services(release)
        if selected != select_services(','.join(selected)):
            raise SystemExit('发布包服务清单无效。')
        if current.exists() and not set(release_services(current.resolve())).issubset(selected):
            raise SystemExit('本次服务范围少于当前版本，停止发布，避免遗漏运行中的服务。')
        backup_before_deploy(release_id)
        run(['python3',str(release / 'prepare-config.py')])
        prepare_business(selected)
        compose(release,'config','--quiet')
        compose(release,'build','--pull=false')
        print('配置与镜像准备完成，开始按依赖顺序启动应用。', flush=True)
        try:
            run(['python3',str(release / 'migrate-iam-compat.py')])
            compose(release,'up','-d','--no-build','--pull','never','iam')
            wait_health('http://127.0.0.1:26881/actuator/health')
            for name, title, module, port, suffix, prefix in DOMAINS:
                if name in selected:
                    print('启动并检查：' + title,flush=True)
                    compose(release,'up','-d','--no-build','--pull','never',name)
                    wait_health(f'http://127.0.0.1:{port}/actuator/health',240)
            if current.exists() and 'portal' in json.loads((current.resolve() / '发布记录.json').read_text()).get('部署服务', []):
                # 旧无状态前端容器占用 5100；在新镜像和服务就绪后移除。
                compose(current.resolve(), 'stop', 'portal')
                compose(current.resolve(), 'rm', '-f', 'portal')
            compose(release,'up','-d','--no-build','--pull','never','gateway','web')
            check_release(release)
        except Exception:
            print('部署未通过验收，保留旧版本记录和备份；未自动修改或回退数据库。', flush=True)
            compose(release,'ps')
            raise
        (release / 'schema-after.json').write_text(json.dumps(schema_state(selected),sort_keys=True))
        hashes = {path.name:hashlib.sha256(path.read_bytes()).hexdigest() for path in (ROOT / 'config').iterdir() if path.is_file()}
        (release / 'config-hashes.json').write_text(json.dumps(hashes,sort_keys=True))
        switch_current(release)
        compose(release,'ps')
        print('应用部署及健康检查完成：http://192.168.12.7:5100', flush=True)
        print('首次部署还需创建管理员并验证真实登录；健康检查不等于业务验收。', flush=True)
