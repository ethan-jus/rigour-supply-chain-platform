#!/bin/bash
# 台式机固定入口：先更新脚本所在仓库，再执行新版本，避免运行旧部署逻辑。
set -euo pipefail
export PATH=/srv/rigour-dev/tools/node-v24.17.0-linux-x64/bin:$PATH
export MAVEN_USER_HOME=/srv/rigour-dev/cache/maven
export npm_config_cache=/srv/rigour-dev/cache/npm
export npm_config_store_dir=/srv/rigour-dev/cache/pnpm
export MAVEN_OPTS="${MAVEN_OPTS:-} -Dmaven.repo.local=/srv/rigour-dev/cache/maven/repository"
action=${1:-deploy}
case "$action" in deploy|build|status|logs|rollback) ;; *) echo '不支持的操作'; exit 2;; esac
# 代理仅作用于本次拉代码、下载依赖，不写入应用容器；运行服务不依赖 VPN。
if [ -f /mnt/d/RigourDev/config/build-proxy.env ]; then
  set -a
  source /mnt/d/RigourDev/config/build-proxy.env
  set +a
fi
repo=/srv/rigour-dev/src/rigour-supply-chain-platform
if [ "$action" = deploy ] || [ "$action" = build ]; then
  mkdir -p /srv/rigour-dev/src
  exec 9>/srv/rigour-dev/entry.lock
  flock -n 9 || { echo '已有部署在执行，请不要重复运行。'; exit 1; }
  if [ ! -d "$repo" ]; then
    git clone --branch dev --single-branch https://github.com/ethan-jus/rigour-supply-chain-platform.git "$repo"
  fi
  [ "$(git -C "$repo" branch --show-current)" = dev ] && [ -z "$(git -C "$repo" status --porcelain)" ] || { echo '台式机代码分支或工作区异常，未覆盖本地修改。'; exit 1; }
  git -C "$repo" fetch origin dev
  [ "$(git -C "$repo" rev-list --count origin/dev..HEAD)" = 0 ] || { echo '存在未推送提交，停止部署。'; exit 1; }
  git -C "$repo" merge --ff-only origin/dev
fi
exec python3 "$repo/scripts/desktop/deploy.py" "$@"
