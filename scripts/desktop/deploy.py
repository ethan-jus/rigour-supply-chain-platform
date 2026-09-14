#!/usr/bin/env python3
"""Mac 一键部署：核对 Git dev、构建测试、上传并更新台式机应用。"""
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

PLATFORM = Path(__file__).resolve().parents[2]
PORTAL = PLATFORM.parent / 'rigour-supply-chain-portal'
SOURCE = Path(__file__).resolve().parent
HOST = 'admin@192.168.12.7'
KEY = Path.home() / '.ssh/rigour_workstation_dev_ed25519'
SSH = ['-o','BatchMode=yes','-o','ConnectTimeout=8','-o','StrictHostKeyChecking=yes','-o','IdentitiesOnly=yes','-i',str(KEY)]

def run(args, **kwargs):
    print('执行：' + ' '.join(str(item) for item in args), flush=True)
    return subprocess.run(args, check=True, **kwargs)

def git(repo, *args):
    return subprocess.check_output(['git','-C',str(repo),*args],text=True).strip()

def remote(command):
    run(['ssh',*SSH,HOST,command])

parser = argparse.ArgumentParser(description='中文一键部署 IAM、Gateway、门户到 D 盘开发环境')
parser.add_argument('action', nargs='?',default='deploy',choices=['deploy','status','logs','rollback'])
parser.add_argument('--version',help='回退版本号，见发布记录')
args = parser.parse_args()
if args.action != 'deploy':
    import re
    if args.action == 'rollback' and not re.fullmatch(r'[0-9]{14}-[0-9a-f]{12}',args.version or ''):
        raise SystemExit('回退时必须提供 --version 完整版本号。')
    remote('wsl.exe -d RigourDev -u root --exec python3 /mnt/d/RigourDev/scripts/app-server.py ' + args.action + (' ' + args.version if args.version else ''))
    raise SystemExit(0)

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
state = Path.home() / 'Library/Caches/RigourDev'
state.mkdir(parents=True,exist_ok=True)
log_path = state / (release_id + '.log')
print('开始发布：' + release_id + '；构建日志：' + str(log_path), flush=True)
with log_path.open('w') as log:
    run(['./mvnw','verify','-B','-T','2'],cwd=PLATFORM,stdout=log,stderr=subprocess.STDOUT)
    for command in [['pnpm','install','--frozen-lockfile'],['pnpm','lint'],['pnpm','typecheck'],['pnpm','test:run']]:
        run(command,cwd=PORTAL,stdout=log,stderr=subprocess.STDOUT)
    env = dict(os.environ,VITE_OIDC_ISSUER='http://192.168.12.7:26881',
        VITE_OIDC_CLIENT_ID='rigour-portal-browser',VITE_OIDC_REDIRECT_URI='http://192.168.12.7:5100/oidc/callback',
        VITE_OIDC_POST_LOGOUT_REDIRECT_URI='http://192.168.12.7:5100/',VITE_API_BASE_URL='/api/v1',VITE_APP_ENV='development')
    run(['pnpm','exec','vite','build','--mode','desktop'],cwd=PORTAL,env=env,stdout=log,stderr=subprocess.STDOUT)

with tempfile.TemporaryDirectory(prefix='rigour-release-') as staging:
    folder = Path(staging)
    for name in ['compose.yaml','Dockerfile.iam','Dockerfile.gateway','Dockerfile.portal','nginx.conf','prepare-config.py','server.py']:
        shutil.copy2(SOURCE / name,folder / name)
    shutil.copy2(PLATFORM / 'services/rigour-tenant-iam-service/iam-service/target/iam-service-1.0.0-SNAPSHOT.jar',folder / 'iam.jar')
    shutil.copy2(PLATFORM / 'services/rigour-api-gateway/target/rigour-api-gateway-1.0.0-SNAPSHOT.jar',folder / 'gateway.jar')
    shutil.copytree(PORTAL / 'dist',folder / 'portal')
    (folder / '发布记录.json').write_text(json.dumps({'版本':release_id,'Git提交':commits,'开发入口':'http://192.168.12.7:5100'},ensure_ascii=False,indent=2))
    hashes = {str(path.relative_to(folder)):hashlib.sha256(path.read_bytes()).hexdigest() for path in folder.rglob('*') if path.is_file()}
    (folder / 'sha256.json').write_text(json.dumps(hashes,ensure_ascii=False,indent=2))
    archive = state / (release_id + '.tar.gz')
    with tarfile.open(archive,'w:gz') as stream:
        for path in folder.iterdir():
            stream.add(path,arcname=path.name)
run(['scp',*SSH,str(SOURCE / 'server.py'),HOST + ':D:/RigourDev/scripts/app-server.py'])
run(['scp',*SSH,str(archive),HOST + ':D:/RigourDev/downloads/'])
remote('wsl.exe -d RigourDev -u root --exec python3 /mnt/d/RigourDev/scripts/app-server.py deploy /mnt/d/RigourDev/downloads/' + archive.name)
print('发布完成。门户：http://192.168.12.7:5100；代码版本与验收信息在发布包内。')
