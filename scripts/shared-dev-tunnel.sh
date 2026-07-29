#!/usr/bin/env bash

set -euo pipefail

: "${RIGOUR_SSH_USER:?请设置个人 SSH 用户名，例如 rigour-yiran}"

server_host="${RIGOUR_SSH_HOST:-82.157.4.176}"
identity_args=()

if [[ -n "${RIGOUR_SSH_IDENTITY_FILE:-}" ]]; then
  identity_args=(-i "${RIGOUR_SSH_IDENTITY_FILE}")
fi

echo "正在建立 Rigour 共享开发环境隧道：${RIGOUR_SSH_USER}@${server_host}"
echo "保持本终端运行；按 Ctrl+C 关闭隧道。"

exec ssh \
  "${identity_args[@]}" \
  -N \
  -o ExitOnForwardFailure=yes \
  -o ServerAliveInterval=30 \
  -o ServerAliveCountMax=3 \
  -L 13306:127.0.0.1:13306 \
  -L 16379:127.0.0.1:16379 \
  -L 19876:127.0.0.1:19876 \
  -L 18081:127.0.0.1:18081 \
  -L 20909:127.0.0.1:20909 \
  -L 20911:127.0.0.1:20911 \
  -L 19000:127.0.0.1:19000 \
  -L 19001:127.0.0.1:19001 \
  "${RIGOUR_SSH_USER}@${server_host}"
