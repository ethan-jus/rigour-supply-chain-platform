#!/usr/bin/env python3
"""使用台式机 JDK 和待发布 JAR 的依赖，执行经备份的白名单补充迁移。"""
import argparse
import os
from pathlib import Path
import subprocess
import tempfile
import zipfile

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--check-only', action='store_true', help='只核对历史和白名单，不执行迁移')
args = parser.parse_args()
release = Path(__file__).resolve().parent
root = Path('/srv/rigour-dev')
env = dict(line.split('=',1) for line in (root/'.env').read_text().splitlines()
           if line and not line.startswith('#'))
assert (release/'iam.jar').is_file(), '只从已经校验的发布包执行迁移'
with tempfile.TemporaryDirectory(dir=root/'cache',prefix='iam-compat-') as temporary:
    folder = Path(temporary)
    with zipfile.ZipFile(release/'iam.jar') as jar:
        for info in jar.infolist():
            name = info.filename
            if (name.startswith('BOOT-INF/lib/') and name.endswith('.jar')) or (
                    name.startswith('BOOT-INF/classes/db/migration/') and name.endswith('.sql')):
                assert (folder/name).resolve().is_relative_to(folder)
                jar.extract(info,folder)
    subprocess.run(['java','--class-path',str(folder/'BOOT-INF/lib/*'),
                    str(release/'IamCompatibilityMigration.java'),str(folder/'BOOT-INF/classes/db/migration'),
                    *(['--check-only'] if args.check_only else [])],
                   env=dict(os.environ,IAM_DB_MIGRATOR_PASSWORD=env['IAM_DB_MIGRATOR_PASSWORD']),check=True)
