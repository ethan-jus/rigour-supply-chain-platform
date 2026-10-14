# Platform 生产服务打包、上传、部署和重启

适用当前服务器：CentOS 7、Python 3.6.8、JDK 21；root 登录；服务目录 `/root/rigour_scm`。
本地在 IDEA 的 Terminal 执行打包和上传，Linux 终端执行服务管理。不需要新装软件。

## 1. 本次管理范围与已知状态

`all` **只代表下表九个已迁移服务**。不会操作若依、Nacos、MySQL、Redis，也不包含尚未完成本轮迁移验收的 Sales、AI。

| 服务名 | 端口 | Nacos 生产 Data ID | 当前 JVM 堆参数 |
|---|---:|---|---|
| gateway | 26880 | rigour-api-gateway-prod.yaml | 沿用未显式设置 Xms/Xmx 的方式 |
| iam | 26881 | rigour-iam-prod.yaml | 沿用未显式设置 Xms/Xmx 的方式 |
| foundation（setting） | 26892 | rigour-business-settings-prod.yaml | Xms64m / Xmx384m |
| hr | 26889 | rigour-hr-prod.yaml | Xms64m / Xmx256m |
| crm | 26883 | rigour-crm-prod.yaml | Xms64m / Xmx384m |
| erp | 26884 | rigour-erp-prod.yaml | Xms128m / Xmx512m |
| order | 26885 | rigour-order-prod.yaml | Xms64m / Xmx384m |
| integration | 26882 | rigour-integration-prod.yaml | Xms128m / Xmx512m |
| bi | 26888 | rigour-bi-prod.yaml | Xms64m / Xmx256m |

全部服务使用 `prod`，生产 Flyway 保持关闭。IAM 的 tenant-admin、scdp-client、local-signing-key 三个自动初始化开关保持关闭。

当前验收状态中，Integration 商品媒体 worker、DHB orchestration，以及 BI 自动刷新均关闭。脚本保留此状态。`READY` 只证明对应进程监听端口且健康接口返回 HTTP 200 / UP；登录、Redis 真实连接、COS 对象读写、飞书资源权限及后台数据更新仍需业务验收。

2026-09-24 用户最后一次截图的可用内存约 418 MiB，仅是当时快照。九个服务的启动检查已陆续通过，但未做生产负载容量验证。先检查内存再恢复全部服务或启用后台任务；堆上限不等于整个进程的内存上限。Gateway/IAM 的实际堆上限还需现场检查，不能把当前未显式设限的方式视为容量建议。

## 2. 现有服务器准备（只核对，不重新初始化密钥）

当前已存在：

```text
/etc/rigour/local-prod.env                         # 仅开关、目录和必要非敏感参数
/etc/rigour/local-secrets/keys/master.key          # 已有主密钥
/etc/rigour/local-secrets/secrets/common.enc       # Nacos、服务间 HMAC
/etc/rigour/local-secrets/secrets/<服务名>.enc     # 各服务凭据
/root/rigour_scm/rigour-local-secrets-tool.jar
```

env 和密钥、密文文件为 root 所有、600 权限；密钥与密文目录为 700。首次建库及录入见 [PRODUCTION_SECRETS.md](PRODUCTION_SECRETS.md)。**日常发布不再运行 `init`，不生成新 master.key，也不覆盖现有 env 或凭据文件。**

生产 Nacos 保留数据库 URL、Redis 地址/端口/database、COS region/bucket、业务开关等参数。已迁移且验收的凭据直接删除对应属性行，无需 `${...}` 或空值。Redis username 只有实际录入本机密文后才能删除。Order COS、IAM 飞书等未核实项不能据此一并删除。

## 3. 本地 IDEA：打包

先进入 Platform 项目：

```bash
cd '/Users/guorongrong/IdeaProjects/瑞盖/rigour-supply-chain-platform'
```

当前本地没有 Docker 时，打包全部九个：

```bash
bash scripts/production/package-release.sh --without-docker all
```

只更新指定服务时：

```bash
bash scripts/production/package-release.sh --without-docker iam
bash scripts/production/package-release.sh --without-docker order hr bi
```

有可用 Docker、需要运行全部选定模块测试时：

```bash
bash scripts/production/package-release.sh all
```

脚本使用当前工作区（包含未提交改动），按选定服务执行 `./mvnw -pl ... -am clean package`。`--without-docker` 明确排除十二个未配置自动跳过的 Docker 测试类，其余测试照常执行；自动跳过的容器测试和未配置测试 MySQL 的用例均不能算验证通过。名单在脚本中，新引入的 Docker 测试需重新核对。

构建成功后还会检查每个可执行 JAR 是否包含并注册本机解密组件，然后输出：

```text
RELEASE_ID=本次版本号
RELEASE_DIR=本次发布目录的绝对路径
```

目录位于 `target/production-release/<版本号>/`，包含选定服务的 `.local-secrets.jar`、管理脚本、运行参数示例、构建 Git SHA/dirty 标记和 `SHA256SUMS`。不包含生产 env、master.key 或密文。发布包不进 Git。

**构建失败即停止，不能拿之前目录中的旧包当作本次成功产物。**

## 4. 本地 IDEA：上传

仍在本地终端，整段执行。发布目录填写刚才 `RELEASE_DIR` 的值，服务器地址和端口沿用此前上传时的值：

```bash
(
  set -e
  cd '/Users/guorongrong/IdeaProjects/瑞盖/rigour-supply-chain-platform'

  printf '请输入服务器 IP 或域名：'
  IFS= read -r rigour_host
  printf '请输入 SSH 端口（回车为 22）：'
  IFS= read -r rigour_port
  printf '请输入本次 RELEASE_DIR 的完整路径：'
  IFS= read -r rigour_release_dir

  bash scripts/production/upload-release.sh \
    "root@$rigour_host" "$rigour_release_dir" "${rigour_port:-22}"
)
```

上传流程：本地校验 → SCP 到服务器独立临时目录 → 服务器校验 → 改名为正式版本目录 → 原子更新 `/root/rigour_scm/rigour-service.py`。

**上传不会替换运行中的 JAR，也不会重启业务。** 看到 `UPLOAD_OK` 才进入部署。重复使用相同版本目录会被拒绝。上传中断会留下 `.版本号.uploading`，旧服务不受影响；核对该目录确属失败上传后再处理，或重新打包生成新版本号。

## 5. Linux：查看当前状态

进入新的 root SSH 会话，避免继承此前手动 source 的旧明文环境。管理脚本自行在新 Java 子进程中加载 `/etc/rigour/local-prod.env`。

```bash
python3 /root/rigour_scm/rigour-service.py status all
free -m
vmstat 1 5
```

`READY` 表示健康；`STOPPED` 表示目标 JAR 未运行；`NOT_READY` 表示有进程但健康/端口所有者检查未通过。`status` 用于查看；要将任何未就绪状态作为命令失败，可使用：

```bash
python3 /root/rigour_scm/rigour-service.py check all
```

本脚本操作的 JAR 固定为 `/root/rigour_scm/<服务名>-server.local-secrets.jar`。它按真实进程的 JAR 路径和内核启动时间核对身份，不靠过期 PID 文件发停止信号。发现同服务多实例、旧命名 `<服务名>-server.jar` 或端口冲突时，会停止并要求核对，不自动猜测。

## 6. Linux：部署新版本

把 `本次版本号` 替换为上传成功的 `RELEASE_ID`。

整套九个服务的包：

```bash
python3 /root/rigour_scm/rigour-service.py deploy 本次版本号 all
```

只打包上传了 IAM，就只部署 IAM：

```bash
python3 /root/rigour_scm/rigour-service.py deploy 本次版本号 iam
```

只更新 Order、HR、BI：

```bash
python3 /root/rigour_scm/rigour-service.py deploy 本次版本号 order hr bi
```

脚本先对本次选择的全部服务校验 SHA256、解密组件和本机密文，再逐个处理：

1. 准备新 JAR，并备份当前 JAR；磁盘准备失败时不会先停服务。
2. 给旧进程发送 TERM，最多等待 45 秒。未退出就停止后续操作，不自动 `kill -9`。
3. 确认端口释放后原子替换 JAR，使用本机密文启动。
4. 最多等待约 120 秒，核对新 PID、端口归属以及 HTTP 200 / UP；通过后再处理下一个服务。

这是串行发布，会有短暂业务中断，不是零停机滚动发布。任何失败都会停止后续服务操作，前面已完成的服务保持当前状态。新服务健康超时时保留进程和日志供排查；必要时人工回退，不自动切回旧包。

## 7. Linux：日常启动、停止、重启

这些命令操作服务器上当前的 `.local-secrets.jar`，**不需要重新打包或上传**。

前提是服务器已安装管理脚本。第 4 节上传会自动安装；如果现在只想管理已有九个服务，可以跳过打包，先在**本地 IDEA Terminal** 单独上传脚本。将两处 `服务器地址` 和端口 `22` 替换为实际值：

```bash
cd '/Users/guorongrong/IdeaProjects/瑞盖/rigour-supply-chain-platform'
scp -P 22 scripts/production/rigour-service.py \
  root@服务器地址:/root/rigour_scm/rigour-service.py.new && \
ssh -p 22 root@服务器地址 \
  'chmod 700 /root/rigour_scm/rigour-service.py.new && mv -f /root/rigour_scm/rigour-service.py.new /root/rigour_scm/rigour-service.py'
```

随后在**Linux root 终端**执行：

```bash
# 启动单个服务
python3 /root/rigour_scm/rigour-service.py start gateway
python3 /root/rigour_scm/rigour-service.py start iam

# 重启指定服务（例如改过密文或 Nacos 配置后）
python3 /root/rigour_scm/rigour-service.py restart iam
python3 /root/rigour_scm/rigour-service.py restart order hr bi

# 停止单个或多个服务
python3 /root/rigour_scm/rigour-service.py stop gateway
python3 /root/rigour_scm/rigour-service.py stop gateway iam integration

# 停止本次九个 Platform 服务，保留若依与 Nacos
python3 /root/rigour_scm/rigour-service.py stop all

# 内存检查后，依次恢复九个服务
python3 /root/rigour_scm/rigour-service.py start all

# 逐个重启九个服务，每个健康检查通过后才继续
python3 /root/rigour_scm/rigour-service.py restart all
```

启动顺序固定为 Gateway → IAM → Foundation → HR → CRM → ERP → Order → Integration → BI。Gateway 先起只表示进程先就绪，实际登录和业务需要后端就绪。传入多个服务时也按此顺序处理。

全部停止时先停 Gateway，再停 BI、Integration 等上游任务入口，最后停 IAM。操作使用互斥锁；不要同时开多个终端重复发部署、停止或重启命令。操作中途按 Ctrl+C 后，先运行 `status all`，不要假设所有服务都已完成或未执行。

## 8. Linux：日志和回退

统一日志：`/root/rigour_scm/<服务名>-local-secrets.log`，统一 PID 记录：`<服务名>-local-secrets.pid`。管理脚本采用追加日志，不删除旧日志。Gateway 若此前使用 `gateway.log`，旧文件仍保留，管理脚本后续写入 `gateway-local-secrets.log`。

```bash
tail -n 100 -f /root/rigour_scm/iam-local-secrets.log
tail -n 100 -f /root/rigour_scm/bi-local-secrets.log
```

Ctrl+C 结束日志跟踪，不等于停止后台 Java 服务。分享报错前隐去凭据、令牌和业务数据。

每个服务更换 JAR 前会输出 `BACKUP: 服务名 备份号`。回退例子：

```bash
python3 /root/rigour_scm/rigour-service.py rollback iam 对应IAM的备份号
python3 /root/rigour_scm/rigour-service.py check iam
```

回退也会校验备份、正常停进程、替换 JAR、重新启动和检查健康，并为当前包再留一份备份。只回退应用二进制，**不回退数据库、Nacos、密文或运行参数**；如果接口、数据库或配置已发生不兼容变更，需先评估兼容性。不要直接回退到没有解密组件的旧 JAR。

## 9. 运行参数与后台任务

默认参数就是第 1 节的当前部署参数。可选覆盖文件为 `/etc/rigour/service-runtime.json`；必须 root 所有、600 权限。只接受服务堆大小与已声明后台任务开关，不存密码。

如确需调整，首次创建时从已上传版本复制示例；**已存在则不覆盖**：

```bash
test ! -e /etc/rigour/service-runtime.json && \
  install -m 600 /root/rigour_scm/releases/本次版本号/service-runtime.json.example \
    /etc/rigour/service-runtime.json

vi /etc/rigour/service-runtime.json
```

`xms_mib` / `xmx_mib` 为 MiB 正整数；`null` 表示不传对应 JVM 参数。编辑后重启受影响服务才生效。不要为了省内存直接给所有服务同一个极小堆值；先核对现有 RSS、实际堆上限和负载。

恢复后台任务时需先确认上游服务、表结构、权限和内存余量，再将对应字段改为 `true` 并重启该服务：

- `integration.product_media_worker_enabled`：商品媒体后台 worker。
- `integration.dhb_orchestration_enabled`：DHB 定时编排。
- `bi.refresh_enabled`：BI 自动刷新。

这些开关会触发实际业务处理/数据刷新，不能把健康检查通过当作启用依据。已有原生 `System.getenv()` 依赖（例如 DHB `env://`、IAM 私钥 `env:`）须继续使用原有注入方式；本机 Spring 解密不会自动生成这些操作系统环境变量。

## 10. 验收与维护

每次发布后先 `check`，再验证实际登录、受保护业务 API 和本次变更功能。首次清理 Nacos 明文后必须重启对应服务并再次验收；`check` 不验证 Redis 实际读写或全部第三方权限。

当前值删除不等于 Nacos 历史版本、备份和旧 env 中的明文已消失。所有消费者迁移完成后，再分批轮换凭据并处理旧副本；主密钥需要独立安全备份，不要和密文一起公开分发。

管理脚本使用现有 nohup 类似的后台进程方式，不提供崩溃自动拉起或开机自启。服务器重启后，先恢复并确认 Nacos、数据库等依赖，再按第 7 节手动启动 Platform。本次流程不擅自修改若依、Nacos 的启动方式，也不安装常驻进程。

离线脚本验证（本地执行，不连接生产）：

```bash
bash -n scripts/production/package-release.sh
bash -n scripts/production/upload-release.sh
python3 -B scripts/production/test_rigour_service.py
```
