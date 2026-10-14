#!/usr/bin/env bash
# 上传到独立版本目录并安装管理脚本；不启动、停止或重启服务。
set -euo pipefail
if [ "${1:-}" = --help ] || [ "$#" -lt 2 ] || [ "$#" -gt 3 ]; then
  echo "用法：bash scripts/production/upload-release.sh root@服务器地址 发布目录 [SSH端口，默认22]"
  exit 0
fi
target="$1"
release_dir="$(cd "$2" && pwd)"
ssh_port="${3:-22}"
release_id="$(basename "$release_dir")"
if ! [[ "$target" =~ ^root@[A-Za-z0-9][A-Za-z0-9.-]*$ ]] ||
   ! [[ "$ssh_port" =~ ^[0-9]{1,5}$ ]] ||
   ! [[ "$release_id" =~ ^[A-Za-z0-9][A-Za-z0-9._-]{0,79}$ ]]; then
  echo "请使用 root@IPv4或域名、数字端口以及构建输出的版本目录" >&2
  exit 1
fi
if (( 10#$ssh_port < 1 || 10#$ssh_port > 65535 )); then
  echo "SSH 端口必须在 1 到 65535 之间" >&2
  exit 1
fi
test -f "$release_dir/SHA256SUMS"
# 本地先校验，拒绝丢失、改动或包含路径穿越条目的发布包。
release_services=$(python3 - "$release_dir" <<'PY'
import hashlib
import json
from pathlib import Path
import re
import sys
root = Path(sys.argv[1])
seen = set()
for line in (root / 'SHA256SUMS').read_text().splitlines():
    match = re.fullmatch(r'([0-9a-f]{64})  ([A-Za-z0-9._-]+)', line)
    if not match or match.group(2) in seen:
        sys.exit('SHA256SUMS 格式错误或重复')
    name = match.group(2)
    seen.add(name)
    path = root / name
    if not path.is_file() or path.is_symlink():
        sys.exit('发布文件缺失或是链接：' + name)
    sha = hashlib.sha256()
    with path.open('rb') as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b''):
            sha.update(block)
    if sha.hexdigest() != match.group(1):
        sys.exit('发布文件校验失败：' + name)
if not {'rigour-service.py', 'service-runtime.json.example', 'release.json'}.issubset(seen):
    sys.exit('发布包缺少管理脚本或元数据')
if set(p.name for p in root.iterdir()) != seen | {'SHA256SUMS'}:
    sys.exit('发布目录含未列入校验清单的文件')
metadata = json.loads((root / 'release.json').read_text())
services = metadata.get('services') if isinstance(metadata, dict) else None
allowed = {'gateway', 'iam', 'foundation', 'hr', 'crm', 'erp', 'order', 'integration', 'bi'}
if (not isinstance(services, list) or not services or
        any(not isinstance(s, str) or s not in allowed for s in services) or
        len(set(services)) != len(services) or metadata.get('release_id') != root.name):
    sys.exit('发布元数据中的版本号或服务名不正确')
expected_jars = {s + '-server.local-secrets.jar' for s in services}
if {name for name in seen if name.endswith('.jar')} != expected_jars:
    sys.exit('发布 JAR 与元数据中的服务清单不一致')
print(' '.join(services))
PY
)
incoming="/root/rigour_scm/releases/.$release_id.uploading"
ssh -p "$ssh_port" "$target" bash -s -- "$release_id" <<'REMOTE'
set -eu
umask 077
base=/root/rigour_scm/releases
test ! -L /root/rigour_scm
test ! -L "$base"
mkdir -p "$base"
test ! -e "$base/$1"
test ! -e "$base/.$1.uploading"
mkdir -m 700 "$base/.$1.uploading"
REMOTE
scp -P "$ssh_port" "$release_dir"/* "$target:$incoming/"
ssh -p "$ssh_port" "$target" bash -s -- "$release_id" <<'REMOTE'
set -eu
umask 077
base=/root/rigour_scm/releases
cd "$base/.$1.uploading"
sha256sum -c SHA256SUMS
chmod 600 ./*
chmod 700 rigour-service.py
cd "$base"
test ! -e "$1"
mv ".$1.uploading" "$1"
# 原子更新管理脚本；保持现有 env、主密钥、密文及运行参数文件不变。
manager_tmp=$(mktemp /root/rigour_scm/.rigour-service.XXXXXX)
install -m 700 "$base/$1/rigour-service.py" "$manager_tmp"
mv -f "$manager_tmp" /root/rigour_scm/rigour-service.py
printf 'UPLOAD_OK: %s\n' "$1"
REMOTE
printf '尚未部署服务。服务器下一步：python3 /root/rigour_scm/rigour-service.py deploy %s %s\n' "$release_id" "$release_services"
