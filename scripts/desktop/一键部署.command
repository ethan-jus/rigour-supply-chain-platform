#!/bin/bash
# 在 Mac 双击此入口，自动拉取 dev 并部署到台式机；不会自动提交或推送代码。
set -e
cd "$(dirname "$0")"
python3 deploy.py deploy
read -r -p '操作完成，按回车关闭窗口。'
