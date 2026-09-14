#!/usr/bin/env python3
"""台式机原生部署：在 D 盘拉 Git、构建测试和发布，无 Mac 运行依赖。"""
import argparse
from datetime import datetime
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tarfile
import tempfile
import sys
import fcntl

PLATFORM = Path(__file__).resolve().parents[2]
PORTAL = PLATFORM.parent / 'rigour-supply-chain-portal'
SOURCE = Path(__file__).resolve().parent
ROOT = Path('/srv/rigour-dev')

def run(args, **kwargs):
    print('执行：' + ' '.join(str(item) for item in args), flush=True)
    try:
        return subprocess.run(args, check=True, **kwargs)
    except subprocess.CalledProcessError:
        log = kwargs.get('stdout')
        if hasattr(log, 'name'):
            print('构建或测试失败，未发布应用。详细日志：' + str(log.name), flush=True)
        raise

def git(repo, *args):
    return subprocess.check_output(['git','-C',str(repo),*args],text=True).strip()

parser = argparse.ArgumentParser(description='中文一键部署 IAM、Gateway、门户到 D 盘开发环境')
parser.add_argument('action', nargs='?',default='deploy',choices=['deploy','build','status','logs','rollback'])
parser.add_argument('--version',help='回退版本号，见发布记录')
args = parser.parse_args()
if sys.platform != 'linux' or not PLATFORM.is_relative_to(ROOT / 'src') or not Path('/mnt/d/RigourDev').is_dir():
    raise SystemExit('请在台式机 D:\\RigourDev 运行中文部署入口；本脚本不在 Mac 构建。')
if args.action not in ('deploy','build'):
    import re
    if args.action == 'rollback' and not re.fullmatch(r'[0-9]{14}-[0-9a-f]{12}',args.version or ''):
        raise SystemExit('回退时必须提供 --version 完整版本号。')
    run(['python3',str(SOURCE / 'server.py'),args.action,*([args.version] if args.version else [])])
    raise SystemExit(0)

# 构建互斥锁覆盖 Git 更新、测试和发布，避免两个窗口同时修改工作区。
lock = (ROOT / 'build.lock').open('w')
fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
if not PORTAL.exists():
    run(['git','clone','--branch','dev','--single-branch','https://github.com/ethan-jus/rigour-supply-chain-portal.git',str(PORTAL)])
commits = {}
for repo in [PLATFORM,PORTAL]:
    if git(repo,'branch','--show-current') != 'dev' or git(repo,'status','--porcelain'):
        raise SystemExit(repo.name + ' 必须位于干净的 dev 分支；请先提交自己的修改。')
    run(['git','-C',str(repo),'fetch','origin','dev'])
    if git(repo,'rev-list','--count','origin/dev..HEAD') != '0':
        raise SystemExit(repo.name + ' 存在未推送提交，请确认并推送后再部署，脚本不会替你推送。')
    run(['git','-C',str(repo),'merge','--ff-only','origin/dev'])
    commits[repo.name] = git(repo,'rev-parse','HEAD')

release_id = datetime.now().strftime('%Y%m%d%H%M%S') + '-' + commits[PLATFORM.name][:12]
state = Path('/mnt/d/RigourDev/logs/build')
state.mkdir(parents=True,exist_ok=True)
log_path = state / (release_id + '.log')
print('开始发布：' + release_id + '；构建日志：' + str(log_path), flush=True)
with log_path.open('w') as log:
    settings = Path('/mnt/d/RigourDev/config/maven-settings.xml')
    run(['./mvnw','verify',*(['-s',str(settings)] if settings.exists() else []),'-B','-T','2'],cwd=PLATFORM,stdout=log,stderr=subprocess.STDOUT)
    for command in [['pnpm','install','--frozen-lockfile'],['pnpm','lint'],['pnpm','typecheck'],['pnpm','test:run']]:
        run(command,cwd=PORTAL,stdout=log,stderr=subprocess.STDOUT)
    env = dict(os.environ,VITE_OIDC_ISSUER='http://192.168.12.7:26881',
        VITE_OIDC_CLIENT_ID='rigour-portal-desktop',VITE_OIDC_REDIRECT_URI='http://192.168.12.7:5100/oidc/callback',
        VITE_OIDC_POST_LOGOUT_REDIRECT_URI='http://192.168.12.7:5100/',VITE_API_BASE_URL='/api/v1',VITE_APP_ENV='development')
    run(['pnpm','exec','vite','build','--mode','desktop'],cwd=PORTAL,env=env,stdout=log,stderr=subprocess.STDOUT)

(ROOT / 'cache').mkdir(exist_ok=True)
with tempfile.TemporaryDirectory(prefix='rigour-release-',dir=ROOT / 'cache') as staging:
    folder = Path(staging)
    for name in ['compose.yaml','Dockerfile.iam','Dockerfile.gateway','Dockerfile.portal','nginx.conf','prepare-config.py','server.py','migrate-iam-compat.py','IamCompatibilityMigration.java']:
        shutil.copy2(SOURCE / name,folder / name)
    shutil.copy2(PLATFORM / 'services/rigour-tenant-iam-service/iam-service/target/iam-service-1.0.0-SNAPSHOT.jar',folder / 'iam.jar')
    shutil.copy2(PLATFORM / 'services/rigour-api-gateway/target/rigour-api-gateway-1.0.0-SNAPSHOT.jar',folder / 'gateway.jar')
    shutil.copytree(PORTAL / 'dist',folder / 'portal')
    (folder / '发布记录.json').write_text(json.dumps({'版本':release_id,'Git提交':commits,'开发入口':'http://192.168.12.7:5100'},ensure_ascii=False,indent=2))
    hashes = {str(path.relative_to(folder)):hashlib.sha256(path.read_bytes()).hexdigest() for path in folder.rglob('*') if path.is_file()}
    (folder / 'sha256.json').write_text(json.dumps(hashes,ensure_ascii=False,indent=2))
    archive = Path('/mnt/d/RigourDev/downloads') / (release_id + '.tar.gz')
    with tarfile.open(archive,'w:gz') as stream:
        for path in folder.iterdir():
            stream.add(path,arcname=path.name)
if args.action == 'build':
    print('构建与校验完成，未修改运行中的应用。发布包：' + str(archive))
    raise SystemExit(0)
run(['python3',str(SOURCE / 'server.py'),'deploy',str(archive)])
print('发布完成。门户：http://192.168.12.7:5100；代码版本与验收信息在发布包内。')
