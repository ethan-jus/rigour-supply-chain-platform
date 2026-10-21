#!/usr/bin/env bash
# 本地执行；仅构建，不连接服务器。兼容 macOS Bash 3.2。
set -euo pipefail
repo_dir="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$repo_dir"
all_services="gateway iam foundation hr crm erp order integration bi"
requested=""
without_docker=false
for argument in "$@"; do
  case "$argument" in
    --without-docker) without_docker=true ;;
    -h|--help)
      echo "用法：bash scripts/production/package-release.sh [--without-docker] all|服务名..."
      echo "服务：$all_services；setting 使用 foundation；all 不包含 sales/ai。"
      exit 0 ;;
    all|gateway|iam|foundation|hr|crm|erp|order|integration|bi) requested="$requested $argument" ;;
    *) echo "未知参数：$argument" >&2; exit 1 ;;
  esac
done
if [ -z "$requested" ]; then
  echo "请明确指定 all 或服务名，使用 --help 查看用法" >&2
  exit 1
fi
if [[ " $requested " == *" all "* ]]; then
  if [ "$requested" != " all" ]; then
    echo "all 不能与其他服务名混用" >&2
    exit 1
  fi
  requested=" $all_services"
fi
services=()
modules=""
for service in $all_services; do
  if [[ " $requested " == *" $service "* ]]; then
    services+=("$service")
    modules="${modules:+$modules,}services/rg-scdp-$service/$service-server"
  fi
done
command -v python3 >/dev/null
test -x ./mvnw
maven_args=(-pl "$modules" -am clean package)
if [ "$without_docker" = true ]; then
  # 当前九个 server 中必须使用 Docker、且未自动 skip 的测试；其余普通测试仍执行。
  excluded='HrPlaceholderMigrationTest,ScdpSingleProductMigrationTest,ScdpAdministratorAuthorizationTest,JdbcDhbPageSyncJobStoreTest,TenantDictionaryIntegrationTest,ErpOrderExecutionIntegrationTest,JdbcCustomerSyncJobStoreTest,HistoryOrderSyncMySqlTest,OrderAttributionAndScopeIntegrationTest,JdbcOrderRegisterStoreMySqlTest,AnalyticsSourceSnapshotContractTest,BiSupplyScopeIntegrationTest'
  maven_args+=("-Dtest=*,!${excluded//,/,!}" -Dsurefire.failIfNoSpecifiedTests=false)
  echo "使用无 Docker 构建方式；排除 12 个测试类，容器测试未验证。"
fi
./mvnw "${maven_args[@]}"

# Maven 成功后才创建发布目录；检查每个可执行包实际包含解密组件。
python3 -B - "$repo_dir" "${services[@]}" <<'PY'
import datetime
import importlib.util
import json
from pathlib import Path
import shutil
import subprocess
import sys
import uuid

root = Path(sys.argv[1])
services = sys.argv[2:]
spec = importlib.util.spec_from_file_location('rigour_release_check', str(root / 'scripts/production/rigour-service.py'))
manager = importlib.util.module_from_spec(spec)
spec.loader.exec_module(manager)
sources = []
for service in services:
    source = root / 'services' / ('rg-scdp-' + service) / (service + '-server') / 'target' / (service + '-server.jar')
    manager.validate_jar(source)
    sources.append((source, manager.jar_name(service)))
head = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=str(root), universal_newlines=True).strip()
dirty = bool(subprocess.check_output(['git', 'status', '--porcelain'], cwd=str(root)))
release_id = datetime.datetime.utcnow().strftime('%Y%m%dT%H%M%SZ') + '-' + head[:8] + '-' + uuid.uuid4().hex[:6]
directory = root / 'target' / 'production-release' / release_id
directory.mkdir(parents=True)
for source, name in sources:
    shutil.copyfile(str(source), str(directory / name))
for name in ('rigour-service.py', 'service-runtime.json.example'):
    shutil.copyfile(str(root / 'scripts/production' / name), str(directory / name))
metadata = {'release_id': release_id, 'git_head': head, 'working_tree_dirty': dirty, 'services': services}
(directory / 'release.json').write_text(json.dumps(metadata, indent=2) + '\n')
with (directory / 'SHA256SUMS').open('w') as output:
    for path in sorted(directory.iterdir()):
        if path.name != 'SHA256SUMS':
            output.write(manager.digest(path) + '  ' + path.name + '\n')
print('\nRELEASE_ID=' + release_id)
print('RELEASE_DIR=' + str(directory))
print('构建和发布包校验完成；尚未上传或部署。')
PY
