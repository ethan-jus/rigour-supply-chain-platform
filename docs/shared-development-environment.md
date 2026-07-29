# 共享开发环境接入

更新时间：2026-07-29

## 1. 使用边界

共享开发服务器为 `82.157.4.176`。MySQL、Redis、RocketMQ 和 MinIO 均运行在独立 Docker Compose 项目 `rigour-dev` 中，只监听服务器 `127.0.0.1`，不允许从公网直接连接。

开发者必须使用个人 SSH 账号和公钥建立隧道。不得共享 SSH 私钥、服务器密码或 `ubuntu` 管理账号。

服务器 ED25519 主机指纹：

```text
SHA256:/SPFX2nZGJPgZoVNcY3lJZwqEQBY0AhmZvuwioW6VWk
```

首次连接时必须核对该指纹；不一致时立即停止连接并联系项目负责人。

## 2. 已授权账号

| 开发者 | SSH 用户名 | 权限 |
|---|---|---|
| YiRan | `rigour-yiran` | 仅允许 Rigour 服务端口转发 |
| RongRong | `rigour-rongrong` | 仅允许 Rigour 服务端口转发 |

账号没有 Shell、`sudo`、Docker 或服务器凭据文件读取权限。新增成员必须创建独立账号和独立公钥，禁止复用上述账号。

## 3. 建立 SSH 隧道

在仓库根目录执行：

```bash
RIGOUR_SSH_USER=rigour-yiran ./scripts/shared-dev-tunnel.sh
```

如果私钥不在 SSH 默认位置：

```bash
RIGOUR_SSH_USER=rigour-yiran \
RIGOUR_SSH_IDENTITY_FILE="$HOME/.ssh/your_private_key" \
./scripts/shared-dev-tunnel.sh
```

RongRong 将用户名替换为 `rigour-rongrong`。脚本前台运行，按 `Ctrl+C` 关闭。出现本地端口占用时，先停止占用端口的本机服务；不要修改服务器端口或开放腾讯云安全组。

## 4. 本地连接地址

隧道建立后使用：

| 服务 | 本地地址 | 说明 |
|---|---|---|
| MySQL | `127.0.0.1:13306` | 数据库 `rigour_dev`，应用用户 `rigour_app` |
| Redis | `127.0.0.1:16379` | 已启用密码认证 |
| RocketMQ NameServer | `127.0.0.1:19876` | 对应服务器容器端口 9876 |
| RocketMQ Proxy | `127.0.0.1:18081` | 对应服务器容器端口 8081 |
| RocketMQ Broker VIP | `127.0.0.1:20909` | 对应服务器容器端口 10909 |
| RocketMQ Broker | `127.0.0.1:20911` | 对应服务器容器端口 10911 |
| MinIO API | `http://127.0.0.1:19000` | S3 兼容 API |
| MinIO Console | `http://127.0.0.1:19001` | 浏览器管理入口 |

复制 `config/shared-dev.env.example` 为个人不提交的环境文件，并通过团队密码管理器填入密码。模板中的占位密码不能用于连接。

项目当前仍是服务骨架，数据库、Redis、RocketMQ 和 MinIO 适配器尚未接入所有领域服务。模板提前冻结共享连接变量，不代表持久化、消息投递或对象存储业务实现已经完成。

## 5. 快速验收

确认隧道端口：

```bash
nc -z 127.0.0.1 13306
nc -z 127.0.0.1 16379
nc -z 127.0.0.1 19876
curl -fsS http://127.0.0.1:19000/minio/health/live
```

MinIO 健康端点成功时可能返回空响应。MySQL 和 Redis 的业务连接仍需要密码。

## 6. 服务端管理边界

Compose 文件和真实凭据位于服务器 `/opt/rigour-dev`，只有管理账号可以操作。普通开发者不得自行修改容器、端口、数据卷或服务器防火墙。

需要启停、升级、备份、恢复、增加账号或轮换凭据时，由项目负责人按根项目的《开发环境服务器部署与管理》执行并留存验收结果。
